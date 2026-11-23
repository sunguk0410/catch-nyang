package com.catchnyang.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;

import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 웹소켓 연결 하나. <b>{@link #send} 는 절대 막히지 않는다.</b>
 * <p>
 * 송신 경로 (서버 = Tomcat): 큐 → Tomcat 비동기 송신({@code RemoteEndpoint.Async}). 비동기 송신은 호출한 스레드에서
 * NIO 소켓에 바로 non-blocking 으로 쓰고, 소켓 버퍼가 차 있으면 쓰기 준비 알림을 걸어 두고 즉시 돌아온다.
 * 끝나면 콜백(Tomcat I/O 스레드)이 다음 메시지를 이어서 보낸다. 그래서
 * <ul>
 *   <li>추가 스레드·스레드 전환이 없다 — 보통은 방 tick 스레드가 소켓까지 한 번에 씀 (부하 측정에서 큐 대기 p99 가 크게 줄어듦)</li>
 *   <li>느린 클라가 있어도 방 tick 스레드·로비 락은 안 막힌다 — 못 보낸 건 큐에 쌓일 뿐</li>
 *   <li>Tomcat 은 한 세션에 동시에 한 메시지만 허용 → {@code inflight} 로 하나씩만 보냄</li>
 * </ul>
 * 큐에 쌓인 바이트가 {@code maxQueuedBytes} 를 넘으면 (= 클라가 못 따라옴) 연결을 끊는다.
 * 끊기면 로비가 재접속 유예를 시작하고, 돌아오면 Snapshot 으로 한 번에 따라잡는다 — 밀린 tick 을 다 보내는 것보다 낫다.
 */
final class Connection {

    private static final Logger log = LoggerFactory.getLogger(Connection.class);

    /** 서버가 먼저 끊는 이유 (메트릭 태그) */
    enum CloseReason { IDLE, SLOW, SEND_FAILED, REPLACED }

    @FunctionalInterface
    interface Listener {
        /** 서버가 끊기로 한 순간 (소켓이 실제로 닫히기 전). 보조 스레드에서 불림 */
        void onTerminated(Connection c, CloseReason reason);
    }

    /** 큐에 넣은 뒤 실제로 소켓에 쓰기 시작하기까지 (ns) */
    @FunctionalInterface
    interface SendDelayRecorder {
        void record(long nanos);
    }

    /** 실제 소켓 쓰기. 운영은 Tomcat 비동기, 테스트용 가짜 세션은 보조 스레드에서 블로킹 */
    interface Transport {
        /** 막히지 않음. 끝나면 done(null 또는 실패 원인) — 호출 안에서 바로 불릴 수도 있음 */
        void sendBinary(ByteBuffer data, Consumer<Throwable> done);

        void sendPing(ByteBuffer data) throws Exception;

        void close(CloseStatus status) throws Exception;
    }

    private record Pending(byte[] payload, long enqueuedAt) {}

    private final WebSocketSession raw;
    private final Transport transport;
    private final Executor aux;
    private final int maxQueuedBytes;
    private final Listener listener;
    private final SendDelayRecorder sendDelay;

    private final Queue<Pending> outbox = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedBytes = new AtomicInteger();
    /** 지금 Tomcat 에 넘겨 둔 메시지가 있음 (한 번에 하나) */
    private final AtomicBoolean inflight = new AtomicBoolean();
    private final AtomicBoolean terminated = new AtomicBoolean();

    private volatile long lastInboundNanos = System.nanoTime();

    /**
     * @param aux 막혀도 되는 일(ping·close·리스너, 가짜 세션 송신)을 돌릴 실행기 (가상 스레드)
     */
    Connection(WebSocketSession raw, Executor aux, int maxQueuedBytes, Listener listener, SendDelayRecorder sendDelay,
               long asyncSendTimeoutMs) {
        this.raw = raw;
        this.aux = aux;
        this.maxQueuedBytes = maxQueuedBytes;
        this.listener = listener;
        this.sendDelay = sendDelay;
        this.transport = transportFor(raw, aux, asyncSendTimeoutMs);
    }

    /** 테스트용 */
    Connection(WebSocketSession raw, Executor aux, int maxQueuedBytes, Listener listener) {
        this(raw, aux, maxQueuedBytes, listener, n -> {}, 5_000);
    }

    String getId() {
        return raw.getId();
    }

    boolean isOpen() {
        return !terminated.get() && raw.isOpen();
    }

    // ---------- 수신 쪽 ----------

    /** 클라에서 뭐든 (메시지·pong) 오면 호출 — 하트비트 판정용 */
    void touch() {
        lastInboundNanos = System.nanoTime();
    }

    long idleNanos(long now) {
        return now - lastInboundNanos;
    }

    // ---------- 송신 ----------

    /** 큐에 넣고 (가능하면 바로) 보냄. 닫혔거나 클라가 너무 밀려서 끊었으면 false */
    boolean send(byte[] payload) {
        if (terminated.get()) return false;
        if (queuedBytes.addAndGet(payload.length) > maxQueuedBytes) {
            queuedBytes.addAndGet(-payload.length);
            terminate(CloseReason.SLOW);
            return false;
        }
        outbox.add(new Pending(payload, System.nanoTime()));
        pump();
        return true;
    }

    /** 프로토콜 수준 ping (브라우저가 자동 pong). payload = 보낸 시각(ns) → RTT 측정. 드물어서 보조 스레드에서 */
    void ping(long nowNanos) {
        if (terminated.get()) return;
        aux.execute(() -> {
            try {
                transport.sendPing(ByteBuffer.allocate(Long.BYTES).putLong(0, nowNanos));
            } catch (Exception e) {
                log.debug("ping {} failed: {}", getId(), e.toString());
            }
        });
    }

    int queuedBytes() {
        return queuedBytes.get();
    }

    /**
     * 큐에서 하나씩 Tomcat 에 넘긴다. 완료가 호출 안에서 바로 오면(소켓 버퍼에 다 들어감) 재귀 없이 이 루프에서 계속,
     * 나중에 오면 그 콜백이 다시 pump() 를 부른다. inflight 를 잡은 쪽만 보내므로 동시에 두 번 쓰는 일이 없다.
     */
    private void pump() {
        while (inflight.compareAndSet(false, true)) {
            Pending p = outbox.poll();
            if (p == null) {
                inflight.set(false);
                if (outbox.isEmpty()) return;  // 놓고 나가는 사이에 누가 넣었으면 다시 시도
                continue;
            }
            queuedBytes.addAndGet(-p.payload().length);
            if (terminated.get()) {
                inflight.set(false);
                continue;  // 끊기로 했으면 남은 건 버림
            }
            sendDelay.record(System.nanoTime() - p.enqueuedAt());

            // 0 = 보내는 중, 1 = 호출 안에서 바로 완료, 2 = 호출은 돌아왔고 완료는 나중에
            AtomicInteger state = new AtomicInteger();
            try {
                transport.sendBinary(ByteBuffer.wrap(p.payload()), err -> {
                    if (err != null) {
                        log.debug("send to {} failed: {}", getId(), err.toString());
                        terminate(CloseReason.SEND_FAILED);
                    }
                    if (!state.compareAndSet(0, 1)) {  // 비동기 완료 → 여기서 이어감
                        inflight.set(false);
                        pump();
                    }
                });
            } catch (RuntimeException e) {
                log.debug("send to {} failed: {}", getId(), e.toString());
                terminate(CloseReason.SEND_FAILED);
                state.set(1);
            }
            if (state.compareAndSet(0, 2)) return;  // 완료는 콜백이 처리
            inflight.set(false);                     // 바로 완료됨 → 다음 것
        }
    }

    // ---------- 종료 ----------

    /**
     * 서버가 이 연결을 끊음. 한 번만 동작. 리스너(로비 끊김 처리)와 실제 close 는 보조 스레드에서 —
     * Tomcat 은 close frame 을 못 보내면 (짧은 타임아웃 뒤) 소켓을 그냥 닫는다.
     */
    void terminate(CloseReason reason) {
        if (!terminated.compareAndSet(false, true)) return;
        aux.execute(() -> {
            try {
                listener.onTerminated(this, reason);
            } catch (RuntimeException e) {
                log.error("terminate listener failed for {}", getId(), e);
            }
            try {
                if (raw.isOpen()) transport.close(switch (reason) {
                    case IDLE -> CloseStatus.SESSION_NOT_RELIABLE.withReason("idle");
                    case SLOW -> CloseStatus.SESSION_NOT_RELIABLE.withReason("too slow");
                    case SEND_FAILED -> CloseStatus.SERVER_ERROR;
                    case REPLACED -> CloseStatus.POLICY_VIOLATION.withReason("reconnected elsewhere");
                });
            } catch (Exception e) {
                log.debug("close {} failed: {}", getId(), e.toString());
            }
        });
    }

    // ---------- 전송 구현 ----------

    private static Transport transportFor(WebSocketSession raw, Executor aux, long asyncSendTimeoutMs) {
        if (raw instanceof NativeWebSocketSession n && n.getNativeSession(jakarta.websocket.Session.class) instanceof jakarta.websocket.Session s) {
            return new JakartaAsync(raw, s, asyncSendTimeoutMs);
        }
        return new BlockingOnAux(raw, aux);
    }

    /** Tomcat 비동기 송신. 호출 스레드에서 non-blocking 으로 쓰고, 다 못 쓰면 Tomcat 이 나중에 마저 쓰고 콜백 */
    private static final class JakartaAsync implements Transport {
        private final WebSocketSession raw;
        private final jakarta.websocket.RemoteEndpoint.Async async;

        JakartaAsync(WebSocketSession raw, jakarta.websocket.Session s, long sendTimeoutMs) {
            this.raw = raw;
            this.async = s.getAsyncRemote();
            this.async.setSendTimeout(sendTimeoutMs);  // 이 안에 못 쓰면 실패 콜백 → 끊음
        }

        @Override
        public void sendBinary(ByteBuffer data, Consumer<Throwable> done) {
            async.sendBinary(data, result -> done.accept(result.isOK() ? null : result.getException()));
        }

        @Override
        public void sendPing(ByteBuffer data) throws Exception {
            raw.sendMessage(new PingMessage(data));
        }

        @Override
        public void close(CloseStatus status) throws Exception {
            raw.close(status);
        }
    }

    /** 네이티브 세션이 없을 때 (테스트용 가짜 세션 등): 보조 스레드에서 블로킹 송신 */
    private static final class BlockingOnAux implements Transport {
        private final WebSocketSession raw;
        private final Executor aux;

        BlockingOnAux(WebSocketSession raw, Executor aux) {
            this.raw = raw;
            this.aux = aux;
        }

        @Override
        public void sendBinary(ByteBuffer data, Consumer<Throwable> done) {
            aux.execute(() -> {
                try {
                    raw.sendMessage(new BinaryMessage(data));
                    done.accept(null);
                } catch (Exception e) {
                    done.accept(e);
                }
            });
        }

        @Override
        public void sendPing(ByteBuffer data) throws Exception {
            raw.sendMessage(new PingMessage(data));
        }

        @Override
        public void close(CloseStatus status) throws Exception {
            raw.close(status);
        }
    }
}

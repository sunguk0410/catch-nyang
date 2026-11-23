package com.catchnyang.ws;

import com.catchnyang.ws.Connection.CloseReason;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 살아 있는 연결 목록 + 하트비트.
 * <p>
 * 하트비트: pingInterval 마다 전체를 한 번 훑는다 (연결마다 타이머를 두지 않음).
 * 그동안 클라에서 아무것도 안 온 연결엔 프로토콜 ping 을 보내고 (브라우저가 자동 pong — 프론트 작업 없음),
 * idleTimeout 동안 아무것도 안 온 연결은 죽은 걸로 보고 끊는다 → 로비가 재접속 유예를 시작.
 * 와이파이 끊김·탭 멈춤처럼 TCP 가 close 없이 사라지는 경우를 잡는 장치.
 */
@Component
public class Connections implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Connections.class);

    /** 클라가 이만큼 못 받아가면 끊음. 평소 tick 은 수십~수백 바이트, 스냅샷도 수 KB 라 몇 분치 */
    static final int MAX_QUEUED_BYTES = 256 * 1024;

    private final Map<String, Connection> byId = new ConcurrentHashMap<>();
    /** 막혀도 되는 드문 일 (ping·close·끊김 처리) 용. 평소 송신은 Tomcat 비동기라 여기 안 옴 */
    private final ExecutorService aux = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ws-aux-", 0).factory());

    /** Tomcat 비동기 송신 한 건이 이 안에 안 끝나면 실패 → 끊음 */
    static final long ASYNC_SEND_TIMEOUT_MS = 5_000;
    private final ScheduledExecutorService heartbeat =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("ws-heartbeat").daemon().factory());

    private final long pingIntervalNanos;
    private final long idleTimeoutNanos;
    private final Timer rtt;
    private final Timer sendDelay;
    private final Map<CloseReason, Counter> terminated = new EnumMap<>(CloseReason.class);

    private volatile Connection.Listener onTerminated = (c, r) -> {};

    public Connections(MeterRegistry registry,
                       @Value("${game.ws.ping-interval-sec:5}") double pingIntervalSec,
                       @Value("${game.ws.idle-timeout-sec:15}") double idleTimeoutSec) {
        this.pingIntervalNanos = Math.round(pingIntervalSec * 1e9);
        this.idleTimeoutNanos = Math.round(idleTimeoutSec * 1e9);
        if (idleTimeoutNanos <= pingIntervalNanos) {
            throw new IllegalArgumentException("idle-timeout 은 ping-interval 보다 길어야 함 (pong 받을 시간)");
        }
        this.rtt = Timer.builder("ws.rtt").description("프로토콜 ping → pong 왕복 시간")
                .publishPercentiles(0.5, 0.99).register(registry);
        this.sendDelay = Timer.builder("ws.send.delay").description("송신 큐에 넣고 소켓 쓰기 시작까지 (서버 내부 지연)")
                .publishPercentiles(0.5, 0.99).register(registry);
        for (CloseReason r : CloseReason.values()) {
            terminated.put(r, Counter.builder("ws.terminated").tag("reason", r.name())
                    .description("서버가 먼저 끊은 연결").register(registry));
        }
        Gauge.builder("ws.connections", byId, Map::size).register(registry);
        Gauge.builder("ws.queued.bytes", this, Connections::totalQueuedBytes)
                .description("아직 못 보낸 송신 바이트 합").register(registry);

        long periodMs = Math.max(100, TimeUnit.NANOSECONDS.toMillis(pingIntervalNanos));
        heartbeat.scheduleAtFixedRate(this::beat, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** 서버가 끊을 때 (idle·slow…) 부를 것. 로비가 등록 */
    void onTerminated(Connection.Listener l) {
        this.onTerminated = l;
    }

    Connection open(WebSocketSession raw) {
        Connection c = new Connection(raw, aux, MAX_QUEUED_BYTES, (conn, reason) -> {
            terminated.get(reason).increment();
            log.info("terminate {}: {}", conn.getId(), reason);
            onTerminated.onTerminated(conn, reason);
        }, n -> sendDelay.record(n, TimeUnit.NANOSECONDS), ASYNC_SEND_TIMEOUT_MS);
        byId.put(raw.getId(), c);
        return c;
    }

    Connection get(String id) {
        return byId.get(id);
    }

    Connection remove(String id) {
        return byId.remove(id);
    }

    /** pong 의 payload = 우리가 보낸 시각 */
    void pong(Connection c, ByteBuffer payload) {
        c.touch();
        if (payload.remaining() == Long.BYTES) {
            long sent = payload.getLong(payload.position());
            long d = System.nanoTime() - sent;
            if (d >= 0 && d < idleTimeoutNanos) rtt.record(d, TimeUnit.NANOSECONDS);
        }
    }

    int size() {
        return byId.size();
    }

    private double totalQueuedBytes() {
        long sum = 0;
        for (Connection c : byId.values()) sum += c.queuedBytes();
        return sum;
    }

    /** 하트비트 한 바퀴. 테스트에서도 직접 부름 */
    void beat() {
        try {
            long now = System.nanoTime();
            for (Connection c : byId.values()) {
                long idle = c.idleNanos(now);
                if (idle >= idleTimeoutNanos) {
                    c.terminate(CloseReason.IDLE);
                } else if (idle >= pingIntervalNanos - TimeUnit.MILLISECONDS.toNanos(50)) {
                    c.ping(now);  // 50ms 여유: 스케줄 오차로 한 바퀴 건너뛰지 않게
                }
            }
        } catch (RuntimeException e) {
            log.error("heartbeat failed", e);  // 스케줄러가 멈추면 안 됨
        }
    }

    @Override
    public void close() {
        heartbeat.shutdownNow();
        aux.shutdownNow();
    }
}

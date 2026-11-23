package com.catchnyang.ws;

import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigLoader;
import com.catchnyang.room.GameLoop;
import com.catchnyang.room.GameRoom;
import com.catchnyang.room.GameRooms;
import com.catchnyang.room.MatchInfo;
import com.catchnyang.ws.Connection.CloseReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionTest {

    static final GameConfig CFG = GameConfigLoader.loadDefault();

    final ExecutorService writers = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        writers.shutdownNow();
    }

    /** 가짜 웹소켓 세션: 보낸 걸 기록하고, gate 가 닫혀 있으면 sendMessage 가 막힘 (= 안 읽는 클라) */
    static final class FakeSession {
        final List<WebSocketMessage<?>> sent = new CopyOnWriteArrayList<>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final AtomicBoolean open = new AtomicBoolean(true);
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
        volatile CountDownLatch gate = new CountDownLatch(0);
        final String id;

        FakeSession(String id) {
            this.id = id;
        }

        WebSocketSession proxy() {
            return (WebSocketSession) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{WebSocketSession.class}, (p, m, args) -> switch (m.getName()) {
                        case "getId" -> id;
                        case "isOpen" -> open.get();
                        case "sendMessage" -> {
                            int n = inFlight.incrementAndGet();
                            maxInFlight.accumulateAndGet(n, Math::max);
                            try {
                                gate.await();
                                sent.add((WebSocketMessage<?>) args[0]);
                            } finally {
                                inFlight.decrementAndGet();
                            }
                            yield null;
                        }
                        case "close" -> {
                            open.set(false);
                            closed.complete(args != null && args.length == 1 ? (CloseStatus) args[0] : CloseStatus.NORMAL);
                            gate.countDown();  // Tomcat 도 소켓을 닫아 막힌 쓰기를 풀어 줌
                            yield null;
                        }
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        case "toString" -> "FakeSession(" + id + ")";
                        default -> null;
                    });
        }
    }

    @Test
    @DisplayName("send 는 소켓이 막혀 있어도 바로 돌아오고, 순서대로 전부 나감")
    void sendNeverBlocks() throws Exception {
        FakeSession fs = new FakeSession("a");
        fs.gate = new CountDownLatch(1);  // 클라가 안 읽음
        Connection c = new Connection(fs.proxy(), writers, 1 << 20, (x, r) -> {});

        long t0 = System.nanoTime();
        for (int i = 0; i < 1000; i++) assertThat(c.send(new byte[]{(byte) i, (byte) (i >> 8)})).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(200);

        fs.gate.countDown();  // 다시 읽기 시작
        long deadline = System.currentTimeMillis() + 3000;
        while (fs.sent.size() < 1000 && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(fs.sent).hasSize(1000);
        for (int i = 0; i < 1000; i++) {
            ByteBuffer b = ((BinaryMessage) fs.sent.get(i)).getPayload();
            assertThat((b.get(0) & 0xFF) | (b.get(1) & 0xFF) << 8).isEqualTo(i);
        }
        assertThat(c.queuedBytes()).isZero();
    }

    @Test
    @DisplayName("여러 스레드가 동시에 보내도 소켓에는 한 번에 하나만 씀 (Tomcat 동시 쓰기 금지)")
    void singleWriter() throws Exception {
        FakeSession fs = new FakeSession("b");
        Connection c = new Connection(fs.proxy(), writers, 1 << 20, (x, r) -> {});
        int threads = 8, each = 500;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            for (int t = 0; t < threads; t++) pool.execute(() -> {
                for (int i = 0; i < each; i++) c.send(new byte[16]);
            });
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (fs.sent.size() < threads * each && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(fs.sent).hasSize(threads * each);
        assertThat(fs.maxInFlight.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("못 받아간 바이트가 상한을 넘으면 SLOW 로 끊고 리스너 호출, 이후 send 는 false")
    void overflowTerminates() throws Exception {
        FakeSession fs = new FakeSession("c");
        fs.gate = new CountDownLatch(1);
        CompletableFuture<CloseReason> reason = new CompletableFuture<>();
        Connection c = new Connection(fs.proxy(), writers, 1000, (x, r) -> reason.complete(r));

        int ok = 0;
        while (c.send(new byte[100])) ok++;
        assertThat(ok).isBetween(9, 11);
        assertThat(reason.get(1, TimeUnit.SECONDS)).isEqualTo(CloseReason.SLOW);
        assertThat(fs.closed.get(1, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.SESSION_NOT_RELIABLE.getCode());
        assertThat(c.isOpen()).isFalse();
        assertThat(c.send(new byte[1])).isFalse();
    }

    @Test
    @DisplayName("하트비트: 조용하면 ping, 더 조용하면 IDLE 로 끊음. 뭐라도 오면 살아 있음")
    void heartbeat() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (Connections conns = new Connections(registry, 3600, 7200)) {  // 자동 스케줄은 사실상 끔, beat() 직접 호출
            FakeSession quiet = new FakeSession("quiet");
            FakeSession chatty = new FakeSession("chatty");
            CompletableFuture<Connection> terminated = new CompletableFuture<>();
            conns.onTerminated((c, r) -> terminated.complete(c));
            Connection q = conns.open(quiet.proxy());
            Connection ch = conns.open(chatty.proxy());

            // 시간을 흉내: 마지막 수신 시각을 과거로
            setLastInbound(q, 3601);
            setLastInbound(ch, 3601);
            ch.touch();
            conns.beat();
            Thread.sleep(100);
            assertThat(quiet.sent).hasSize(1).first().isInstanceOf(PingMessage.class);
            assertThat(chatty.sent).isEmpty();

            // pong 오면 RTT 기록 + 살아남
            PingMessage ping = (PingMessage) quiet.sent.getFirst();
            conns.pong(q, ping.getPayload());
            assertThat(registry.get("ws.rtt").timer().count()).isEqualTo(1);
            assertThat(q.idleNanos(System.nanoTime())).isLessThan(TimeUnit.SECONDS.toNanos(1));

            setLastInbound(q, 7201);
            conns.beat();
            assertThat(terminated.get(1, TimeUnit.SECONDS)).isSameAs(q);
            assertThat(quiet.closed.get(1, TimeUnit.SECONDS).getReason()).isEqualTo("idle");
            assertThat(registry.get("ws.terminated").tag("reason", "IDLE").counter().count()).isEqualTo(1);
            assertThat(ch.isOpen()).isTrue();
        }
    }

    static void setLastInbound(Connection c, long secondsAgo) throws Exception {
        var f = Connection.class.getDeclaredField("lastInboundNanos");
        f.setAccessible(true);
        f.setLong(c, System.nanoTime() - TimeUnit.SECONDS.toNanos(secondsAgo));
    }

    @Test
    @DisplayName("한 플레이어 소켓이 완전히 막혀도 게임 루프는 제 속도로 돌고 상대는 계속 받음")
    void stuckPlayerDoesNotStallRoom() throws Exception {
        long tickMs = 1000L / CFG.server().tickHz();
        Warmup.run(CFG);  // 첫 tick 클래스 로딩 지연을 빼고 "막힘" 만 보려고
        try (GameLoop loop = new GameLoop(tickMs, new SimpleMeterRegistry(), r -> {})) {
            loop.start();
            MessageSender sender = new MessageSender(false);
            FakeSession stuck = new FakeSession("stuck");
            stuck.gate = new CountDownLatch(1);  // 영원히 안 읽음
            FakeSession fine = new FakeSession("fine");
            Connection cs = new Connection(stuck.proxy(), writers, 1 << 20, (x, r) -> {});
            Connection cf = new Connection(fine.proxy(), writers, 1 << 20, (x, r) -> {});

            GameRooms rooms = new GameRooms(CFG, loop);
            GameRoom room = rooms.create(MatchInfo.anonymous(),
                    new WsPlayerChannel(sender, cs), new WsPlayerChannel(sender, cf), r -> {});
            // 비교 대상: 같은 루프에서 도는 정상 방 (절대 시간은 OS 타이머 해상도에 따라 흔들리므로 상대 비교)
            GameRoom control = rooms.create(batch -> {}, batch -> {});

            Thread.sleep(2000);
            // 막힌 쪽 때문에 방 스레드가 멈췄다면 게임 시간이 정상 방보다 크게 뒤처졌을 것
            assertThat(room.match().now()).isGreaterThan(1000);
            assertThat(Math.abs(room.match().now() - control.match().now())).isLessThanOrEqualTo(2 * tickMs);
            assertThat(fine.sent.size()).isGreaterThanOrEqualTo(2);    // 스냅샷 + 1초마다 Clock
            assertThat(stuck.sent).isEmpty();
            assertThat(cs.queuedBytes()).isPositive();                 // 못 보낸 건 큐에만 쌓임
            stuck.gate.countDown();
        }
    }
}

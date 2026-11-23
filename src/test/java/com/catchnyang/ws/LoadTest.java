package com.catchnyang.ws;

import com.catchnyang.proto.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 부하 측정: 방 N개(클라 2N개)를 실제 웹소켓으로 붙여 진행 단계에서 일정 시간 돌린다.
 * 기본 빌드에선 빠짐. 실행: {@code ./gradlew loadTest -Dload.rooms=500 -Dload.seconds=20}
 * <p>
 * 클라도 같은 JVM 에서 돌기 때문에 CPU·지연 수치는 실제 서버 단독보다 나쁘게 나온다 (보수적).
 */
@Tag("load")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.debug-log-messages=false", "logging.level.com.catchnyang=WARN"})
class LoadTest {

    static final int ROOMS = Integer.getInteger("load.rooms", 200);
    static final int SECONDS = Integer.getInteger("load.seconds", 15);

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    final List<Bot> bots = new CopyOnWriteArrayList<>();
    final List<String> codes = new CopyOnWriteArrayList<>();

    /** 받은 걸 시각과 함께 기록하는 봇 */
    static final class Bot extends BinaryWebSocketHandler {
        WebSocketSession session;
        volatile Role role;
        volatile boolean playing;
        volatile boolean measuring;
        volatile String roomCode;
        final CountDownLatch created = new CountDownLatch(1);
        final CountDownLatch found = new CountDownLatch(1);
        final AtomicInteger seq = new AtomicInteger();
        /** seq → 보낸 시각 */
        final Map<Integer, Long> sentAt = new ConcurrentHashMap<>();
        volatile int lastAck;
        final List<Long> tickGapsNanos = Collections.synchronizedList(new ArrayList<>());
        final List<Long> ackNanos = Collections.synchronizedList(new ArrayList<>());
        long lastTickAt;
        long bytesIn;
        long framesIn;

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) throws Exception {
            long now = System.nanoTime();
            ServerMessage msg = ServerMessage.parseFrom(m.getPayload());
            if (msg.hasRoomCreated()) {
                roomCode = msg.getRoomCreated().getRoomCode();
                created.countDown();
            } else if (msg.hasMatchFound()) {
                role = msg.getMatchFound().getMyRole();
                found.countDown();
            } else if (msg.hasTick()) {
                GameTick t = msg.getTick();
                if (t.getPhase() == Phase.PLAYING) playing = true;
                if (measuring) {
                    bytesIn += m.getPayloadLength();
                    framesIn++;
                    if (lastTickAt != 0) tickGapsNanos.add(now - lastTickAt);
                    int ack = t.getAckSeq();
                    for (int s2 = lastAck + 1; s2 <= ack; s2++) {
                        Long at = sentAt.remove(s2);
                        if (at != null) ackNanos.add(now - at);
                    }
                    if (ack > lastAck) lastAck = ack;
                }
                lastTickAt = now;
            }
        }

        void send(ClientMessage.Builder b) {
            int n = seq.incrementAndGet();
            if (measuring) sentAt.put(n, System.nanoTime());
            try {
                synchronized (this) {
                    session.sendMessage(new BinaryMessage(b.setSeq(n).build().toByteArray()));
                }
            } catch (IOException | IllegalStateException e) {
                // 측정 중 끊김은 아래 집계에서 드러남
            }
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Bot b : bots) if (b.session != null && b.session.isOpen()) b.session.close();
        Thread.sleep(2000);
        for (String code : codes) {
            jdbc.update("UPDATE game_match SET rematch_of = NULL WHERE room_code = ?", code);
            jdbc.update("DELETE mp FROM match_player mp JOIN game_match gm ON gm.id = mp.match_id WHERE gm.room_code = ?", code);
            jdbc.update("DELETE FROM game_match WHERE room_code = ?", code);
        }
    }

    @Test
    void load() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        String url = "ws://localhost:" + port + "/ws";

        // 1) 접속 + 방 코드로 짝짓기
        long t0 = System.nanoTime();
        List<Bot[]> pairs = new ArrayList<>();
        for (int i = 0; i < ROOMS; i++) {
            Bot a = new Bot(), b = new Bot();
            a.session = client.execute(a, url).get(10, TimeUnit.SECONDS);
            b.session = client.execute(b, url).get(10, TimeUnit.SECONDS);
            bots.add(a);
            bots.add(b);
            pairs.add(new Bot[]{a, b});
        }
        for (Bot[] p : pairs) p[0].send(ClientMessage.newBuilder().setCreateRoom(CreateRoom.newBuilder().setNickname("a")));
        for (Bot[] p : pairs) {
            assertThat(p[0].created.await(10, TimeUnit.SECONDS)).isTrue();
            codes.add(p[0].roomCode);
            p[1].send(ClientMessage.newBuilder().setJoinRoom(JoinRoom.newBuilder().setNickname("b").setRoomCode(p[0].roomCode)));
        }
        for (Bot b : bots) assertThat(b.found.await(10, TimeUnit.SECONDS)).isTrue();
        long connectMs = (System.nanoTime() - t0) / 1_000_000;

        // 2) 준비 시간 지나 진행 단계까지
        long deadline = System.currentTimeMillis() + 20_000;
        while (bots.stream().anyMatch(b -> !b.playing) && System.currentTimeMillis() < deadline) Thread.sleep(100);
        assertThat(bots).allMatch(b -> b.playing);

        Timer tick = registry.get("game.tick.duration").timer();
        double overrunBefore = registry.get("game.tick.overrun").counter().count();
        long tickCountBefore = tick.count();
        double tickTotalBefore = tick.totalTime(TimeUnit.MILLISECONDS);
        var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long cpuBefore = os.getProcessCpuTime();
        long gcBefore = gcMillis();

        // 3) 측정: 감독관은 100ms 마다 방향을 바꿔 계속 움직임(→ 매 tick 위치 갱신), 냥이는 3초마다 빙의 이동
        for (Bot b : bots) b.measuring = true;
        ScheduledExecutorService drivers = Executors.newScheduledThreadPool(4);
        AtomicInteger step = new AtomicInteger();
        drivers.scheduleAtFixedRate(() -> {
            int s = step.incrementAndGet();
            for (Bot b : bots) {
                if (b.role == Role.PROCTOR) {
                    b.send(ClientMessage.newBuilder().setProctorMove(ProctorMove.newBuilder()
                            .setDx(s % 2 == 0 ? 1 : -1).setDz(0.3f).setFaceX(s % 2 == 0 ? 1 : -1).setFaceZ(-1)));
                } else if (s % 31 == 0) {
                    b.send(ClientMessage.newBuilder().setPossess(Possess.newBuilder().setSeat(s % 9)));
                }
            }
        }, 0, 100, TimeUnit.MILLISECONDS);
        long m0 = System.nanoTime();
        Thread.sleep(SECONDS * 1000L);
        drivers.shutdownNow();
        for (Bot b : bots) b.measuring = false;
        double wallSec = (System.nanoTime() - m0) / 1e9;

        // 4) 집계
        long ticks = tick.count() - tickCountBefore;
        double tickAvgMs = (tick.totalTime(TimeUnit.MILLISECONDS) - tickTotalBefore) / Math.max(1, ticks);
        double overruns = registry.get("game.tick.overrun").counter().count() - overrunBefore;
        double cpuCores = (os.getProcessCpuTime() - cpuBefore) / 1e9 / wallSec;
        long gcMs = gcMillis() - gcBefore;

        List<Long> gaps = new ArrayList<>();
        List<Long> acks = new ArrayList<>();
        long bytes = 0, frames = 0;
        for (Bot b : bots) {
            gaps.addAll(b.tickGapsNanos);
            if (b.role == Role.PROCTOR) acks.addAll(b.ackNanos);
            bytes += b.bytesIn;
            frames += b.framesIn;
        }
        Collections.sort(gaps);
        Collections.sort(acks);

        Timer rtt = registry.find("ws.rtt").timer();
        Timer sendDelay = registry.get("ws.send.delay").timer();
        String report = String.format("""

                ================ 부하 측정 ================
                방 %d개 / 연결 %d개 / 측정 %.1f초 (접속·매칭 %d ms)
                CPU (서버+봇 같은 JVM)   : %.2f 코어, GC %d ms
                서버 tick 처리 (방 하나)  : 평균 %.3f ms, 최대 %.2f ms, p99 %s
                tick 건너뜀(overrun)      : %.0f 회 / %d tick
                tick 수신 간격 (목표 50)  : p50 %.1f / p99 %.1f / 최대 %.1f ms
                입력 → ack 지연           : p50 %.1f / p99 %.1f / 최대 %.1f ms  (tick 대기 0~50ms 포함)
                하향 트래픽 (연결당)      : %.0f B/s, %.1f 프레임/s, 프레임당 %.0f B
                ping RTT                  : %s
                서버 송신 큐 대기         : p50 %s / p99 %s / 최대 %.2f ms
                ==========================================
                """,
                ROOMS, bots.size(), wallSec, connectMs,
                cpuCores, gcMs,
                tickAvgMs, tick.max(TimeUnit.MILLISECONDS), pct(tick, 0.99),
                overruns, ticks,
                ms(gaps, 0.5), ms(gaps, 0.99), ms(gaps, 1.0),
                ms(acks, 0.5), ms(acks, 0.99), ms(acks, 1.0),
                bytes / wallSec / bots.size(), frames / wallSec / bots.size(), frames == 0 ? 0.0 : (double) bytes / frames,
                rtt == null || rtt.count() == 0 ? "(측정 구간에 ping 없음 — 계속 말하는 연결엔 안 보냄)" : pct(rtt, 0.99),
                pct(sendDelay, 0.5), pct(sendDelay, 0.99), sendDelay.max(TimeUnit.MILLISECONDS));
        System.out.println(report);

        assertThat(ms(gaps, 0.99)).isLessThan(150);
        assertThat(overruns).isLessThanOrEqualTo(ticks * 0.01);
    }

    static double ms(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return Double.NaN;
        int i = (int) Math.min(sorted.size() - 1, Math.max(0, Math.ceil(p * sorted.size()) - 1));
        return sorted.get(i) / 1e6;
    }

    static String pct(Timer t, double p) {
        for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
            if (v.percentile() == p) return String.format("%.2f ms", v.value(TimeUnit.MILLISECONDS));
        }
        return "n/a";
    }

    static long gcMillis() {
        long sum = 0;
        for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) sum += Math.max(0, gc.getCollectionTime());
        return sum;
    }
}

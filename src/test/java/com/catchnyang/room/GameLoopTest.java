package com.catchnyang.room;

import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigLoader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class GameLoopTest {

    static final GameConfig CFG = GameConfigLoader.loadDefault();

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    CompletableFuture<GameRoom> ended = new CompletableFuture<>();
    GameLoop loop = new GameLoop(1000L / CFG.server().tickHz(), registry, ended::complete);
    GameRooms rooms = new GameRooms(CFG, loop);

    @AfterEach
    void tearDown() {
        loop.close();
    }

    static void await(BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) throw new AssertionError("timeout");
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("스케줄러가 50ms 마다 방을 tick 하고 메트릭을 남김")
    void ticksRoomsAndRecordsMetrics() throws Exception {
        loop.start();
        GameRoom room = rooms.create(batch -> {}, batch -> {});
        assertThat(rooms.activeRooms()).isEqualTo(1);

        await(() -> room.match().now() >= 500, 3000);  // 게임 시간 0.5초 = tick 10번

        assertThat(registry.get("game.tick.duration").timer().count()).isGreaterThanOrEqualTo(10);
        assertThat(registry.get("game.tick.jitter").timer().count()).isGreaterThanOrEqualTo(10);
        assertThat(registry.get("game.rooms.active").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("판이 끝난 방은 루프에서 빠지고 종료 리스너가 불림")
    void finishedRoomIsRemoved() throws Exception {
        loop.start();
        GameRoom room = rooms.create(batch -> {}, batch -> {});
        room.submit(Role.CAT, new GameCommand.Leave());

        GameRoom endedRoom = ended.get(2, TimeUnit.SECONDS);
        assertThat(endedRoom).isSameAs(room);
        assertThat(endedRoom.match().winner()).isEqualTo(Role.PROCTOR);
        await(() -> rooms.activeRooms() == 0, 1000);
    }

    @Test
    @DisplayName("방 하나의 송신이 터져도 루프는 계속 돎")
    void sendFailureDoesNotStopLoop() throws Exception {
        loop.start();
        GameRoom room = rooms.create(batch -> { throw new IllegalStateException("boom"); }, batch -> {});
        await(() -> room.match().now() >= 10_500, 15_000);  // 준비 10초 지나 PhaseChanged 송신 실패 이후에도 진행
    }
}

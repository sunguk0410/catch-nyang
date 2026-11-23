package com.catchnyang.room;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 공유 스케줄러 스레드 1개가 tickMs 마다 모든 방에 "tick 하라"고 신호를 주고,
 * 실제 tick 은 방마다 전용 (가상) 스레드에서 돈다 → 방 안에선 락 없음, 방끼리는 서로 안 막음.
 * 이전 tick 이 아직 안 끝난 방은 이번 신호를 건너뛰고 overrun 으로 센다.
 */
public final class GameLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameLoop.class);

    private final long tickMs;
    private final MatchEndListener onEnd;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("game-scheduler").daemon().factory());
    private final Map<String, RoomRunner> rooms = new ConcurrentHashMap<>();

    private final Timer tickDuration;
    private final Timer tickJitter;
    private final Counter tickOverrun;

    private long lastFireNanos;

    private final class RoomRunner {
        final GameRoom room;
        final ExecutorService thread;
        final AtomicBoolean running = new AtomicBoolean();

        RoomRunner(GameRoom room) {
            this.room = room;
            this.thread = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("room-" + room.id()).factory());
        }
    }

    public GameLoop(long tickMs, MeterRegistry registry, MatchEndListener onEnd) {
        this.tickMs = tickMs;
        this.onEnd = onEnd;
        this.tickDuration = Timer.builder("game.tick.duration")
                .description("방 하나의 tick 처리 시간").publishPercentiles(0.5, 0.99).register(registry);
        this.tickJitter = Timer.builder("game.tick.jitter")
                .description("스케줄러 신호 간격이 tickMs 에서 벗어난 정도").publishPercentiles(0.5, 0.99).register(registry);
        this.tickOverrun = Counter.builder("game.tick.overrun")
                .description("이전 tick 이 안 끝나 건너뛴 횟수").register(registry);
        Gauge.builder("game.rooms.active", rooms, Map::size).register(registry);
    }

    public void start() {
        lastFireNanos = System.nanoTime();
        scheduler.scheduleAtFixedRate(this::fire, tickMs, tickMs, TimeUnit.MILLISECONDS);
    }

    public void add(GameRoom room) {
        rooms.put(room.id(), new RoomRunner(room));
    }

    public int activeRooms() {
        return rooms.size();
    }

    private void fire() {
        long nowNanos = System.nanoTime();
        long expected = TimeUnit.MILLISECONDS.toNanos(tickMs);
        tickJitter.record(Math.abs(nowNanos - lastFireNanos - expected), TimeUnit.NANOSECONDS);
        lastFireNanos = nowNanos;

        for (RoomRunner r : rooms.values()) {
            if (!r.running.compareAndSet(false, true)) {
                tickOverrun.increment();
                continue;
            }
            r.thread.execute(() -> runTick(r));
        }
    }

    private void runTick(RoomRunner r) {
        long t0 = System.nanoTime();
        try {
            r.room.tick();
        } catch (Throwable t) {
            log.error("room {} tick failed", r.room.id(), t);
        } finally {
            tickDuration.record(System.nanoTime() - t0, TimeUnit.NANOSECONDS);
            r.running.set(false);
        }
        if (r.room.isFinished() && rooms.remove(r.room.id(), r)) {
            r.thread.shutdown();
            try {
                onEnd.onMatchEnded(r.room);
            } catch (RuntimeException e) {
                log.error("room {} end listener failed", r.room.id(), e);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        rooms.values().forEach(r -> r.thread.shutdownNow());
        rooms.clear();
    }
}

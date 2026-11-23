package com.catchnyang.history;

import com.catchnyang.room.GameRoom;
import com.catchnyang.room.MatchEndListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 판이 끝나면 결과를 비동기로 DB 에 저장. 게임 스레드는 값만 복사하고 바로 돌아간다.
 * 저장이 실패해도 게임에는 영향 없음 (로그만 남김 — 통계용 데이터라 재시도 큐까지는 두지 않음).
 */
@Component
public class MatchRecorder implements MatchEndListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MatchRecorder.class);

    private final MatchResultWriter writer;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("match-recorder").factory());

    public MatchRecorder(MatchResultWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onMatchEnded(GameRoom room) {
        MatchResult result = MatchResult.of(room, Instant.now());
        executor.execute(() -> {
            try {
                long id = writer.save(result);
                log.info("match {} saved as game_match#{} winner={} reason={} avg={} target={}",
                        room.id(), id, result.winner(), result.endReason(), result.finalAverage(), result.finalTarget());
            } catch (RuntimeException e) {
                log.error("match {} save failed: {}", room.id(), result, e);
            }
        });
    }

    /** 종료 시 밀린 저장은 끝내고 내려감 (최대 10초) */
    @Override
    public void close() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            log.warn("match recorder: pending saves dropped on shutdown");
            executor.shutdownNow();
        }
    }
}

package com.catchnyang.history;

import com.catchnyang.room.GameRoom;
import com.catchnyang.room.MatchEndListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 판이 끝나면 결과를 비동기로 DB 에 저장. 게임 스레드는 값만 복사하고 바로 돌아간다.
 * 저장이 실패해도 게임에는 영향 없음 (로그만 남김 — 통계용 데이터라 재시도 큐까지는 두지 않음).
 * <p>
 * 재대결의 rematch_of: 저장 스레드가 하나라 이전 판은 항상 먼저 저장돼 있다
 * (재대결은 이전 판이 끝난 뒤에야 시작되므로). 방 id → game_match.id 를 최근 것만 기억해 둔다.
 */
@Component
public class MatchRecorder implements MatchEndListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MatchRecorder.class);

    /** 재대결 대기 시간 안에 찾을 것만 있으면 됨. 넘치면 오래된 것부터 버림 */
    static final int REMEMBER_LAST = 10_000;

    private final MatchResultWriter writer;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(Thread.ofPlatform().name("match-recorder").factory());

    /** 저장 스레드에서만 접근 */
    private final Map<String, Long> savedIds = new LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > REMEMBER_LAST;
        }
    };

    public MatchRecorder(MatchResultWriter writer) {
        this.writer = writer;
    }

    @Override
    public void onMatchEnded(GameRoom room) {
        MatchResult result = MatchResult.of(room, Instant.now());
        executor.execute(() -> save(result));
    }

    private void save(MatchResult result) {
        Long rematchOf = null;
        if (result.rematchOfRoomId() != null) {
            rematchOf = savedIds.get(result.rematchOfRoomId());
            if (rematchOf == null) log.warn("match {}: previous match {} not saved, rematch_of = null",
                    result.roomId(), result.rematchOfRoomId());
        }
        try {
            long id = writer.save(result, rematchOf);
            savedIds.put(result.roomId(), id);
            log.info("match {} saved as game_match#{} winner={} reason={} avg={} target={} rematchOf={}",
                    result.roomId(), id, result.winner(), result.endReason(),
                    result.finalAverage(), result.finalTarget(), rematchOf);
        } catch (RuntimeException e) {
            log.error("match {} save failed: {}", result.roomId(), result, e);
        }
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

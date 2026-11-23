package com.catchnyang.history;

import com.catchnyang.game.EndReason;
import com.catchnyang.game.Role;
import com.catchnyang.room.GameRoom;
import com.catchnyang.room.MatchType;

import java.time.Instant;
import java.util.Map;

/** 저장할 판 결과 (방에서 복사해 둔 값 — 다른 스레드로 넘겨도 안전) */
public record MatchResult(
        MatchType type,
        String roomCode,
        Instant startedAt,
        Instant endedAt,
        Role winner,
        EndReason endReason,
        double finalAverage,
        int finalTarget,
        String ruleVersion,
        String catNickname,
        String proctorNickname,
        Map<String, Integer> catStats,
        Map<String, Integer> proctorStats
) {
    /** 끝난 방에서 (그 방 스레드에서) 만든다 */
    public static MatchResult of(GameRoom room, Instant endedAt) {
        var m = room.match();
        var info = room.info();
        return new MatchResult(info.type(), info.roomCode(), info.startedAt(), endedAt,
                m.winner(), m.endReason(), m.classAverage(), m.target(), m.ruleVersion(),
                info.catNickname(), info.proctorNickname(),
                Map.copyOf(room.stats().of(Role.CAT)), Map.copyOf(room.stats().of(Role.PROCTOR)));
    }
}

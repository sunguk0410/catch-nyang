package com.catchnyang.room;

import java.time.Instant;

/** 판 결과 저장용 메타데이터 (규칙과 무관) */
public record MatchInfo(MatchType type, String roomCode, String catNickname, String proctorNickname, Instant startedAt) {

    /** 테스트·도구용 */
    public static MatchInfo anonymous() {
        return new MatchInfo(MatchType.RANDOM, null, "cat", "proctor", Instant.now());
    }
}

package com.catchnyang.room;

import java.time.Instant;

/**
 * 판 결과 저장용 메타데이터 (규칙과 무관).
 *
 * @param rematchOfRoomId 역할 바꾼 재대결이면 이전 판의 방 id, 아니면 null (DB 에선 game_match.rematch_of 로 바뀜)
 */
public record MatchInfo(MatchType type, String roomCode, String catNickname, String proctorNickname,
                        Instant startedAt, String rematchOfRoomId) {

    /** 테스트·도구용 */
    public static MatchInfo anonymous() {
        return new MatchInfo(MatchType.RANDOM, null, "cat", "proctor", Instant.now(), null);
    }
}

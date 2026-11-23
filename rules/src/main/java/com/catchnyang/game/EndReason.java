package com.catchnyang.game;

/** DB game_match.end_reason 과 같은 값 */
public enum EndReason {
    AVG_REACHED,  // 시간 종료, 평균 >= 목표 → 냥이 승
    AVG_FAILED,   // 시간 종료, 평균 < 목표 → 감독관 승
    CATCH_3,      // 적발 3회 성공 → 감독관 즉시 승
    DISCONNECT    // 한쪽 이탈
}

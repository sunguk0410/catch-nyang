package com.catchnyang.game;

/** 플레이어 행동 처리 결과. OK 가 아니면 상태는 바뀌지 않는다. */
public enum ActionResult {
    OK,
    WRONG_PHASE,   // 지금 단계에서 못 하는 행동
    STUNNED,       // 기절·경직 중
    BUSY,          // 도구 사용·마킹 중이라 다른 행동 불가
    COOLDOWN,      // 빙의 쿨타임
    INVALID,       // 잘못된 인자 (자리·문항 번호, 도구 등)
    NOT_KNOWN,     // AI 로 확인 안 한 문항
    NO_CHANGE,     // 이미 정답이라 고칠 게 없음
    NO_USES_LEFT,  // 남은 횟수 없음
    NO_TOOL,       // 빙의한 학생이 도구가 없음 (파괴됨)
    OUT_OF_RANGE   // 학생 바로 앞이 아님
}

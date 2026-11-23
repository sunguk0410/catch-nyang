package com.catchnyang.game;

import java.util.Map;

/**
 * 규칙 엔진이 tick 동안 만들어 내는 사건. 네트워크 계층이 이걸 역할별로 걸러서 보낸다.
 * (예: byCat 같은 필드는 감독관에게 절대 보내면 안 됨)
 */
public sealed interface GameEvent {

    record PhaseChanged(Phase phase) implements GameEvent {}

    /** 빙의 순간 움찔 (감독관에게 보이는 단서) */
    record Possessed(int seat) implements GameEvent {}

    record ToolStarted(ToolType tool, int seat) implements GameEvent {}

    /** 고정 시간이 끝나 정답을 알아냄. revealed: 문항 → 정답(O=true) */
    record ToolFinished(ToolType tool, int seat, Map<Integer, Boolean> revealed) implements GameEvent {}

    /** 전파 탐지기에 걸려 파괴 */
    record ToolDestroyed(ToolType tool, int seat) implements GameEvent {}

    /** 지우고 다시 마킹하는 손동작 시작. byCat=false 면 학생 실수 수정(가짜 단서) */
    record RemarkStarted(int seat, int question, boolean byCat) implements GameEvent {}

    /** OMR 칸 값이 바뀜. value=null 이면 빈 칸 */
    record OmrMarked(int seat, int question, Boolean value) implements GameEvent {}

    /** 감독관이 학생 앞에 2초 서 있어서 시험지·OMR 을 볼 수 있게 됨 */
    record InspectReady(int seat) implements GameEvent {}

    record CatchResult(int seat, boolean success) implements GameEvent {}

    /** 적발 후 기절이 끝나 랜덤 자리로 튕김 */
    record CatRelocated(int seat) implements GameEvent {}

    /** hit 이면 seat 자리가 revealSec 동안 감독관 미니맵에 표시 */
    record DetectorResult(boolean hit, int seat) implements GameEvent {}

    record TargetChanged(int target) implements GameEvent {}

    /** 감독관용 반 평균 중간 집계 (averageRevealIntervalSec 마다). 냥이는 실시간 평균을 봄 */
    record AverageRevealed(double average) implements GameEvent {}

    record MatchEnded(Role winner, EndReason reason, double finalAverage, int finalTarget) implements GameEvent {}
}

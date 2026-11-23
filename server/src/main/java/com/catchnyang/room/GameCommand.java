package com.catchnyang.room;

import java.util.List;

/** 플레이어 입력. 네트워크 스레드에서 만들어 방 입력 큐에 넣고, 방 스레드에서 적용된다. */
public sealed interface GameCommand {

    // ----- 냥이 -----
    record Possess(int seat) implements GameCommand {}

    record UseTool(List<Integer> questions) implements GameCommand {}

    record Remark(int question) implements GameCommand {}

    // ----- 감독관 -----
    record Move(double dx, double dz) implements GameCommand {}

    /** 바라보는 방향 (시야각 판정용) */
    record Look(double fx, double fz) implements GameCommand {}

    record Swat() implements GameCommand {}

    record Detect() implements GameCommand {}

    // ----- 공통 -----
    /** 나가기 (끊김 포함). 남은 쪽 승리 */
    record Leave() implements GameCommand {}
}

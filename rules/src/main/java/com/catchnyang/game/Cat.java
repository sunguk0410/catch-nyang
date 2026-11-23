package com.catchnyang.game;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** 냥이 상태. 도구는 냥이가 아니라 학생이 들고 있음 ({@link Student#tool()}) */
public final class Cat {

    record ActiveTool(ToolType type, int seat, List<Integer> questions, long until) {}

    record Remark(int seat, int question, long until) {}

    /** 빙의 중인 자리. -1 = 아직 시작 전이거나 적발돼 기절 중 */
    int seat = -1;
    long possessReadyAt;

    boolean stunned;
    long stunnedUntil;

    /** AI 로 확인한 문항 (OX 라 모든 학생 공통) */
    final Set<Integer> known = new TreeSet<>();

    ActiveTool activeTool;
    Remark remark;

    boolean busy() {
        return activeTool != null || remark != null;
    }

    public int seat() { return seat; }
    public boolean isStunned() { return stunned; }
    /** 적발 기절이 끝나는 시각 (게임 시간 ms) */
    public long stunnedUntil() { return stunnedUntil; }
    /** 다음 빙의 이동이 가능한 시각 (게임 시간 ms) */
    public long possessReadyAt() { return possessReadyAt; }
    /** 도구 사용·마킹 중이라 다른 행동 불가 */
    public boolean isBusy() { return busy(); }
    public Set<Integer> knownQuestions() { return Collections.unmodifiableSet(known); }
    public ToolType activeToolType() { return activeTool == null ? null : activeTool.type(); }
}

package com.catchnyang.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 학생 한 명. 시험지(연필 답)는 고정, OMR 은 학생 본인·냥이가 바꾼다. */
public final class Student {

    final int seat;
    final Vec2 position;
    final boolean[] paper;   // 시험지에 연필로 적힌 답 (O=true)
    final Boolean[] omr;     // null = 아직 안 옮겨 적음

    /** 지참한 도구. null = 없음(탐지기에 파괴됨). 냥이는 빙의한 학생의 도구만 쓸 수 있음 */
    ToolType tool;
    int toolChecksLeft;

    long nextTranscribeAt;

    // 학생 실수: 잘못 마킹 → fixAt 에 손동작 시작 → fixApplyAt 에 원래 답으로
    int pendingFixQuestion = -1;
    long fixAt;
    long fixApplyAt = -1;  // -1 = 아직 손동작 시작 전

    /** 냥이가 고친 문항 → 고치기 전 OMR 값. 적발되면 이걸로 되돌림 */
    final Map<Integer, Boolean> catEditsOriginal = new LinkedHashMap<>();

    Student(int seat, Vec2 position, boolean[] paper) {
        this.seat = seat;
        this.position = position;
        this.paper = paper;
        this.omr = new Boolean[paper.length];
    }

    /** 점수 계산에 쓰는 답: OMR 에 마킹돼 있으면 그 값, 아니면 시험지 답 (어차피 그대로 옮겨 적을 것) */
    public boolean effectiveAnswer(int q) {
        return omr[q] != null ? omr[q] : paper[q];
    }

    public int score(boolean[] answerKey, int pointsPerQuestion) {
        int correct = 0;
        for (int q = 0; q < answerKey.length; q++) {
            if (effectiveAnswer(q) == answerKey[q]) correct++;
        }
        return correct * pointsPerQuestion;
    }

    int firstUnmarked() {
        for (int q = 0; q < omr.length; q++) {
            if (omr[q] == null) return q;
        }
        return -1;
    }

    void catMark(int q, boolean value) {
        catEditsOriginal.putIfAbsent(q, omr[q]);
        omr[q] = value;
        if (pendingFixQuestion == q) cancelFix(); // 냥이가 덮어쓴 칸은 학생이 다시 안 고침
    }

    /** 냥이가 고친 칸을 원래대로. 되돌린 문항 번호 반환 */
    List<Integer> revertCatEdits() {
        List<Integer> reverted = new ArrayList<>(catEditsOriginal.keySet());
        catEditsOriginal.forEach((q, original) -> omr[q] = original);
        catEditsOriginal.clear();
        return reverted;
    }

    void cancelFix() {
        pendingFixQuestion = -1;
        fixApplyAt = -1;
    }

    void destroyTool() {
        tool = null;
        toolChecksLeft = 0;
    }

    public ToolType tool() { return tool; }
    public int toolChecksLeft() { return toolChecksLeft; }
    public int seat() { return seat; }
    public Vec2 position() { return position; }
    public boolean paperAnswer(int q) { return paper[q]; }
    public Boolean omrAnswer(int q) { return omr[q]; }
}

package com.catchnyang.room;

import com.catchnyang.game.Student;
import com.catchnyang.game.ToolType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 학생 한 명의 시험지·OMR 복사본 (방 스레드 밖으로 넘겨도 안전).
 *
 * @param omr  null = 빈 칸
 * @param tool 감독관에게 보낼 땐 null (도구·남은 횟수는 냥이만 앎)
 */
public record StudentInfo(int seat, List<Boolean> paper, List<Boolean> omr, ToolType tool, int toolChecksLeft) {

    public static StudentInfo of(Student s, int questionCount, boolean withTool) {
        List<Boolean> paper = new ArrayList<>(questionCount);
        List<Boolean> omr = new ArrayList<>(questionCount);
        for (int q = 0; q < questionCount; q++) {
            paper.add(s.paperAnswer(q));
            omr.add(s.omrAnswer(q));
        }
        return new StudentInfo(s.seat(), Collections.unmodifiableList(paper), Collections.unmodifiableList(omr),
                withTool ? s.tool() : null, withTool ? s.toolChecksLeft() : 0);
    }
}

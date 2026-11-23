package com.catchnyang.room;

import com.catchnyang.game.Match;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.Student;
import com.catchnyang.game.ToolType;
import com.catchnyang.game.Vec2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 한 역할에게 보이는 전체 상태. {@link EventFilter} 와 같은 원칙: 상대 비밀은 절대 안 담는다.
 * cat / proctor 중 내 역할 것만 채워지고 나머지는 null.
 */
public record RoleSnapshot(
        Role role,
        Phase phase,
        long matchTimeMs,
        long remainingMs,
        int target,
        int catchSuccesses,
        Vec2 proctorPosition,
        Vec2 proctorFacing,
        List<Vec2> seats,
        int questionCount,
        String ruleVersion,
        CatView cat,
        ProctorView proctor
) {
    /**
     * @param seat    빙의 중인 자리, -1 = 시작 전·기절 중
     * @param known   AI 로 확인한 문항 → 정답
     * @param current 빙의 중인 학생 (없으면 null)
     * @param tools   자리별 지참 도구 (null = 없음/파괴)
     */
    public record CatView(int seat, boolean stunned, long stunnedMs, long possessCooldownMs,
                          Map<Integer, Boolean> known, double liveAverage, boolean busy,
                          StudentInfo current, List<ToolType> tools) {}

    /**
     * @param visibleAverage 마지막으로 공개된 반 평균 (실시간 아님)
     * @param inspecting     검사 중인 학생 (없으면 null)
     */
    public record ProctorView(double visibleAverage, int swatsLeft, int detectorsLeft, long stunnedMs,
                              int inspectingSeat, StudentInfo inspecting) {}

    /** 방 스레드에서만 호출 */
    public static RoleSnapshot of(Role role, Match match) {
        int qc = match.questionCount();
        long now = match.now();
        List<Vec2> seats = new ArrayList<>();
        for (Student s : match.students()) seats.add(s.position());

        CatView catView = null;
        ProctorView proctorView = null;
        if (role == Role.CAT) {
            var c = match.cat();
            Map<Integer, Boolean> known = new LinkedHashMap<>();
            for (int q : c.knownQuestions()) known.put(q, match.answer(q));
            List<ToolType> tools = new ArrayList<>();
            for (Student s : match.students()) tools.add(s.tool());
            StudentInfo current = c.seat() >= 0 ? StudentInfo.of(match.students().get(c.seat()), qc, true) : null;
            catView = new CatView(c.seat(), c.isStunned(), c.isStunned() ? Math.max(0, c.stunnedUntil() - now) : 0,
                    Math.max(0, c.possessReadyAt() - now), Collections.unmodifiableMap(known),
                    match.classAverage(), c.isBusy(), current, Collections.unmodifiableList(tools));
        } else {
            var p = match.proctor();
            int inspecting = p.inspectingSeat();
            proctorView = new ProctorView(match.proctorVisibleAverage(), p.swatsLeft(), p.detectorsLeft(),
                    Math.max(0, p.stunnedUntil() - now), inspecting,
                    inspecting >= 0 ? StudentInfo.of(match.students().get(inspecting), qc, false) : null);
        }

        return new RoleSnapshot(role, match.phase(), now, match.remainingMs(), match.target(),
                match.proctor().catchSuccesses(), match.proctor().position(), match.proctor().facing(),
                List.copyOf(seats), qc, match.ruleVersion(), catView, proctorView);
    }
}

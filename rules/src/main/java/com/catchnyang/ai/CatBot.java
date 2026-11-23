package com.catchnyang.ai;

import com.catchnyang.game.SimpleRng;
import com.catchnyang.game.ToolType;
import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.EventFilter;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.StudentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 냥이. 사람 냥이가 볼 수 있는 것(빙의한 학생 시험지·OMR, 아는 정답, 감독관 위치, 자리별 도구)만으로 판단한다.
 * <ul>
 *   <li>감독관이 가까우면 가만히 (수상한 짓 안 함). 바로 옆까지 오면 먼 학생으로 도망</li>
 *   <li>아는 정답으로 고칠 게 있으면 고침 — 한 학생에 몰지 않고 몇 개씩만 (몰아 고치면 검사에 걸리기 쉬움)</li>
 *   <li>감독관이 멀고 안 보고 있을 때 도구로 모르는 문항 확인</li>
 *   <li>할 게 없으면 도구가 남아 있거나 아직 덜 고친, 감독관에게서 먼 학생으로 옮김</li>
 * </ul>
 */
public final class CatBot implements Bot {

    /** 한 학생에게 고치는 최대 개수 (넘으면 다른 학생으로) */
    static final int MAX_EDITS_PER_STUDENT = 4;

    private final SimpleRng rng;
    private int[] edits = new int[0];
    private long nextThinkAt;
    /** 마지막으로 "본" 감독관 위치. 사람 냥이처럼 앞·옆만 보이고 등 뒤는 못 봄 */
    private Vec2 seenProctor;

    public CatBot(SimpleRng rng) {
        this.rng = rng;
    }

    @Override
    public List<GameCommand> decide(BotView v) {
        List<GameCommand> out = new ArrayList<>();
        if (!v.playing() || v.stunned || v.mySeat < 0 || v.catBusy() || v.seats.length == 0) return out;
        if (v.now < nextThinkAt) return out;
        nextThinkAt = v.now + 450 + rng.nextInt(650);
        if (edits.length != v.seats.length) edits = new int[v.seats.length];

        GameConfig cfg = v.cfg;
        int seat = v.mySeat;
        Vec2 me = v.seats[seat];
        // 앞(칠판 쪽)이나 바로 옆이면 보임 (살피기로 좌우 90°까지 돌릴 수 있음). 등 뒤면 마지막으로 본 위치로 판단
        boolean visible = v.proctorPos.z() >= me.z() - 0.3 || me.dist(v.proctorPos) < 1.5;
        if (visible || seenProctor == null) seenProctor = v.proctorPos;
        Vec2 proctor = seenProctor;
        double proctorDist = me.dist(proctor);
        boolean watched = visible && EventFilter.inFov(v.proctorPos, v.proctorFacing, me, cfg.proctor().fovDeg());
        boolean possessReady = v.now >= v.possessReadyAt;

        // 1) 위험: 감독관이 가까움 → 옮길 수 있으면 멀고 안 보이는 학생으로, 아니면 얌전히
        if (proctorDist < 2.2 || (proctorDist < 3.5 && watched)) {
            if (possessReady) {
                int to = pickSeat(v, seat);
                if (to >= 0) out.add(new GameCommand.Possess(to));
            }
            return out;
        }

        StudentInfo st = v.current;
        if (st == null || st.seat() != seat) return out;

        // 2) 아는 정답으로 고치기 (한 학생에 몇 개만 — 평균이 모자라거나 시간이 없으면 더)
        int cap = MAX_EDITS_PER_STUDENT;
        if (v.liveAverage < v.target + 4) cap += 3;
        if (v.remainingMs < 60_000) cap = Integer.MAX_VALUE;
        if (edits[seat] < cap) {
            List<Integer> fixable = new ArrayList<>();
            for (int q = 0; q < v.questionCount; q++) {
                Boolean k = v.known[q];
                if (k != null && BotView.effective(st, q) != k) fixable.add(q);
            }
            if (!fixable.isEmpty()) {
                out.add(new GameCommand.Remark(fixable.get(rng.nextInt(fixable.size()))));
                edits[seat]++;
                return out;
            }
        }

        // 3) 도구로 모르는 문항 확인 (들킬 만하면 안 씀)
        ToolType tool = st.tool();
        var spec = tool == null ? null : cfg.tools().get(tool);
        if (spec != null && st.toolChecksLeft() > 0 && toolSafe(tool, spec.visibleRangeM(), proctorDist, watched)) {
            List<Integer> unknown = new ArrayList<>();
            for (int q = 0; q < v.questionCount; q++) if (v.known[q] == null) unknown.add(q);
            int n = Math.min(Math.min(spec.perUse(), st.toolChecksLeft()), unknown.size());
            if (n > 0) {
                List<Integer> pick = new ArrayList<>();
                for (int i = 0; i < n; i++) pick.add(unknown.remove(rng.nextInt(unknown.size())));
                v.noteToolRequest(n);
                out.add(new GameCommand.UseTool(pick));
                return out;
            }
        }

        // 4) 여기서 할 게 없으면 다른 학생으로
        if (possessReady) {
            int to = pickSeat(v, seat);
            if (to >= 0) out.add(new GameCommand.Possess(to));
        }
        return out;
    }

    private static boolean toolSafe(ToolType tool, Double visibleRange, double proctorDist, boolean watched) {
        if (visibleRange == null) return !watched && proctorDist > 3.0;      // AI 안경: 시야 안이면 거리 무관하게 보임
        return !(watched && proctorDist <= visibleRange + 0.5) && proctorDist > 2.5;
    }

    /** 감독관에게서 멀고, 도구가 남았거나 아직 덜 고친 학생 */
    private int pickSeat(BotView v, int except) {
        Vec2 proctor = seenProctor != null ? seenProctor : v.proctorPos;
        int best = -1;
        double bestScore = -1e9;
        for (int s = 0; s < v.seats.length; s++) {
            if (s == except) continue;
            double score = v.seats[s].dist(proctor) * 0.6 + rng.nextDouble();
            // 감독관 시야 밖으로 옮기면 움찔도 안 보임 (냥이가 본 시선 기준)
            if (!EventFilter.inFov(proctor, v.proctorFacing, v.seats[s], v.cfg.proctor().fovDeg())) score += 2;
            ToolType t = s < v.tools.length ? v.tools[s] : null;
            if (t != null && v.checksUsed[s] < v.cfg.tools().get(t).totalChecks()) score += 2.5;
            if (edits[s] < MAX_EDITS_PER_STUDENT) score += 1.5;
            if (score > bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }
}

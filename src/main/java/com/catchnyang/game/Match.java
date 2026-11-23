package com.catchnyang.game;

import com.catchnyang.game.GameEvent.*;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfig.ToolSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;

/**
 * 한 판의 규칙 엔진.
 * <ul>
 *   <li>방 게임 스레드 하나에서만 호출한다 (thread-safe 아님, 락 없음).</li>
 *   <li>시간은 {@link #tick(long)} 으로만 흐른다 → 테스트에서 시간을 마음대로 돌릴 수 있음.</li>
 *   <li>행동 메서드는 검증 실패 시 상태를 안 바꾸고 이유를 {@link ActionResult} 로 돌려준다.</li>
 * </ul>
 */
public final class Match {

    private final GameConfig cfg;
    private final RandomGenerator rnd;

    private final boolean[] answerKey;  // 정답 (O=true)
    private final List<Student> students;
    private final Cat cat = new Cat();
    private final Proctor proctor;
    private final List<GameEvent> events = new ArrayList<>();

    private Phase phase = Phase.PREPARE;
    private long now;
    private long phaseEndsAt;
    private int target;
    private double proctorAverage;      // 감독관에게 보이는 (마지막 집계) 평균
    private long nextAverageRevealAt;
    private Role winner;
    private EndReason endReason;

    public Match(GameConfig cfg, RandomGenerator rnd) {
        this.cfg = cfg;
        this.rnd = rnd;
        var m = cfg.match();

        answerKey = new boolean[m.questionCount()];
        for (int q = 0; q < answerKey.length; q++) answerKey[q] = rnd.nextBoolean();

        List<ToolType> tools = enabledTools();
        students = new ArrayList<>(m.studentCount());
        for (int seat = 0; seat < m.studentCount(); seat++) {
            double accuracy = range(m.initialAccuracyMin(), m.initialAccuracyMax());
            boolean[] paper = new boolean[answerKey.length];
            for (int q = 0; q < paper.length; q++) {
                paper[q] = rnd.nextDouble() < accuracy ? answerKey[q] : !answerKey[q];
            }
            Student s = new Student(seat, seatPosition(seat), paper);
            s.tool = tools.get(rnd.nextInt(tools.size()));  // 학생마다 랜덤 도구 1개 지참
            s.toolChecksLeft = cfg.tools().get(s.tool).totalChecks();
            students.add(s);
        }

        var room = cfg.classroom();
        var p = cfg.proctor();
        proctor = new Proctor(new Vec2(room.proctorSpawnX(), room.proctorSpawnZ()),
                p.swatter().maxAttempts(), p.detector().maxUses());

        target = m.initialTarget();
        phaseEndsAt = ms(m.prepareSec());
    }

    // =====================================================================
    // tick
    // =====================================================================

    public void tick(long dtMs) {
        if (phase == Phase.ENDED) return;
        now += dtMs;

        if (phase == Phase.PREPARE) {
            if (now >= phaseEndsAt) startPlaying();
            return;
        }

        updateProctorMovement(dtMs);
        updateCat();
        updateStudents();
        updateInspect();

        if (now >= nextAverageRevealAt) revealAverage();

        if (phase == Phase.PLAYING && now >= phaseEndsAt) {
            boolean reached = classAverage() >= target;
            end(reached ? Role.CAT : Role.PROCTOR, reached ? EndReason.AVG_REACHED : EndReason.AVG_FAILED);
        }
    }

    private void startPlaying() {
        phase = Phase.PLAYING;
        phaseEndsAt = now + ms(cfg.match().durationSec());
        cat.seat = rnd.nextInt(students.size());
        for (Student s : students) s.nextTranscribeAt = now + transcribeInterval();

        events.add(new PhaseChanged(Phase.PLAYING));
        events.add(new CatRelocated(cat.seat));  // 첫 빙의 자리 (냥이에게만 감)
        revealAverage();
    }

    private void revealAverage() {
        proctorAverage = classAverage();
        nextAverageRevealAt = now + ms(cfg.proctor().averageRevealIntervalSec());
        events.add(new AverageRevealed(proctorAverage));
    }

    private void updateProctorMovement(long dtMs) {
        if (proctorStunned()) return;
        double step = cfg.proctor().moveSpeedMps() * dtMs / 1000.0;
        var room = cfg.classroom();
        double x = clamp(proctor.position.x() + proctor.moveX * step, room.halfWidthM());
        double z = clamp(proctor.position.z() + proctor.moveZ * step, room.halfDepthM());
        proctor.position = new Vec2(x, z);
    }

    private void updateCat() {
        // 적발 기절 끝 → 랜덤 자리로 튕김
        if (cat.stunned && now >= cat.stunnedUntil) {
            cat.stunned = false;
            cat.seat = rnd.nextInt(students.size());
            events.add(new CatRelocated(cat.seat));
        }

        var tool = cat.activeTool;
        if (tool != null && now >= tool.until()) {
            Map<Integer, Boolean> revealed = new LinkedHashMap<>();
            for (int q : tool.questions()) {
                cat.known.add(q);
                revealed.put(q, answerKey[q]);
            }
            cat.activeTool = null;
            events.add(new ToolFinished(tool.type(), tool.seat(), revealed));
        }

        var remark = cat.remark;
        if (remark != null && now >= remark.until()) {
            Student s = students.get(remark.seat());
            s.catMark(remark.question(), answerKey[remark.question()]);
            cat.remark = null;
            events.add(new OmrMarked(s.seat, remark.question(), s.omr[remark.question()]));
        }
    }

    private void updateStudents() {
        var m = cfg.match();
        for (Student s : students) {
            if (s.seat == cat.seat) continue;  // 빙의 중인 학생은 손을 멈춤

            // 실수 고치기: 손동작 시작 → remarkSec 뒤 원래 답으로
            if (s.pendingFixQuestion >= 0) {
                int q = s.pendingFixQuestion;
                if (s.fixApplyAt < 0 && now >= s.fixAt) {
                    s.fixApplyAt = now + ms(cfg.cat().remarkSec());
                    events.add(new RemarkStarted(s.seat, q, false));
                } else if (s.fixApplyAt >= 0 && now >= s.fixApplyAt) {
                    s.omr[q] = s.paper[q];
                    s.cancelFix();
                    events.add(new OmrMarked(s.seat, q, s.omr[q]));
                }
            }

            // 시험지 → OMR 한 문항씩 옮겨 적기
            if (now >= s.nextTranscribeAt) {
                int q = s.firstUnmarked();
                if (q >= 0) {
                    boolean mistake = s.pendingFixQuestion < 0 && rnd.nextDouble() < m.studentMistakeRate();
                    s.omr[q] = mistake ? !s.paper[q] : s.paper[q];
                    if (mistake) {
                        s.pendingFixQuestion = q;
                        s.fixAt = now + ms(range(m.mistakeFixDelaySecMin(), m.mistakeFixDelaySecMax()));
                    }
                    events.add(new OmrMarked(s.seat, q, s.omr[q]));
                }
                s.nextTranscribeAt = now + transcribeInterval();
            }
        }
    }

    private void updateInspect() {
        int seat = proctorStunned() ? -1 : seatInReach();
        if (seat != proctor.nearSeat) {
            proctor.nearSeat = seat;
            proctor.nearSince = now;
            proctor.inspecting = false;
        } else if (seat >= 0 && !proctor.inspecting && now - proctor.nearSince >= ms(cfg.proctor().inspectHoldSec())) {
            proctor.inspecting = true;
            events.add(new InspectReady(seat));
        }
    }

    // =====================================================================
    // 냥이 행동
    // =====================================================================

    /** 다른 학생으로 빙의 이동 */
    public ActionResult catPossess(int seat) {
        ActionResult block = catBlocked();
        if (block != null) return block;
        if (!validSeat(seat) || seat == cat.seat) return ActionResult.INVALID;
        if (now < cat.possessReadyAt) return ActionResult.COOLDOWN;

        cat.seat = seat;
        cat.possessReadyAt = now + ms(cfg.cat().possessCooldownSec());
        events.add(new Possessed(seat));
        return ActionResult.OK;
    }

    /** 빙의한 학생의 도구로 문항 정답 확인. 고정 시간 동안 다른 행동 불가, 끝나야 정답을 앎 */
    public ActionResult catUseTool(List<Integer> questions) {
        ActionResult block = catBlocked();
        if (block != null) return block;
        Student s = students.get(cat.seat);
        if (s.tool == null) return ActionResult.NO_TOOL;
        int left = s.toolChecksLeft;
        if (left <= 0) return ActionResult.NO_USES_LEFT;

        ToolType type = s.tool;
        ToolSpec spec = cfg.tools().get(type);
        int max = Math.min(spec.perUse(), left);
        if (questions.isEmpty() || questions.size() > max || new HashSet<>(questions).size() != questions.size()) {
            return ActionResult.INVALID;
        }
        for (int q : questions) {
            if (!validQuestion(q) || cat.known.contains(q)) return ActionResult.INVALID;
        }

        s.toolChecksLeft = left - questions.size();
        cat.activeTool = new Cat.ActiveTool(type, cat.seat, List.copyOf(questions), now + ms(spec.lockSec()));
        events.add(new ToolStarted(type, cat.seat));
        return ActionResult.OK;
    }

    /** 빙의한 학생의 OMR 한 칸을 정답으로 지우고 다시 마킹 (remarkSec 걸림) */
    public ActionResult catRemark(int question) {
        ActionResult block = catBlocked();
        if (block != null) return block;
        if (!validQuestion(question)) return ActionResult.INVALID;
        if (!cat.known.contains(question)) return ActionResult.NOT_KNOWN;
        if (students.get(cat.seat).effectiveAnswer(question) == answerKey[question]) return ActionResult.NO_CHANGE;

        cat.remark = new Cat.Remark(cat.seat, question, now + ms(cfg.cat().remarkSec()));
        events.add(new RemarkStarted(cat.seat, question, true));
        return ActionResult.OK;
    }

    private ActionResult catBlocked() {
        if (phase != Phase.PLAYING) return ActionResult.WRONG_PHASE;
        if (cat.stunned) return ActionResult.STUNNED;
        if (cat.busy()) return ActionResult.BUSY;
        return null;
    }

    // =====================================================================
    // 감독관 행동
    // =====================================================================

    /** 이동 방향 입력 (dx, dz). 길이 1 초과면 정규화. (0,0) 이면 멈춤 */
    public ActionResult proctorMove(double dx, double dz) {
        if (phase != Phase.PLAYING) return ActionResult.WRONG_PHASE;
        if (!Double.isFinite(dx) || !Double.isFinite(dz)) return ActionResult.INVALID;
        double len = Math.hypot(dx, dz);
        if (len > 1) {
            dx /= len;
            dz /= len;
        }
        proctor.moveX = dx;
        proctor.moveZ = dz;
        return ActionResult.OK;
    }

    /** 바라보는 방향 (fx, fz). 정규화해서 저장. 도구 이펙트 시야 판정에 쓰임 */
    public ActionResult proctorLook(double fx, double fz) {
        if (phase == Phase.ENDED) return ActionResult.WRONG_PHASE;
        if (!Double.isFinite(fx) || !Double.isFinite(fz)) return ActionResult.INVALID;
        double len = Math.hypot(fx, fz);
        if (len < 1e-6) return ActionResult.INVALID;
        proctor.facing = new Vec2(fx / len, fz / len);
        return ActionResult.OK;
    }

    /** 파리채: 바로 앞 학생 적발 시도 */
    public ActionResult proctorSwat() {
        if (phase != Phase.PLAYING) return ActionResult.WRONG_PHASE;
        if (proctorStunned()) return ActionResult.STUNNED;
        if (proctor.swatsLeft <= 0) return ActionResult.NO_USES_LEFT;
        int seat = seatInReach();
        if (seat < 0) return ActionResult.OUT_OF_RANGE;

        var swatter = cfg.proctor().swatter();
        proctor.swatsLeft--;

        if (seat == cat.seat) {
            proctor.catchSuccesses++;
            changeTarget(swatter.successTargetDelta());
            Student s = students.get(seat);
            for (int q : s.revertCatEdits()) events.add(new OmrMarked(seat, q, s.omr[q]));
            // 쓰던 도구·마킹은 중단 (이미 쓴 확인 횟수는 돌려주지 않음)
            cat.activeTool = null;
            cat.remark = null;
            cat.seat = -1;
            cat.stunned = true;
            cat.stunnedUntil = now + ms(cfg.cat().stunSec());
            events.add(new CatchResult(seat, true));
            if (proctor.catchSuccesses >= swatter.winOnSuccessCount()) end(Role.PROCTOR, EndReason.CATCH_3);
        } else {
            changeTarget(swatter.failTargetDelta());
            proctor.stunnedUntil = now + ms(swatter.failStunSec());
            events.add(new CatchResult(seat, false));
        }
        return ActionResult.OK;
    }

    /** 전파 탐지기: 지금 도구를 쓰는 중이면 파괴 + 위치 노출, 아니면 목표 점수 감소 */
    public ActionResult proctorDetect() {
        if (phase != Phase.PLAYING) return ActionResult.WRONG_PHASE;
        if (proctorStunned()) return ActionResult.STUNNED;
        if (proctor.detectorsLeft <= 0) return ActionResult.NO_USES_LEFT;
        proctor.detectorsLeft--;

        var tool = cat.activeTool;
        if (tool != null) {
            students.get(tool.seat()).destroyTool();  // 그 학생 도구는 사라짐
            cat.activeTool = null;
            events.add(new ToolDestroyed(tool.type(), tool.seat()));
            events.add(new DetectorResult(true, tool.seat()));
        } else {
            changeTarget(cfg.proctor().detector().missTargetDelta());
            events.add(new DetectorResult(false, -1));
        }
        return ActionResult.OK;
    }

    // =====================================================================
    // 공통
    // =====================================================================

    /** 한쪽이 나가면 남은 쪽 승리 */
    public void forfeit(Role leaver) {
        if (phase == Phase.ENDED) return;
        end(leaver == Role.CAT ? Role.PROCTOR : Role.CAT, EndReason.DISCONNECT);
    }

    /** 이번 tick 까지 쌓인 사건을 꺼내고 비움 */
    public List<GameEvent> drainEvents() {
        List<GameEvent> out = List.copyOf(events);
        events.clear();
        return out;
    }

    public double classAverage() {
        int points = cfg.match().pointsPerQuestion();
        double sum = 0;
        for (Student s : students) sum += s.score(answerKey, points);
        return sum / students.size();
    }

    public Vec2 seatPosition(int seat) {
        int cols = cfg.match().gridSize();
        double offset = (cols - 1) / 2.0;
        double spacing = cfg.classroom().seatSpacingM();
        return new Vec2((seat % cols - offset) * spacing, (seat / cols - offset) * spacing);
    }

    private void end(Role winner, EndReason reason) {
        this.phase = Phase.ENDED;
        this.winner = winner;
        this.endReason = reason;
        events.add(new MatchEnded(winner, reason, classAverage(), target));
    }

    private void changeTarget(int delta) {
        target += delta;
        events.add(new TargetChanged(target));
    }

    /** 감독관 바로 앞(interactRangeM 이내)에서 가장 가까운 학생 자리, 없으면 -1 */
    private int seatInReach() {
        int best = -1;
        double bestDist = cfg.classroom().interactRangeM();
        for (Student s : students) {
            double d = s.position.dist(proctor.position);
            if (d <= bestDist) {
                best = s.seat;
                bestDist = d;
            }
        }
        return best;
    }

    private boolean proctorStunned() {
        return now < proctor.stunnedUntil;
    }

    private List<ToolType> enabledTools() {
        List<ToolType> out = new ArrayList<>();
        cfg.tools().forEach((t, spec) -> {
            if (spec.isEnabled()) out.add(t);
        });
        return out;
    }

    private long transcribeInterval() {
        var m = cfg.match();
        return ms(range(m.transcribeIntervalSecMin(), m.transcribeIntervalSecMax()));
    }

    private double range(double min, double max) {
        return min >= max ? min : rnd.nextDouble(min, max);
    }

    private boolean validSeat(int seat) {
        return seat >= 0 && seat < students.size();
    }

    private boolean validQuestion(int q) {
        return q >= 0 && q < answerKey.length;
    }

    private static long ms(double sec) {
        return Math.round(sec * 1000);
    }

    private static double clamp(double v, double half) {
        return Math.max(-half, Math.min(half, v));
    }

    // ---------- 조회 ----------

    public Phase phase() { return phase; }
    public long now() { return now; }
    public long remainingMs() { return Math.max(0, phaseEndsAt - now); }
    public int target() { return target; }
    public int questionCount() { return answerKey.length; }
    /** 감독관 화면용 평균 (마지막 집계값). 실시간 값은 {@link #classAverage()} — 냥이 화면·승패 판정용 */
    public double proctorVisibleAverage() { return proctorAverage; }
    public Role winner() { return winner; }
    public EndReason endReason() { return endReason; }
    public boolean answer(int q) { return answerKey[q]; }
    public List<Student> students() { return Collections.unmodifiableList(students); }
    public Cat cat() { return cat; }
    public Proctor proctor() { return proctor; }
    public String ruleVersion() { return cfg.version(); }

    // ---------- 테스트 전용 ----------

    void teleportProctor(Vec2 p) {
        proctor.position = p;
    }

    void setTarget(int target) {
        this.target = target;
    }
}

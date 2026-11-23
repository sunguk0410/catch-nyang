package com.catchnyang.ai;

import com.catchnyang.game.GameEvent;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.ToolType;
import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.RoleSnapshot;
import com.catchnyang.room.StudentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 봇이 아는 것 = 그 역할 플레이어 화면에 보이는 것뿐. 방(RoomCore)이 그 역할에게 보낸 묶음만으로 갱신한다.
 * (사람 클라의 GameStore 와 같은 역할 — 봇이 정답·냥이 위치를 몰래 보는 일이 없게)
 * <p>
 * 브라우저 변환을 위해 HashMap 순회에 기대지 않고 배열을 쓴다.
 */
public final class BotView {

    final GameConfig cfg;
    final Role role;

    Phase phase = Phase.PREPARE;
    long now;
    long remainingMs;
    int target;
    Vec2 proctorPos;
    Vec2 proctorFacing = new Vec2(0, -1);
    Vec2[] seats = new Vec2[0];
    int questionCount;

    // ---- 냥이 ----
    int mySeat = -1;
    boolean stunned;
    long possessReadyAt;
    long busyUntil;
    /** 확인한 문항의 정답, null = 모름 */
    Boolean[] known = new Boolean[0];
    double liveAverage;
    StudentInfo current;
    ToolType[] tools = new ToolType[0];
    /** 내가 쓴 확인 횟수 (자리별) — 다른 학생의 남은 횟수는 화면에 안 보이니 직접 셈 */
    int[] checksUsed = new int[0];
    private int pendingToolQuestions;

    // ---- 감독관 ----
    int swatsLeft;
    int detectorsLeft;
    long stunnedUntil;
    int inspectingSeat = -1;
    StudentInfo inspected;

    /** 이번 묶음에서 새로 본 사건 (봇이 반응용으로 씀) */
    final List<GameEvent> fresh = new ArrayList<>();

    BotView(GameConfig cfg, Role role) {
        this.cfg = cfg;
        this.role = role;
        this.proctorPos = new Vec2(cfg.classroom().proctorSpawnX(), cfg.classroom().proctorSpawnZ());
        this.target = cfg.match().initialTarget();
    }

    void apply(List<Outbound> batch) {
        fresh.clear();
        for (Outbound o : batch) {
            switch (o) {
                case Outbound.Clock c -> {
                    phase = c.phase();
                    now = c.matchTimeMs();
                    remainingMs = c.remainingMs();
                }
                case Outbound.Snapshot s -> snapshot(s.snapshot());
                case Outbound.Event e -> {
                    event(e.event());
                    fresh.add(e.event());
                }
                case Outbound.ProctorState p -> {
                    proctorPos = p.position();
                    proctorFacing = p.facing();
                }
                case Outbound.LiveAverage a -> liveAverage = a.average();
                case Outbound.StudentDetail d -> {
                    if (role == Role.CAT) current = d.info();
                    else inspected = d.info();
                }
                case Outbound.Rejected r -> {
                    // 봇은 다음 생각 때 상태를 다시 보고 판단하므로 따로 할 일 없음
                }
            }
        }
    }

    private void snapshot(RoleSnapshot s) {
        phase = s.phase();
        now = s.matchTimeMs();
        remainingMs = s.remainingMs();
        target = s.target();
        proctorPos = s.proctorPosition();
        proctorFacing = s.proctorFacing();
        seats = s.seats().toArray(new Vec2[0]);
        questionCount = s.questionCount();
        if (s.cat() != null) {
            var c = s.cat();
            mySeat = c.seat();
            stunned = c.stunned();
            possessReadyAt = now + c.possessCooldownMs();
            known = new Boolean[questionCount];
            for (int q = 0; q < questionCount; q++) known[q] = c.known().get(q);
            liveAverage = c.liveAverage();
            current = c.current();
            tools = c.tools().toArray(new ToolType[0]);
            checksUsed = new int[tools.length];
        }
        if (s.proctor() != null) {
            var p = s.proctor();
            swatsLeft = p.swatsLeft();
            detectorsLeft = p.detectorsLeft();
            stunnedUntil = now + p.stunnedMs();
            inspectingSeat = p.inspectingSeat();
            inspected = p.inspecting();
        }
    }

    private void event(GameEvent e) {
        switch (e) {
            case GameEvent.PhaseChanged x -> phase = x.phase();
            case GameEvent.Possessed x -> {
                if (role == Role.CAT) {
                    mySeat = x.seat();
                    possessReadyAt = now + Math.round(cfg.cat().possessCooldownSec() * 1000);
                }
            }
            case GameEvent.CatRelocated x -> {
                mySeat = x.seat();
                stunned = false;
            }
            case GameEvent.ToolStarted x -> {
                if (role == Role.CAT && x.seat() == mySeat) {
                    var spec = cfg.tools().get(x.tool());
                    busyUntil = now + Math.round(spec.lockSec() * 1000) + 100;
                    if (x.seat() < checksUsed.length) checksUsed[x.seat()] += pendingToolQuestions;
                    if (current != null && current.seat() == x.seat()) {
                        current = new StudentInfo(current.seat(), current.paper(), current.omr(), current.tool(),
                                Math.max(0, current.toolChecksLeft() - pendingToolQuestions));
                    }
                    pendingToolQuestions = 0;
                }
            }
            case GameEvent.ToolFinished x -> {
                for (var en : x.revealed().entrySet()) {
                    if (en.getKey() < known.length) known[en.getKey()] = en.getValue();
                }
                busyUntil = 0;
            }
            case GameEvent.ToolDestroyed x -> {
                if (x.seat() < tools.length) tools[x.seat()] = null;
                if (role == Role.CAT && x.seat() == mySeat) busyUntil = 0;
            }
            case GameEvent.RemarkStarted x -> {
                if (role == Role.CAT && x.byCat()) busyUntil = now + Math.round(cfg.cat().remarkSec() * 1000) + 100;
            }
            case GameEvent.OmrMarked x -> {
                StudentInfo st = role == Role.CAT ? current : inspected;
                if (st != null && st.seat() == x.seat()) {
                    List<Boolean> omr = new ArrayList<>(st.omr());
                    omr.set(x.question(), x.value());
                    StudentInfo updated = new StudentInfo(st.seat(), st.paper(), omr, st.tool(), st.toolChecksLeft());
                    if (role == Role.CAT) current = updated;
                    else inspected = updated;
                }
                if (role == Role.CAT && x.seat() == mySeat && busyUntil > now) busyUntil = 0;
            }
            case GameEvent.InspectReady x -> inspectingSeat = x.seat();
            case GameEvent.CatchResult x -> {
                if (role == Role.PROCTOR) {
                    swatsLeft = Math.max(0, swatsLeft - 1);
                    if (!x.success()) stunnedUntil = now + Math.round(cfg.proctor().swatter().failStunSec() * 1000);
                } else if (x.success()) {
                    mySeat = -1;
                    stunned = true;
                    busyUntil = 0;
                    current = null;
                }
            }
            case GameEvent.DetectorResult x -> {
                if (role == Role.PROCTOR) detectorsLeft = Math.max(0, detectorsLeft - 1);
            }
            case GameEvent.TargetChanged x -> target = x.target();
            case GameEvent.AverageRevealed x -> {}
            case GameEvent.MatchEnded x -> phase = Phase.ENDED;
        }
    }

    void noteToolRequest(int questions) {
        pendingToolQuestions = questions;
    }

    boolean playing() {
        return phase == Phase.PLAYING;
    }

    boolean catBusy() {
        return busyUntil > now;
    }

    boolean proctorStunned() {
        return stunnedUntil > now;
    }

    /** 점수에 쓰이는 답 (서버 Student.effectiveAnswer 와 같음) */
    static boolean effective(StudentInfo st, int q) {
        Boolean m = st.omr().get(q);
        return m != null ? m : st.paper().get(q);
    }
}

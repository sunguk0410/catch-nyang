package com.catchnyang.room;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.GameEvent;
import com.catchnyang.game.GameEvent.CatRelocated;
import com.catchnyang.game.GameEvent.InspectReady;
import com.catchnyang.game.GameEvent.Possessed;
import com.catchnyang.game.Match;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.GameCommand.*;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 방 하나의 순수 로직: 입력 적용 → 규칙 tick → 역할별로 걸러서 보낼 묶음 만들기.
 * 스레드·네트워크를 모른다 (한 스레드에서만 부를 것). 멀티는 서버 GameRoom 이, 싱글은 브라우저가 감싸서 쓴다.
 * <p>
 * 한 tick 에 역할마다 묶음 하나: {@link Outbound.Clock} → 사건 → 거절 → 감독관 위치 → 실시간 평균 → 학생 상세 → (스냅샷).
 * 보낼 게 없는 tick 은 빈 묶음이지만, 시간 동기화용 Clock 만은 1초마다 넣는다.
 */
public final class RoomCore {

    private record Input(Role role, int seq, GameCommand command) {}

    private final Match match;
    private final GameConfig cfg;
    private final long tickMs;
    private final int clockEveryTicks;
    private final MatchStats stats = new MatchStats();
    private final List<Input> inputs = new ArrayList<>();
    private final Map<Role, Boolean> snapshotRequested = new EnumMap<>(Role.class);
    private final Map<Role, Integer> ackSeq = new EnumMap<>(Role.class);
    private final Map<Role, Integer> sentAckSeq = new EnumMap<>(Role.class);
    private long tickCount;
    private Vec2 sentProctorPos;
    private Vec2 sentProctorFacing;
    private double sentLiveAverage = Double.NaN;
    private boolean finished;

    public RoomCore(Match match, GameConfig cfg) {
        this.match = match;
        this.cfg = cfg;
        this.tickMs = 1000L / cfg.server().tickHz();
        this.clockEveryTicks = cfg.server().tickHz();
        for (Role r : Role.values()) {
            snapshotRequested.put(r, Boolean.TRUE);  // 첫 tick 에 전체 상태를 보냄
            ackSeq.put(r, 0);
            sentAckSeq.put(r, 0);
        }
        sentProctorPos = match.proctor().position();
        sentProctorFacing = match.proctor().facing();
    }

    /** 다음 {@link #tick} 에 적용됨. seq: 클라 입력 순번 (처리되면 Clock.ackSeq 로 돌아감, 0 = 없음) */
    public void submit(Role role, int seq, GameCommand command) {
        if (!finished) inputs.add(new Input(role, seq, command));
    }

    /** 다음 tick 에 그 역할에게 전체 상태를 보냄 (재접속 등) */
    public void requestSnapshot(Role role) {
        snapshotRequested.put(role, Boolean.TRUE);
    }

    /** 한 tick 진행. 역할별 보낼 묶음 (비어 있을 수 있음). 끝난 방이면 둘 다 빈 묶음 */
    public Map<Role, List<Outbound>> tick() {
        Map<Role, List<Outbound>> out = new EnumMap<>(Role.class);
        for (Role r : Role.values()) out.put(r, new ArrayList<>());
        if (finished) return out;
        tickCount++;

        Map<Role, List<Outbound>> details = new EnumMap<>(Role.class);
        Map<Role, List<Outbound>> rejected = new EnumMap<>(Role.class);
        for (Role r : Role.values()) {
            details.put(r, new ArrayList<>());
            rejected.put(r, new ArrayList<>());
        }

        // 1) 입력 적용
        for (Input in : inputs) {
            ActionResult result = apply(in.role(), in.command());
            if (in.seq() != 0) ackSeq.put(in.role(), in.seq());
            if (result != ActionResult.OK) rejected.get(in.role()).add(new Outbound.Rejected(in.seq(), in.command(), result));
        }
        inputs.clear();

        // 2) 규칙 (타이머·학생·적발·평균)
        match.tick(tickMs);

        // 3) 변경분을 역할별로 걸러서 모음
        for (GameEvent e : match.drainEvents()) {
            stats.accept(e);
            for (Role role : Role.values()) {
                GameEvent visible = EventFilter.forRole(role, e, match, cfg).orElse(null);
                if (visible != null) out.get(role).add(new Outbound.Event(visible));
            }
            // 새로 보게 된 학생의 시험지·OMR 전체
            switch (e) {
                case Possessed p -> details.get(Role.CAT).add(detail(p.seat(), true));
                case CatRelocated r -> details.get(Role.CAT).add(detail(r.seat(), true));
                case InspectReady i -> details.get(Role.PROCTOR).add(detail(i.seat(), false));
                default -> {}
            }
        }
        for (Role r : Role.values()) out.get(r).addAll(rejected.get(r));

        Vec2 pos = match.proctor().position();
        Vec2 facing = match.proctor().facing();
        if (!pos.equals(sentProctorPos) || !facing.equals(sentProctorFacing)) {
            sentProctorPos = pos;
            sentProctorFacing = facing;
            for (Role r : Role.values()) out.get(r).add(new Outbound.ProctorState(pos, facing));
        }

        double live = match.classAverage();
        if (Double.compare(live, sentLiveAverage) != 0) {
            sentLiveAverage = live;
            out.get(Role.CAT).add(new Outbound.LiveAverage(live));
        }

        for (Role r : Role.values()) {
            List<Outbound> batch = out.get(r);
            batch.addAll(details.get(r));
            if (snapshotRequested.get(r)) {
                snapshotRequested.put(r, Boolean.FALSE);
                batch.add(new Outbound.Snapshot(RoleSnapshot.of(r, match)));
            }
            int ack = ackSeq.get(r);
            boolean clockDue = tickCount % clockEveryTicks == 0 || match.phase() == Phase.ENDED;
            if (!batch.isEmpty() || ack != sentAckSeq.get(r) || clockDue) {
                sentAckSeq.put(r, ack);
                // addFirst(Java 21) 는 브라우저 변환(TeaVM)이 지원 안 함
                batch.add(0, new Outbound.Clock(match.phase(), match.now(), match.remainingMs(), ack));
            }
        }

        if (match.phase() == Phase.ENDED) finished = true;
        return out;
    }

    private Outbound.StudentDetail detail(int seat, boolean withTool) {
        return new Outbound.StudentDetail(StudentInfo.of(match.students().get(seat), match.questionCount(), withTool));
    }

    private ActionResult apply(Role role, GameCommand command) {
        if (command instanceof Leave) {
            match.forfeit(role);
            return ActionResult.OK;
        }
        boolean cat = role == Role.CAT;
        return switch (command) {
            case Possess c when cat -> match.catPossess(c.seat());
            case UseTool c when cat -> match.catUseTool(c.questions());
            case Remark c when cat -> match.catRemark(c.question());
            case Move c when !cat -> match.proctorMove(c.dx(), c.dz());
            case Look c when !cat -> match.proctorLook(c.fx(), c.fz());
            case Swat c when !cat -> match.proctorSwat();
            case Detect c when !cat -> match.proctorDetect();
            default -> ActionResult.INVALID;  // 다른 역할의 행동
        };
    }

    public Match match() { return match; }
    public GameConfig config() { return cfg; }
    /** 판이 끝난 뒤 읽을 것 */
    public MatchStats stats() { return stats; }
    public boolean isFinished() { return finished; }
}

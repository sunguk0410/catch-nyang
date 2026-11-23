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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 방 하나 = 판 하나 + 플레이어 둘.
 * <ul>
 *   <li>{@link #submit} · {@link #requestSnapshot} 은 아무 스레드(웹소켓)에서나 호출 — 큐에 넣기만 한다.</li>
 *   <li>{@link #tick} 은 이 방 전용 스레드에서만 호출 — 입력 적용 → 규칙 → 역할별 전송.</li>
 * </ul>
 * 한 tick 에 역할마다 묶음 하나를 보낸다: {@link Outbound.Clock} → 사건 → 거절 → 학생 상세 → (스냅샷).
 * 보낼 게 없는 tick 은 건너뛰되, 시간 동기화용 Clock 만은 1초마다 보낸다.
 */
public final class GameRoom {

    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);

    /** 한 방에 쌓일 수 있는 미처리 입력 상한 (넘치면 버림 — 도배 방지) */
    static final int MAX_PENDING_INPUTS = 256;

    record Input(Role role, int seq, GameCommand command) {}

    private final String id;
    private final Match match;
    private final GameConfig cfg;
    private final MatchInfo info;
    private final long tickMs;
    private final int clockEveryTicks;
    private final Map<Role, PlayerChannel> channels = new EnumMap<>(Role.class);
    private final Queue<Input> inputs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingInputs = new AtomicInteger();
    private final Map<Role, AtomicBoolean> snapshotRequested = new EnumMap<>(Role.class);
    private final MatchStats stats = new MatchStats();

    // 방 스레드 전용
    private final Map<Role, Integer> ackSeq = new EnumMap<>(Role.class);
    private final Map<Role, Integer> sentAckSeq = new EnumMap<>(Role.class);
    private long tickCount;
    private Vec2 sentProctorPos;
    private Vec2 sentProctorFacing;
    private double sentLiveAverage = Double.NaN;

    private volatile boolean finished;

    public GameRoom(String id, Match match, GameConfig cfg, PlayerChannel cat, PlayerChannel proctor) {
        this(id, match, cfg, MatchInfo.anonymous(), cat, proctor);
    }

    public GameRoom(String id, Match match, GameConfig cfg, MatchInfo info, PlayerChannel cat, PlayerChannel proctor) {
        this.id = id;
        this.match = match;
        this.cfg = cfg;
        this.info = info;
        this.tickMs = 1000L / cfg.server().tickHz();
        this.clockEveryTicks = cfg.server().tickHz();
        channels.put(Role.CAT, cat);
        channels.put(Role.PROCTOR, proctor);
        for (Role r : Role.values()) {
            snapshotRequested.put(r, new AtomicBoolean(true));  // 첫 tick 에 전체 상태를 보냄
            ackSeq.put(r, 0);
            sentAckSeq.put(r, 0);
        }
        sentProctorPos = match.proctor().position();
        sentProctorFacing = match.proctor().facing();
    }

    /** 네트워크 스레드에서 호출. 다음 tick 에 적용됨 */
    public void submit(Role role, GameCommand command) {
        submit(role, 0, command);
    }

    /** seq: 클라 입력 순번. 처리되면 그 역할의 다음 묶음 Clock.ackSeq 로 돌아감 */
    public boolean submit(Role role, int seq, GameCommand command) {
        if (finished) return false;
        // 나가기는 상한과 무관하게 받음 (끊김 처리가 막히면 안 됨)
        if (pendingInputs.incrementAndGet() > MAX_PENDING_INPUTS && !(command instanceof Leave)) {
            pendingInputs.decrementAndGet();
            return false;
        }
        inputs.add(new Input(role, seq, command));
        return true;
    }

    /** 다음 tick 에 그 역할에게 전체 상태를 보냄 (재접속 등). 아무 스레드에서나 */
    public void requestSnapshot(Role role) {
        snapshotRequested.get(role).set(true);
    }

    /** 방 스레드에서만 호출 */
    void tick() {
        if (finished) return;
        tickCount++;

        Map<Role, List<Outbound>> out = new EnumMap<>(Role.class);
        Map<Role, List<Outbound>> details = new EnumMap<>(Role.class);
        for (Role r : Role.values()) {
            out.put(r, new ArrayList<>());
            details.put(r, new ArrayList<>());
        }
        List<Outbound.Rejected> catRejected = new ArrayList<>();
        List<Outbound.Rejected> proctorRejected = new ArrayList<>();

        // 1) 입력 수집·적용
        Input in;
        while ((in = inputs.poll()) != null) {
            pendingInputs.decrementAndGet();
            ActionResult result = apply(in.role(), in.command());
            if (in.seq() != 0) ackSeq.put(in.role(), in.seq());
            if (result != ActionResult.OK) {
                (in.role() == Role.CAT ? catRejected : proctorRejected)
                        .add(new Outbound.Rejected(in.seq(), in.command(), result));
            }
        }

        // 2) 규칙 (타이머·학생·적발·평균)
        match.tick(tickMs);

        // 3) 변경분을 역할별로 걸러서 모음
        for (GameEvent e : match.drainEvents()) {
            stats.accept(e);
            for (Role role : Role.values()) {
                EventFilter.forRole(role, e, match, cfg).ifPresent(v -> out.get(role).add(new Outbound.Event(v)));
            }
            // 새로 보게 된 학생의 시험지·OMR 전체
            switch (e) {
                case Possessed p -> details.get(Role.CAT).add(detail(p.seat(), true));
                case CatRelocated r -> details.get(Role.CAT).add(detail(r.seat(), true));
                case InspectReady i -> details.get(Role.PROCTOR).add(detail(i.seat(), false));
                default -> {}
            }
        }
        out.get(Role.CAT).addAll(catRejected);
        out.get(Role.PROCTOR).addAll(proctorRejected);

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
            if (snapshotRequested.get(r).getAndSet(false)) {
                batch.add(new Outbound.Snapshot(RoleSnapshot.of(r, match)));
            }
            int ack = ackSeq.get(r);
            boolean clockDue = tickCount % clockEveryTicks == 0 || match.phase() == Phase.ENDED;
            if (!batch.isEmpty() || ack != sentAckSeq.get(r) || clockDue) {
                sentAckSeq.put(r, ack);
                batch.addFirst(new Outbound.Clock(match.phase(), match.now(), match.remainingMs(), ack));
            }
        }
        out.forEach(this::send);

        if (match.phase() == Phase.ENDED) finished = true;
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

    private void send(Role role, List<Outbound> batch) {
        if (batch.isEmpty()) return;
        try {
            channels.get(role).send(batch);
        } catch (RuntimeException e) {
            // 한 플레이어 송신 실패가 게임 루프를 멈추면 안 됨. 끊김 처리는 네트워크 계층이 Leave 로 넣음
            log.warn("room {} send to {} failed: {}", id, role, e.toString());
        }
    }

    public String id() { return id; }
    public Match match() { return match; }
    public MatchInfo info() { return info; }
    /** 판이 끝난 뒤 읽을 것 */
    public MatchStats stats() { return stats; }
    public boolean isFinished() { return finished; }
}

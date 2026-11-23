package com.catchnyang.ws;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.GameEvent;
import com.catchnyang.proto.*;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.RoleSnapshot;
import com.catchnyang.room.StudentInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 도메인 ↔ proto 변환. 이름이 겹치는 게 많아서 proto 쪽은 import, 도메인 쪽은 전체 이름으로 쓴다.
 * 이 클래스는 거르지 않는다 — 역할별 필터링은 이미 방({@code EventFilter}, {@link RoleSnapshot})에서 끝난 상태.
 */
final class ProtoMapper {

    private ProtoMapper() {}

    // =====================================================================
    // 클라 → 서버
    // =====================================================================

    /** 게임 입력이 아니면 빈 리스트. ProctorMove 는 이동 + (있으면) 시선 두 명령이 됨 */
    static List<GameCommand> toCommands(ClientMessage msg) {
        return switch (msg.getPayloadCase()) {
            case POSSESS -> List.of(new GameCommand.Possess(msg.getPossess().getSeat()));
            case USE_TOOL -> List.of(new GameCommand.UseTool(List.copyOf(msg.getUseTool().getQuestionsList())));
            case REMARK -> List.of(new GameCommand.Remark(msg.getRemark().getQuestion()));
            case PROCTOR_MOVE -> {
                var m = msg.getProctorMove();
                List<GameCommand> out = new ArrayList<>(2);
                out.add(new GameCommand.Move(m.getDx(), m.getDz()));
                if (m.getFaceX() != 0 || m.getFaceZ() != 0) out.add(new GameCommand.Look(m.getFaceX(), m.getFaceZ()));
                yield out;
            }
            case SWAT -> List.of(new GameCommand.Swat());
            case DETECT -> List.of(new GameCommand.Detect());
            case LEAVE_MATCH -> List.of(new GameCommand.Leave());
            default -> List.of();
        };
    }

    static boolean isGameInput(ClientMessage msg) {
        return switch (msg.getPayloadCase()) {
            case POSSESS, USE_TOOL, REMARK, PROCTOR_MOVE, SWAT, DETECT, LEAVE_MATCH -> true;
            default -> false;
        };
    }

    // =====================================================================
    // 서버 → 클라
    // =====================================================================

    /** 한 tick 묶음 → [GameTick, (Snapshot)]. Snapshot 은 항상 뒤 (최신 상태로 덮어쓰도록) */
    static List<ServerMessage> toMessages(List<Outbound> batch) {
        GameTick.Builder tick = null;
        List<ServerMessage> snapshots = new ArrayList<>(1);
        for (Outbound o : batch) {
            if (o instanceof Outbound.Snapshot s) {
                snapshots.add(ServerMessage.newBuilder().setSnapshot(snapshot(s.snapshot())).build());
                continue;
            }
            if (tick == null) tick = GameTick.newBuilder();
            switch (o) {
                case Outbound.Clock c -> tick.setPhase(phase(c.phase()))
                        .setMatchTimeMs(u32(c.matchTimeMs()))
                        .setRemainingMs(u32(c.remainingMs()))
                        .setAckSeq(c.ackSeq());
                case Outbound.Event e -> tick.addEvents(event(e.event()));
                case Outbound.Rejected r -> tick.addRejected(rejected(r.seq(), r.reason()));
                case Outbound.ProctorState p -> tick.setProctor(proctorState(p.position(), p.facing()));
                case Outbound.LiveAverage a -> tick.setLiveAverage((float) a.average());
                case Outbound.StudentDetail d -> tick.addStudents(student(d.info()));
                case Outbound.Snapshot s -> throw new AssertionError("handled above");
            }
        }
        List<ServerMessage> out = new ArrayList<>(1 + snapshots.size());
        if (tick != null) out.add(ServerMessage.newBuilder().setTick(tick).build());
        out.addAll(snapshots);
        return out;
    }

    static Rejected rejected(int seq, ActionResult reason) {
        return Rejected.newBuilder().setSeq(seq).setReason(RejectReason.valueOf("REJECT_REASON_" + reason.name())).build();
    }

    static Snapshot snapshot(RoleSnapshot s) {
        Snapshot.Builder b = Snapshot.newBuilder()
                .setMyRole(role(s.role()))
                .setPhase(phase(s.phase()))
                .setMatchTimeMs(u32(s.matchTimeMs()))
                .setRemainingMs(u32(s.remainingMs()))
                .setTarget(s.target())
                .setCatchSuccesses(s.catchSuccesses())
                .setProctor(proctorState(s.proctorPosition(), s.proctorFacing()))
                .setQuestionCount(s.questionCount())
                .setRuleVersion(s.ruleVersion());
        for (var seat : s.seats()) b.addSeats(vec(seat));

        if (s.cat() != null) {
            var c = s.cat();
            CatView.Builder cv = CatView.newBuilder()
                    .setSeat(c.seat())
                    .setStunned(c.stunned())
                    .setStunnedMs(u32(c.stunnedMs()))
                    .setPossessCooldownMs(u32(c.possessCooldownMs()))
                    .setLiveAverage((float) c.liveAverage())
                    .setBusy(c.busy());
            c.known().forEach(cv::putKnown);
            if (c.current() != null) cv.setCurrent(student(c.current()));
            for (var t : c.tools()) cv.addTools(tool(t));
            b.setCat(cv);
        }
        if (s.proctor() != null) {
            var p = s.proctor();
            ProctorView.Builder pv = ProctorView.newBuilder()
                    .setVisibleAverage((float) p.visibleAverage())
                    .setSwatsLeft(p.swatsLeft())
                    .setDetectorsLeft(p.detectorsLeft())
                    .setStunnedMs(u32(p.stunnedMs()))
                    .setInspectingSeat(p.inspectingSeat());
            if (p.inspecting() != null) pv.setInspecting(student(p.inspecting()));
            b.setProctorView(pv);
        }
        return b.build();
    }

    static com.catchnyang.proto.GameEvent event(GameEvent e) {
        var b = com.catchnyang.proto.GameEvent.newBuilder();
        switch (e) {
            case GameEvent.PhaseChanged x -> b.setPhaseChanged(PhaseChanged.newBuilder().setPhase(phase(x.phase())));
            case GameEvent.Possessed x -> b.setPossessed(Possessed.newBuilder().setSeat(x.seat()));
            case GameEvent.ToolStarted x -> b.setToolStarted(ToolStarted.newBuilder().setTool(tool(x.tool())).setSeat(x.seat()));
            case GameEvent.ToolFinished x -> {
                var f = ToolFinished.newBuilder().setTool(tool(x.tool())).setSeat(x.seat());
                for (Map.Entry<Integer, Boolean> r : x.revealed().entrySet()) f.putRevealed(r.getKey(), r.getValue());
                b.setToolFinished(f);
            }
            case GameEvent.ToolDestroyed x -> b.setToolDestroyed(ToolDestroyed.newBuilder().setTool(tool(x.tool())).setSeat(x.seat()));
            case GameEvent.RemarkStarted x -> b.setRemarkStarted(RemarkStarted.newBuilder()
                    .setSeat(x.seat()).setQuestion(x.question()).setByCat(x.byCat()));
            case GameEvent.OmrMarked x -> b.setOmrMarked(OmrMarked.newBuilder()
                    .setSeat(x.seat()).setQuestion(x.question()).setValue(mark(x.value())));
            case GameEvent.InspectReady x -> b.setInspectReady(InspectReady.newBuilder().setSeat(x.seat()));
            case GameEvent.CatchResult x -> b.setCatchResult(CatchResult.newBuilder().setSeat(x.seat()).setSuccess(x.success()));
            case GameEvent.CatRelocated x -> b.setCatRelocated(CatRelocated.newBuilder().setSeat(x.seat()));
            case GameEvent.DetectorResult x -> b.setDetectorResult(DetectorResult.newBuilder().setHit(x.hit()).setSeat(x.seat()));
            case GameEvent.TargetChanged x -> b.setTargetChanged(TargetChanged.newBuilder().setTarget(x.target()));
            case GameEvent.AverageRevealed x -> b.setAverageRevealed(AverageRevealed.newBuilder().setAverage((float) x.average()));
            case GameEvent.MatchEnded x -> b.setMatchEnded(MatchEnded.newBuilder()
                    .setWinner(role(x.winner()))
                    .setReason(EndReason.valueOf("END_REASON_" + x.reason().name()))
                    .setFinalAverage((float) x.finalAverage())
                    .setFinalTarget(x.finalTarget()));
        }
        return b.build();
    }

    static StudentDetail student(StudentInfo s) {
        StudentDetail.Builder b = StudentDetail.newBuilder()
                .setSeat(s.seat())
                .setTool(tool(s.tool()))
                .setToolChecksLeft(s.toolChecksLeft());
        b.addAllPaper(s.paper());
        for (Boolean v : s.omr()) b.addOmr(mark(v));
        return b.build();
    }

    static ProctorState proctorState(com.catchnyang.game.Vec2 pos, com.catchnyang.game.Vec2 facing) {
        return ProctorState.newBuilder().setPosition(vec(pos)).setFacing(vec(facing)).build();
    }

    static Vec2 vec(com.catchnyang.game.Vec2 v) {
        return Vec2.newBuilder().setX((float) v.x()).setZ((float) v.z()).build();
    }

    static Role role(com.catchnyang.game.Role r) {
        return r == null ? Role.ROLE_UNSPECIFIED : Role.valueOf("ROLE_" + r.name());
    }

    static Phase phase(com.catchnyang.game.Phase p) {
        return Phase.valueOf("PHASE_" + p.name());
    }

    static ToolType tool(com.catchnyang.game.ToolType t) {
        return t == null ? ToolType.TOOL_TYPE_UNSPECIFIED : ToolType.valueOf("TOOL_TYPE_" + t.name());
    }

    static Mark mark(Boolean v) {
        return v == null ? Mark.MARK_EMPTY : v ? Mark.MARK_O : Mark.MARK_X;
    }

    private static int u32(long v) {
        return (int) Math.max(0, Math.min(v, 0xFFFF_FFFFL));
    }
}

package com.catchnyang.room;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.EndReason;
import com.catchnyang.game.GameEvent;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.ToolType;
import com.catchnyang.game.Vec2;

import java.util.List;
import java.util.Map;

/**
 * 방이 한 역할에게 보내는 묶음 → protobuf ServerMessage 의 "proto3 JSON" 모양 (JSON 배열).
 * 브라우저 싱글 모드는 이걸 protobuf-es fromJson 으로 읽어서 서버가 보낸 것과 똑같은 메시지로 만든다
 * → 화면 코드는 싱글·멀티를 구분하지 않는다.
 * <p>
 * 서버 ws/ProtoMapper 와 같은 변환을 JSON 으로 하는 것. 메시지 모양을 바꾸면 둘 다 바꿀 것.
 * 소수는 런타임마다 문자열 표현이 달라질 수 있어서 직접 포맷한다 (JVM·JS 결과 비교 테스트용).
 */
public final class OutboundJson {

    private OutboundJson() {}

    /** [{"tick":{…}}, {"snapshot":{…}}] — 스냅샷은 항상 뒤 (최신 상태로 덮어쓰도록) */
    public static String messages(List<Outbound> batch) {
        // tick 필드는 종류별로 모았다가 마지막에 합침 (배열은 묶음 안에서 섞여 나올 수 있음)
        StringBuilder head = new StringBuilder();
        StringBuilder events = new StringBuilder();
        StringBuilder rejected = new StringBuilder();
        StringBuilder students = new StringBuilder();
        StringBuilder snaps = new StringBuilder();
        boolean anyTick = false;
        for (Outbound o : batch) {
            switch (o) {
                case Outbound.Snapshot s -> {
                    comma(snaps).append("{\"snapshot\":");
                    snapshot(snaps, s.snapshot());
                    snaps.append('}');
                }
                case Outbound.Clock c -> {
                    anyTick = true;
                    comma(head).append("\"phase\":\"").append(phase(c.phase())).append("\",\"matchTimeMs\":").append(u32(c.matchTimeMs()))
                            .append(",\"remainingMs\":").append(u32(c.remainingMs())).append(",\"ackSeq\":").append(c.ackSeq());
                }
                case Outbound.Event e -> {
                    anyTick = true;
                    comma(events).append(eventJson(e.event()));
                }
                case Outbound.Rejected r -> {
                    anyTick = true;
                    comma(rejected).append("{\"seq\":").append(r.seq()).append(",\"reason\":\"").append(reject(r.reason())).append("\"}");
                }
                case Outbound.ProctorState p -> {
                    anyTick = true;
                    comma(head).append("\"proctor\":");
                    proctorState(head, p.position(), p.facing());
                }
                case Outbound.LiveAverage a -> {
                    anyTick = true;
                    comma(head).append("\"liveAverage\":").append(num(a.average()));
                }
                case Outbound.StudentDetail d -> {
                    anyTick = true;
                    student(comma(students), d.info());
                }
            }
        }
        StringBuilder out = new StringBuilder("[");
        if (anyTick) {
            StringBuilder tick = new StringBuilder(head);
            if (events.length() > 0) comma(tick).append("\"events\":[").append(events).append(']');
            if (rejected.length() > 0) comma(tick).append("\"rejected\":[").append(rejected).append(']');
            if (students.length() > 0) comma(tick).append("\"students\":[").append(students).append(']');
            out.append("{\"tick\":{").append(tick).append("}}");
        }
        if (snaps.length() > 0) comma(out).append(snaps);
        return out.append(']').toString();
    }

    private static StringBuilder comma(StringBuilder b) {
        if (b.length() > 0 && b.charAt(b.length() - 1) != '[') b.append(',');
        return b;
    }

    // ---------------------------------------------------------------------

    static String eventJson(GameEvent e) {
        return switch (e) {
            case GameEvent.PhaseChanged x -> "{\"phaseChanged\":{\"phase\":\"" + phase(x.phase()) + "\"}}";
            case GameEvent.Possessed x -> "{\"possessed\":{\"seat\":" + x.seat() + "}}";
            case GameEvent.ToolStarted x -> "{\"toolStarted\":{\"tool\":\"" + tool(x.tool()) + "\",\"seat\":" + x.seat() + "}}";
            case GameEvent.ToolFinished x -> {
                StringBuilder b = new StringBuilder("{\"toolFinished\":{\"tool\":\"").append(tool(x.tool())).append("\",\"seat\":").append(x.seat()).append(",\"revealed\":{");
                boolean f = true;
                for (Map.Entry<Integer, Boolean> en : x.revealed().entrySet()) {
                    if (!f) b.append(',');
                    f = false;
                    b.append('"').append(en.getKey()).append("\":").append(en.getValue());
                }
                yield b.append("}}}").toString();
            }
            case GameEvent.ToolDestroyed x -> "{\"toolDestroyed\":{\"tool\":\"" + tool(x.tool()) + "\",\"seat\":" + x.seat() + "}}";
            case GameEvent.RemarkStarted x -> "{\"remarkStarted\":{\"seat\":" + x.seat() + ",\"question\":" + x.question() + ",\"byCat\":" + x.byCat() + "}}";
            case GameEvent.OmrMarked x -> "{\"omrMarked\":{\"seat\":" + x.seat() + ",\"question\":" + x.question() + ",\"value\":\"" + mark(x.value()) + "\"}}";
            case GameEvent.InspectReady x -> "{\"inspectReady\":{\"seat\":" + x.seat() + "}}";
            case GameEvent.CatchResult x -> "{\"catchResult\":{\"seat\":" + x.seat() + ",\"success\":" + x.success() + "}}";
            case GameEvent.CatRelocated x -> "{\"catRelocated\":{\"seat\":" + x.seat() + "}}";
            case GameEvent.DetectorResult x -> "{\"detectorResult\":{\"hit\":" + x.hit() + ",\"seat\":" + x.seat() + "}}";
            case GameEvent.TargetChanged x -> "{\"targetChanged\":{\"target\":" + x.target() + "}}";
            case GameEvent.AverageRevealed x -> "{\"averageRevealed\":{\"average\":" + num(x.average()) + "}}";
            case GameEvent.MatchEnded x -> "{\"matchEnded\":{\"winner\":\"" + role(x.winner()) + "\",\"reason\":\"" + end(x.reason())
                    + "\",\"finalAverage\":" + num(x.finalAverage()) + ",\"finalTarget\":" + x.finalTarget() + "}}";
        };
    }

    static void snapshot(StringBuilder b, RoleSnapshot s) {
        b.append("{\"myRole\":\"").append(role(s.role())).append("\",\"phase\":\"").append(phase(s.phase()))
                .append("\",\"matchTimeMs\":").append(u32(s.matchTimeMs())).append(",\"remainingMs\":").append(u32(s.remainingMs()))
                .append(",\"target\":").append(s.target()).append(",\"catchSuccesses\":").append(s.catchSuccesses())
                .append(",\"proctor\":");
        proctorState(b, s.proctorPosition(), s.proctorFacing());
        b.append(",\"seats\":[");
        for (int i = 0; i < s.seats().size(); i++) {
            if (i > 0) b.append(',');
            vec(b, s.seats().get(i));
        }
        b.append("],\"questionCount\":").append(s.questionCount()).append(",\"ruleVersion\":\"").append(s.ruleVersion()).append('"');
        if (s.cat() != null) {
            var c = s.cat();
            b.append(",\"cat\":{\"seat\":").append(c.seat()).append(",\"stunned\":").append(c.stunned())
                    .append(",\"stunnedMs\":").append(u32(c.stunnedMs())).append(",\"possessCooldownMs\":").append(u32(c.possessCooldownMs()))
                    .append(",\"known\":{");
            boolean f = true;
            for (Map.Entry<Integer, Boolean> en : c.known().entrySet()) {
                if (!f) b.append(',');
                f = false;
                b.append('"').append(en.getKey()).append("\":").append(en.getValue());
            }
            b.append("},\"liveAverage\":").append(num(c.liveAverage())).append(",\"busy\":").append(c.busy());
            if (c.current() != null) {
                b.append(",\"current\":");
                student(b, c.current());
            }
            b.append(",\"tools\":[");
            for (int i = 0; i < c.tools().size(); i++) {
                if (i > 0) b.append(',');
                b.append('"').append(tool(c.tools().get(i))).append('"');
            }
            b.append("]}");
        }
        if (s.proctor() != null) {
            var p = s.proctor();
            b.append(",\"proctorView\":{\"visibleAverage\":").append(num(p.visibleAverage()))
                    .append(",\"swatsLeft\":").append(p.swatsLeft()).append(",\"detectorsLeft\":").append(p.detectorsLeft())
                    .append(",\"stunnedMs\":").append(u32(p.stunnedMs())).append(",\"inspectingSeat\":").append(p.inspectingSeat());
            if (p.inspecting() != null) {
                b.append(",\"inspecting\":");
                student(b, p.inspecting());
            }
            b.append('}');
        }
        b.append('}');
    }

    static void student(StringBuilder b, StudentInfo s) {
        b.append("{\"seat\":").append(s.seat()).append(",\"paper\":[");
        for (int q = 0; q < s.paper().size(); q++) {
            if (q > 0) b.append(',');
            b.append(s.paper().get(q));
        }
        b.append("],\"omr\":[");
        for (int q = 0; q < s.omr().size(); q++) {
            if (q > 0) b.append(',');
            b.append('"').append(mark(s.omr().get(q))).append('"');
        }
        b.append("],\"tool\":\"").append(tool(s.tool())).append("\",\"toolChecksLeft\":").append(s.toolChecksLeft()).append('}');
    }

    static void proctorState(StringBuilder b, Vec2 pos, Vec2 facing) {
        b.append("{\"position\":");
        vec(b, pos);
        b.append(",\"facing\":");
        vec(b, facing);
        b.append('}');
    }

    static void vec(StringBuilder b, Vec2 v) {
        b.append("{\"x\":").append(num(v.x())).append(",\"z\":").append(num(v.z())).append('}');
    }

    static String role(Role r) { return r == null ? "ROLE_UNSPECIFIED" : "ROLE_" + r.name(); }
    static String phase(Phase p) { return "PHASE_" + p.name(); }
    static String tool(ToolType t) { return t == null ? "TOOL_TYPE_UNSPECIFIED" : "TOOL_TYPE_" + t.name(); }
    static String reject(ActionResult r) { return "REJECT_REASON_" + r.name(); }
    static String end(EndReason r) { return "END_REASON_" + r.name(); }
    static String mark(Boolean v) { return v == null ? "MARK_EMPTY" : v ? "MARK_O" : "MARK_X"; }

    static long u32(long v) {
        return Math.max(0, Math.min(v, 0xFFFF_FFFFL));
    }

    /** 소수 넷째 자리까지, 런타임과 무관하게 같은 문자열 (예: 61.6667, -0.5, 3) */
    public static String num(double v) {
        long scaled = Math.round(v * 10000);
        boolean neg = scaled < 0;
        long a = Math.abs(scaled);
        long whole = a / 10000;
        long frac = a % 10000;
        StringBuilder b = new StringBuilder();
        if (neg && (whole != 0 || frac != 0)) b.append('-');
        b.append(whole);
        if (frac != 0) {
            String f = Long.toString(frac + 10000).substring(1);
            int end = f.length();
            while (end > 0 && f.charAt(end - 1) == '0') end--;
            b.append('.').append(f, 0, end);
        }
        return b.toString();
    }
}

package com.catchnyang.room;

import com.catchnyang.game.config.GameConfigJson;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 클라 입력(protobuf ClientMessage 의 proto3 JSON) → 게임 명령. 브라우저 싱글 모드용.
 * 서버 ws/ProtoMapper.toCommands 와 같은 변환 (바꾸면 둘 다 바꿀 것).
 * proto3 JSON 은 기본값(0)을 생략하므로 없는 숫자는 0 으로 본다.
 */
public final class CommandJson {

    private CommandJson() {}

    /** 게임 입력이 아니면(로비 메시지 등) 빈 목록. proctorMove 는 이동 + (있으면) 시선 두 명령 */
    @SuppressWarnings("unchecked")
    public static List<GameCommand> parse(String json) {
        Map<String, Object> m = (Map<String, Object>) GameConfigJson.parse(json);
        List<GameCommand> out = new ArrayList<>();
        if (m.containsKey("possess")) {
            out.add(new GameCommand.Possess(i(obj(m, "possess"), "seat")));
        } else if (m.containsKey("useTool")) {
            List<Integer> qs = new ArrayList<>();
            Object raw = obj(m, "useTool").get("questions");
            if (raw instanceof List<?> l) for (Object o : l) qs.add((int) Math.round((Double) o));
            out.add(new GameCommand.UseTool(qs));
        } else if (m.containsKey("remark")) {
            out.add(new GameCommand.Remark(i(obj(m, "remark"), "question")));
        } else if (m.containsKey("proctorMove")) {
            Map<String, Object> p = obj(m, "proctorMove");
            out.add(new GameCommand.Move(d(p, "dx"), d(p, "dz")));
            double fx = d(p, "faceX");
            double fz = d(p, "faceZ");
            if (fx != 0 || fz != 0) out.add(new GameCommand.Look(fx, fz));
        } else if (m.containsKey("swat")) {
            out.add(new GameCommand.Swat());
        } else if (m.containsKey("detect")) {
            out.add(new GameCommand.Detect());
        } else if (m.containsKey("leaveMatch")) {
            out.add(new GameCommand.Leave());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    private static double d(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v instanceof Double x ? x : 0;
    }

    private static int i(Map<String, Object> m, String k) {
        return (int) Math.round(d(m, k));
    }
}

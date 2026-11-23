package com.catchnyang.game.config;

import com.catchnyang.game.ToolType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 아주 작은 JSON 파서 + game-config.json → {@link GameConfig}.
 * Jackson 은 브라우저(TeaVM 변환)에서 못 쓰므로, 서버·브라우저 양쪽에서 똑같이 도는 순수 Java 로 둔다.
 * (서버는 Jackson 로더도 있음 — 모르는 키를 거부하는 엄격한 검사용)
 */
public final class GameConfigJson {

    private final String s;
    private int i;

    private GameConfigJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        GameConfigJson p = new GameConfigJson(json);
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    private Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws();
            expect(':');
            m.put(k, value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (s.charAt(i) == ',') { i++; continue; }
            expect(']');
            return a;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);
                }
            } else {
                b.append(c);
            }
        }
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.parseDouble(s.substring(start, i));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
    }

    private void expect(char c) {
        if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
        i++;
    }

    // ---------- game-config.json → GameConfig ----------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> m, String k) {
        return (Map<String, Object>) m.get(k);
    }

    private static double d(Map<String, Object> m, String k) {
        return ((Double) m.get(k));
    }

    private static int n(Map<String, Object> m, String k) {
        return (int) Math.round((Double) m.get(k));
    }

    @SuppressWarnings("unchecked")
    public static GameConfig parseConfig(String json) {
        Map<String, Object> r = (Map<String, Object>) parse(json);
        var server = obj(r, "server");
        var m = obj(r, "match");
        var c = obj(r, "classroom");
        var cat = obj(r, "cat");
        var p = obj(r, "proctor");
        var sw = obj(p, "swatter");
        var de = obj(p, "detector");

        Map<String, List<Integer>> look = new LinkedHashMap<>();
        obj(cat, "lookRangeDeg").forEach((k, v) -> {
            List<Integer> range = new ArrayList<>();
            for (Object o : (List<Object>) v) range.add((int) Math.round((Double) o));
            look.put(k, range);
        });

        Map<ToolType, GameConfig.ToolSpec> tools = new EnumMap<>(ToolType.class);
        obj(r, "tools").forEach((k, v) -> {
            Map<String, Object> t = (Map<String, Object>) v;
            tools.put(ToolType.valueOf(k), new GameConfig.ToolSpec(
                    n(t, "totalChecks"), n(t, "perUse"), d(t, "lockSec"), (String) t.get("detect"),
                    (Double) t.get("visibleRangeM"), (Boolean) t.get("enabled")));
        });

        return new GameConfig(
                (String) r.get("version"),
                new GameConfig.Server(n(server, "tickHz")),
                new GameConfig.MatchRules(n(m, "durationSec"), n(m, "prepareSec"), n(m, "studentCount"), n(m, "gridSize"),
                        n(m, "questionCount"), n(m, "pointsPerQuestion"), d(m, "initialAccuracyMin"), d(m, "initialAccuracyMax"),
                        d(m, "studentMistakeRate"), d(m, "transcribeIntervalSecMin"), d(m, "transcribeIntervalSecMax"),
                        d(m, "mistakeFixDelaySecMin"), d(m, "mistakeFixDelaySecMax"), n(m, "initialTarget")),
                new GameConfig.Classroom(d(c, "seatSpacingM"), d(c, "interactRangeM"), d(c, "halfWidthM"), d(c, "halfDepthM"),
                        d(c, "proctorSpawnX"), d(c, "proctorSpawnZ"), d(c, "deskHalfWidthM"), d(c, "deskFrontM"),
                        d(c, "deskBackM"), d(c, "proctorRadiusM")),
                new GameConfig.CatRules(d(cat, "possessCooldownSec"), d(cat, "remarkSec"), d(cat, "stunSec"), look, n(cat, "fovDeg")),
                tools,
                new GameConfig.ProctorRules(d(p, "moveSpeedMps"), n(p, "fovDeg"), d(p, "inspectHoldSec"), d(p, "averageRevealIntervalSec"),
                        new GameConfig.Swatter(n(sw, "maxAttempts"), n(sw, "winOnSuccessCount"), n(sw, "successTargetDelta"),
                                n(sw, "failTargetDelta"), d(sw, "failStunSec")),
                        new GameConfig.Detector(n(de, "maxUses"), d(de, "revealSec"), n(de, "missTargetDelta"))));
    }
}

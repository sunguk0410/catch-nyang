package com.catchnyang.js;

import com.catchnyang.ai.Conformance;
import com.catchnyang.ai.LocalMatch;
import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfigJson;
import com.catchnyang.room.CommandJson;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.OutboundJson;
import org.teavm.jso.JSExport;

import java.util.HashMap;
import java.util.Map;

/**
 * 브라우저 싱글 모드가 부르는 함수들 (TeaVM 이 JS 로 export). 주고받는 건 문자열·숫자만.
 * 메시지는 서버와 같은 protobuf 의 JSON 모양 → 브라우저가 fromJson 으로 서버 메시지와 똑같이 읽는다.
 */
public final class RulesJs {

    private RulesJs() {}

    private static final Map<Integer, LocalMatch> MATCHES = new HashMap<>();
    private static int nextId = 1;

    public static void main(String[] args) {
        // TeaVM 진입 클래스. 실제로 쓰는 건 아래 @JSExport 함수들
    }

    /** 새 판. humanRole = "CAT" | "PROCTOR" (상대는 AI). 돌려준 번호로 나머지 함수를 부름 */
    @JSExport
    public static int createMatch(String configJson, int seed, String humanRole) {
        int id = nextId++;
        MATCHES.put(id, new LocalMatch(GameConfigJson.parseConfig(configJson), seed, Role.valueOf(humanRole)));
        return id;
    }

    /** 사람 입력 (ClientMessage 의 proto3 JSON). 다음 tick 에 적용 */
    @JSExport
    public static void submit(int id, int seq, String clientMessageJson) {
        LocalMatch m = MATCHES.get(id);
        if (m == null) return;
        for (GameCommand c : CommandJson.parse(clientMessageJson)) m.submit(seq, c);
    }

    @JSExport
    public static void requestSnapshot(int id) {
        LocalMatch m = MATCHES.get(id);
        if (m != null) m.requestSnapshot();
    }

    /** 한 tick (50ms) 진행. 사람에게 보낼 ServerMessage 들의 JSON 배열 */
    @JSExport
    public static String tick(int id) {
        LocalMatch m = MATCHES.get(id);
        return m == null ? "[]" : OutboundJson.messages(m.tick());
    }

    @JSExport
    public static boolean isFinished(int id) {
        LocalMatch m = MATCHES.get(id);
        return m == null || m.isFinished();
    }

    @JSExport
    public static void dispose(int id) {
        MATCHES.remove(id);
    }

    /** JVM 과 같은 규칙인지 확인용 기록 (rules ConformanceTest 와 같은 함수) */
    @JSExport
    public static String conformance(String configJson, int seed) {
        return Conformance.run(GameConfigJson.parseConfig(configJson), seed);
    }
}

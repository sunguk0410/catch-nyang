package com.catchnyang.ws;

import com.catchnyang.ai.LocalMatch;
import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigLoader;
import com.catchnyang.proto.ServerMessage;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.OutboundJson;
import com.google.protobuf.util.JsonFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 싱글 모드(브라우저)가 받는 JSON 과 멀티 서버가 보내는 protobuf 가 같은 메시지인지.
 * 봇끼리 한 판 전체의 모든 묶음을 양쪽 방식으로 바꿔 비교한다 (소수는 JSON 이 넷째 자리까지라 1e-3 까지 허용).
 */
class OutboundJsonParityTest {

    static final GameConfig CFG = GameConfigLoader.loadDefault();
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    @Test
    @DisplayName("봇끼리 한 판의 모든 묶음: OutboundJson == ProtoMapper (양쪽 역할)")
    void sameMessagesForWholeMatch() throws Exception {
        java.util.Set<String> kinds = new java.util.TreeSet<>();
        for (int seed : new int[]{11, 2024, 7, 99, 31337}) {
            LocalMatch m = new LocalMatch(CFG, seed, null);
            int compared = 0;
            for (int t = 0; t < 6000 && !m.isFinished(); t++) {
                Map<Role, List<Outbound>> out = m.tickAllRoles();  // 봇이 실제로 움직이는 판
                for (List<Outbound> batch : out.values()) {
                    if (batch.isEmpty()) continue;
                    compare(batch);
                    compared++;
                    for (Outbound o : batch) {
                        kinds.add(o instanceof Outbound.Event e ? e.event().getClass().getSimpleName() : o.getClass().getSimpleName());
                    }
                }
            }
            assertThat(compared).isGreaterThan(500);
        }
        // 여러 판을 합쳐 사건 종류가 골고루 나와야 의미 있는 비교
        assertThat(kinds).contains("Possessed", "ToolStarted", "ToolFinished", "RemarkStarted", "OmrMarked",
                "InspectReady", "CatchResult", "DetectorResult", "TargetChanged", "AverageRevealed", "MatchEnded",
                "Snapshot", "StudentDetail", "ProctorState", "LiveAverage", "Rejected");
    }

    private static void compare(List<Outbound> batch) throws Exception {
        // 서버 방식: protobuf → 공식 JSON 출력
        List<ServerMessage> proto = ProtoMapper.toMessages(batch);
        // 싱글 방식: OutboundJson → 공식 JSON 파서로 protobuf 로 읽기 (브라우저의 fromJson 과 같은 규칙)
        JsonNode arr = JSON.readTree(OutboundJson.messages(batch));
        List<ServerMessage> fromJson = new ArrayList<>();
        for (JsonNode n : arr) {
            ServerMessage.Builder b = ServerMessage.newBuilder();
            JsonFormat.parser().merge(n.toString(), b);
            fromJson.add(b.build());
        }
        assertThat(fromJson).hasSameSizeAs(proto);
        for (int i = 0; i < proto.size(); i++) {
            JsonNode a = JSON.readTree(PRINTER.print(proto.get(i)));
            JsonNode b = JSON.readTree(PRINTER.print(fromJson.get(i)));
            assertSame(a, b, "$");
        }
    }

    private static void assertSame(JsonNode a, JsonNode b, String path) {
        if (a.isNumber() && b.isNumber()) {
            assertThat(b.asDouble()).as(path).isCloseTo(a.asDouble(), org.assertj.core.data.Offset.offset(1e-3));
            return;
        }
        if (a.isObject()) {
            assertThat(b.isObject()).as(path).isTrue();
            assertThat(set(b.propertyNames())).as(path + " keys").isEqualTo(set(a.propertyNames()));
            for (String k : a.propertyNames()) assertSame(a.get(k), b.get(k), path + "." + k);
            return;
        }
        if (a.isArray()) {
            assertThat(b.size()).as(path + " size").isEqualTo(a.size());
            for (int i = 0; i < a.size(); i++) assertSame(a.get(i), b.get(i), path + "[" + i + "]");
            return;
        }
        assertThat(b).as(path).isEqualTo(a);
    }

    private static java.util.Set<String> set(Iterable<String> it) {
        java.util.Set<String> s = new java.util.TreeSet<>();
        it.forEach(s::add);
        return s;
    }
}

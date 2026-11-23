package com.catchnyang.ws;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.EndReason;
import com.catchnyang.game.GameEvent.MatchEnded;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.ToolType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 도메인 enum ↔ proto enum 은 이름(+ 접두사)으로 맞춘다. 한쪽에만 값을 추가하면 실행 중에 터지므로 여기서 미리 잡는다.
 * 실패하면: protocol/catchnyang/v1/game.proto 에 같은 이름의 값을 (접두사 붙여) 추가할 것.
 */
class ProtoMapperTest {

    @Test
    @DisplayName("도메인 enum 의 모든 값에 대응하는 proto 값이 있음")
    void everyDomainEnumValueHasProtoCounterpart() {
        for (Role r : Role.values()) assertThatCode(() -> ProtoMapper.role(r)).as("Role.%s", r).doesNotThrowAnyException();
        for (Phase p : Phase.values()) assertThatCode(() -> ProtoMapper.phase(p)).as("Phase.%s", p).doesNotThrowAnyException();
        for (ToolType t : ToolType.values()) assertThatCode(() -> ProtoMapper.tool(t)).as("ToolType.%s", t).doesNotThrowAnyException();
        for (ActionResult a : ActionResult.values()) {
            assertThatCode(() -> ProtoMapper.rejected(1, a)).as("ActionResult.%s", a).doesNotThrowAnyException();
        }
        for (EndReason e : EndReason.values()) {
            assertThatCode(() -> ProtoMapper.event(new MatchEnded(Role.CAT, e, 0, 0)))
                    .as("EndReason.%s", e).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("값 번호가 아니라 이름으로 맞춰짐 (예: 도메인 CAT → proto ROLE_CAT)")
    void mapsByName() {
        assertThat(ProtoMapper.role(Role.CAT)).isEqualTo(com.catchnyang.proto.Role.ROLE_CAT);
        assertThat(ProtoMapper.phase(Phase.PLAYING)).isEqualTo(com.catchnyang.proto.Phase.PHASE_PLAYING);
        assertThat(ProtoMapper.tool(null)).isEqualTo(com.catchnyang.proto.ToolType.TOOL_TYPE_UNSPECIFIED);
        assertThat(ProtoMapper.rejected(7, ActionResult.COOLDOWN).getReason())
                .isEqualTo(com.catchnyang.proto.RejectReason.REJECT_REASON_COOLDOWN);
    }
}

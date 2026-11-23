package com.catchnyang.ws;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LobbyTest {

    @Test
    @DisplayName("닉네임: 앞뒤 공백 제거, 1~20자 (한글·이모지도 한 글자), 제어문자 거절")
    void nickname() {
        assertThat(Lobby.validNickname("  리트냥이 ")).isEqualTo("리트냥이");
        assertThat(Lobby.validNickname("")).isNull();
        assertThat(Lobby.validNickname("   ")).isNull();
        assertThat(Lobby.validNickname(null)).isNull();
        assertThat(Lobby.validNickname("가".repeat(20))).isNotNull();
        assertThat(Lobby.validNickname("가".repeat(21))).isNull();
        assertThat(Lobby.validNickname("😺".repeat(20))).isNotNull();
        assertThat(Lobby.validNickname("a\u0000b")).isNull();
    }
}

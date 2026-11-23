package com.catchnyang.game.config;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

public final class GameConfigLoader {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES) // 오타 난 키를 조용히 무시하지 않도록
            .build();

    private GameConfigLoader() {}

    public static GameConfig load(InputStream in) {
        return MAPPER.readValue(in, GameConfig.class);
    }

    /** 테스트·싱글 도구용: classpath 의 game-config.json 을 바로 읽음 */
    public static GameConfig loadDefault() {
        try (InputStream in = GameConfigLoader.class.getResourceAsStream("/game-config.json")) {
            if (in == null) throw new IllegalStateException("classpath 에 game-config.json 없음");
            return load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

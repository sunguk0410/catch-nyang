package com.catchnyang.game;

import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 테스트용: 저장소의 protocol/game-config.json (서버·브라우저가 쓰는 바로 그 파일) */
public final class TestConfig {

    private TestConfig() {}

    public static final String JSON = read();
    public static final GameConfig CFG = GameConfigJson.parseConfig(JSON);

    private static String read() {
        try {
            return Files.readString(Path.of("../protocol/game-config.json"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

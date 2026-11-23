package com.catchnyang.ai;

import com.catchnyang.game.TestConfig;
import com.catchnyang.room.CommandJson;
import com.catchnyang.room.GameCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 기준 기록(rules/src/test/resources/conformance)과 JVM 결과가 같은지.
 * web 쪽 테스트는 JS 로 변환된 rules 로 같은 기록을 만들어 같은 파일과 비교한다 → JVM ≡ JS.
 * <p>
 * 규칙·봇을 일부러 바꿨으면 기준을 다시 만든다: ./gradlew :rules:test -PupdateConformance
 */
class ConformanceTest {

    /** web/src/local/conformance.test.ts 와 같은 seed 목록 */
    static final int[] SEEDS = {1, 42, 2026};
    static final Path DIR = Path.of("src/test/resources/conformance");

    @Test
    @DisplayName("봇끼리 한 판의 기록이 기준 파일과 같음")
    void matchesGolden() throws Exception {
        boolean update = Boolean.getBoolean("updateConformance");
        Files.createDirectories(DIR);
        for (int seed : SEEDS) {
            String log = Conformance.run(TestConfig.CFG, seed);
            Path f = DIR.resolve("seed-" + seed + ".txt");
            if (update || !Files.exists(f)) {
                Files.writeString(f, log, StandardCharsets.UTF_8);
                continue;
            }
            assertThat(log).as("seed " + seed + " (의도한 변경이면 -PupdateConformance 로 기준 갱신)")
                    .isEqualTo(Files.readString(f, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("클라 입력 JSON → 명령 (proto3 JSON 은 0 을 생략함)")
    void commandJson() {
        assertThat(CommandJson.parse("{\"seq\":3,\"possess\":{\"seat\":4}}")).containsExactly(new GameCommand.Possess(4));
        assertThat(CommandJson.parse("{\"possess\":{}}")).containsExactly(new GameCommand.Possess(0));
        assertThat(CommandJson.parse("{\"useTool\":{\"questions\":[1,5,9]}}")).containsExactly(new GameCommand.UseTool(List.of(1, 5, 9)));
        assertThat(CommandJson.parse("{\"proctorMove\":{\"dx\":1,\"faceZ\":-1}}"))
                .containsExactly(new GameCommand.Move(1, 0), new GameCommand.Look(0, -1));
        assertThat(CommandJson.parse("{\"proctorMove\":{}}")).containsExactly(new GameCommand.Move(0, 0));
        assertThat(CommandJson.parse("{\"swat\":{}}")).containsExactly(new GameCommand.Swat());
        assertThat(CommandJson.parse("{\"leaveMatch\":{}}")).containsExactly(new GameCommand.Leave());
        assertThat(CommandJson.parse("{\"joinQueue\":{\"nickname\":\"a\"}}")).isEmpty();
    }
}

package com.catchnyang.ai;

import com.catchnyang.game.EndReason;
import com.catchnyang.game.Match;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.TestConfig;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.GameCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 봇끼리 여러 판 → 봇이 실제로 게임을 하는지(양쪽 다 이길 수 있는지) + 밸런스 수치 */
class BotSimulationTest {

    static final GameConfig CFG = TestConfig.CFG;
    static final int MAX_TICKS = (CFG.match().prepareSec() + CFG.match().durationSec() + 5) * CFG.server().tickHz();

    static Match play(int seed, Role human) {
        LocalMatch m = new LocalMatch(CFG, seed, human);
        for (int t = 0; t < MAX_TICKS && !m.isFinished(); t++) m.tick();
        return m.match();
    }

    @Test
    @DisplayName("봇 대 봇 200판: 모두 끝나고, 양쪽 다 이길 수 있음")
    void botsPlay() {
        int n = 200;
        Map<Role, Integer> wins = new EnumMap<>(Role.class);
        Map<EndReason, Integer> reasons = new EnumMap<>(EndReason.class);
        double avgSum = 0, targetSum = 0;
        int swats = 0, catches = 0, detects = 0;
        long t0 = System.nanoTime();
        for (int seed = 1; seed <= n; seed++) {
            Match m = play(seed, null);
            assertThat(m.phase()).isEqualTo(Phase.ENDED);
            wins.merge(m.winner(), 1, Integer::sum);
            reasons.merge(m.endReason(), 1, Integer::sum);
            avgSum += m.classAverage();
            targetSum += m.target();
            swats += CFG.proctor().swatter().maxAttempts() - m.proctor().swatsLeft();
            catches += m.proctor().catchSuccesses();
            detects += CFG.proctor().detector().maxUses() - m.proctor().detectorsLeft();
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("%n=== 봇 대 봇 %d판 (%.0f ms, 판당 %.1f ms) ===%n", n, ms, ms / n);
        System.out.printf("승리: 냥이 %d / 감독관 %d%n", wins.getOrDefault(Role.CAT, 0), wins.getOrDefault(Role.PROCTOR, 0));
        System.out.printf("끝난 이유: %s%n", reasons);
        System.out.printf("평균 최종 평균 %.1f, 평균 최종 목표 %.1f%n", avgSum / n, targetSum / n);
        System.out.printf("판당 파리채 %.2f (성공 %.2f), 탐지기 %.2f%n", swats / (double) n, catches / (double) n, detects / (double) n);

        assertThat(wins.getOrDefault(Role.CAT, 0)).isPositive();
        assertThat(wins.getOrDefault(Role.PROCTOR, 0)).isPositive();
        assertThat(swats).isPositive();
        assertThat(detects).isPositive();
    }

    @Test
    @DisplayName("같은 seed 면 같은 판 (싱글 모드·JS 비교의 전제)")
    void deterministic() {
        for (int seed : new int[]{3, 77, 1234}) {
            Match a = play(seed, null);
            Match b = play(seed, null);
            assertThat(a.winner()).isEqualTo(b.winner());
            assertThat(a.now()).isEqualTo(b.now());
            assertThat(a.classAverage()).isEqualTo(b.classAverage());
            assertThat(a.target()).isEqualTo(b.target());
            assertThat(a.proctor().position()).isEqualTo(b.proctor().position());
        }
    }

    @Test
    @DisplayName("사람 냥이가 아무것도 안 하면 AI 감독관이 이김 (평균이 목표에 못 미침)")
    void idleHumanCatLoses() {
        Match m = play(5, Role.CAT);
        assertThat(m.winner()).isEqualTo(Role.PROCTOR);
    }

    @Test
    @DisplayName("사람 감독관이 아무것도 안 하면 AI 냥이가 평균을 올려 이김")
    void idleHumanProctorLoses() {
        int catWins = 0;
        for (int seed = 1; seed <= 10; seed++) if (play(seed, Role.PROCTOR).winner() == Role.CAT) catWins++;
        assertThat(catWins).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("사람 입력도 받음 (기권하면 바로 끝)")
    void humanInput() {
        LocalMatch m = new LocalMatch(CFG, 9, Role.PROCTOR);
        m.tick();
        m.submit(1, new GameCommand.Leave());
        m.tick();
        assertThat(m.isFinished()).isTrue();
        assertThat(m.match().winner()).isEqualTo(Role.CAT);
    }
}

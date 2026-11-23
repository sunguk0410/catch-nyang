package com.catchnyang.room;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.GameEvent;
import com.catchnyang.game.GameEvent.*;
import com.catchnyang.game.Match;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.Student;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigLoader;
import com.catchnyang.room.GameCommand.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class GameRoomTest {

    static final GameConfig CFG = GameConfigLoader.loadDefault();
    static final long TICK = 1000L / CFG.server().tickHz();

    /** 받은 걸 전부 기록하는 가짜 채널 */
    static final class Recorder implements PlayerChannel {
        final List<Outbound> received = new ArrayList<>();

        @Override
        public void send(List<Outbound> batch) {
            received.addAll(batch);
        }

        List<GameEvent> events() {
            return received.stream()
                    .filter(o -> o instanceof Outbound.Event)
                    .map(o -> ((Outbound.Event) o).event())
                    .toList();
        }

        List<Outbound.Rejected> rejected() {
            return received.stream()
                    .filter(o -> o instanceof Outbound.Rejected)
                    .map(o -> (Outbound.Rejected) o)
                    .toList();
        }

        void clear() {
            received.clear();
        }
    }

    Recorder cat;
    Recorder proctor;
    GameRoom room;
    Match match;

    @BeforeEach
    void setUp() {
        cat = new Recorder();
        proctor = new Recorder();
        match = new Match(CFG, new Random(7));
        room = new GameRoom("test", match, CFG, cat, proctor);
    }

    void ticks(long ms) {
        for (long t = 0; t < ms; t += TICK) room.tick();
    }

    void startPlaying() {
        ticks(CFG.match().prepareSec() * 1000L);
        assertThat(match.phase()).isEqualTo(Phase.PLAYING);
        cat.clear();
        proctor.clear();
    }

    int wrongQuestionOfPossessed() {
        Student s = match.students().get(match.cat().seat());
        for (int q = 0; q < CFG.match().questionCount(); q++) {
            if (s.effectiveAnswer(q) != match.answer(q)) return q;
        }
        throw new IllegalStateException("틀린 문항 없음");
    }

    @Test
    @DisplayName("입력은 큐에 들어갔다가 다음 tick 에 적용됨")
    void inputAppliedOnTick() {
        startPlaying();
        int to = (match.cat().seat() + 1) % 9;
        room.submit(Role.CAT, new Possess(to));
        assertThat(match.cat().seat()).isNotEqualTo(to);
        room.tick();
        assertThat(match.cat().seat()).isEqualTo(to);
        assertThat(cat.events()).contains(new Possessed(to));
        assertThat(proctor.events()).contains(new Possessed(to)); // 움찔은 감독관도 봄
    }

    @Test
    @DisplayName("거절된 입력은 보낸 사람에게만 이유와 함께 돌아감")
    void rejectedGoesToSenderOnly() {
        startPlaying();
        room.submit(Role.CAT, new Remark(0));           // 확인 안 한 문항
        room.submit(Role.PROCTOR, new Possess(0));      // 다른 역할의 행동
        room.tick();
        assertThat(cat.rejected()).extracting(Outbound.Rejected::reason).containsExactly(ActionResult.NOT_KNOWN);
        assertThat(proctor.rejected()).extracting(Outbound.Rejected::reason).containsExactly(ActionResult.INVALID);
    }

    @Test
    @DisplayName("도구로 알아낸 정답은 냥이만 받음")
    void toolAnswerOnlyToCat() {
        startPlaying();
        int q = wrongQuestionOfPossessed();
        room.submit(Role.CAT, new UseTool(List.of(q)));
        ticks(5000);
        assertThat(cat.events()).anyMatch(e -> e instanceof ToolFinished f && f.revealed().containsKey(q));
        assertThat(proctor.events()).noneMatch(e -> e instanceof ToolFinished);
    }

    @Test
    @DisplayName("고치는 손동작: 감독관에겐 누가 고쳤는지·몇 번인지 숨김")
    void remarkSanitizedForProctor() {
        startPlaying();
        int seat = match.cat().seat();
        int q = wrongQuestionOfPossessed();
        room.submit(Role.CAT, new UseTool(List.of(q)));
        ticks(5000);
        room.submit(Role.CAT, new Remark(q));
        room.tick();
        assertThat(cat.events()).contains(new RemarkStarted(seat, q, true));
        assertThat(proctor.events()).contains(new RemarkStarted(seat, -1, false));
        assertThat(proctor.events()).noneMatch(e -> e instanceof RemarkStarted r && r.byCat());
    }

    @Test
    @DisplayName("10초 집계 평균은 감독관만, 냥이 튕김 자리는 냥이만")
    void roleOnlyEvents() {
        startPlaying();
        ticks(10_000);
        assertThat(proctor.events()).anyMatch(e -> e instanceof AverageRevealed);
        assertThat(cat.events()).noneMatch(e -> e instanceof AverageRevealed);
        assertThat(proctor.events()).noneMatch(e -> e instanceof CatRelocated || e instanceof ToolFinished);
    }

    @Test
    @DisplayName("OMR 변화: 냥이는 빙의한 학생 것만, 검사 안 하는 감독관은 아무것도 못 받음")
    void omrVisibility() {
        startPlaying();
        ticks(60_000);
        int seat = match.cat().seat();
        assertThat(cat.events()).filteredOn(e -> e instanceof OmrMarked)
                .allMatch(e -> ((OmrMarked) e).seat() == seat);
        assertThat(proctor.events()).noneMatch(e -> e instanceof OmrMarked);
    }

    @Test
    @DisplayName("나가면 남은 쪽 승리, 둘 다 결과를 받고 방이 끝남")
    void leaveEndsMatch() {
        startPlaying();
        room.submit(Role.PROCTOR, new Leave());
        room.tick();
        assertThat(room.isFinished()).isTrue();
        assertThat(match.winner()).isEqualTo(Role.CAT);
        assertThat(cat.events()).anyMatch(e -> e instanceof MatchEnded);
        assertThat(proctor.events()).anyMatch(e -> e instanceof MatchEnded);

        cat.clear();
        room.tick();  // 끝난 방은 더 아무것도 안 함
        assertThat(cat.received).isEmpty();
    }
}

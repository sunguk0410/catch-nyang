package com.catchnyang.game;

import com.catchnyang.game.GameEvent.*;
import com.catchnyang.game.config.GameConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class MatchTest {

    static final GameConfig CFG = TestConfig.CFG;
    static final long TICK = 1000L / CFG.server().tickHz();

    Match match;

    @BeforeEach
    void setUp() {
        match = new Match(CFG, new Random(42));
    }

    // ---------- helpers ----------

    void advance(long ms) {
        for (long t = 0; t < ms; t += TICK) match.tick(TICK);
    }

    void startPlaying() {
        advance(CFG.match().prepareSec() * 1000L);
        assertThat(match.phase()).isEqualTo(Phase.PLAYING);
        match.drainEvents();
    }

    /** 빙의 중인 학생의 OMR(유효 답)이 정답과 다른 문항들 */
    List<Integer> wrongQuestionsOfPossessed() {
        Student s = match.students().get(match.cat().seat());
        List<Integer> out = new ArrayList<>();
        for (int q = 0; q < CFG.match().questionCount(); q++) {
            if (s.effectiveAnswer(q) != match.answer(q)) out.add(q);
        }
        return out;
    }

    /** 빙의 중인 학생의 도구를 고정 (랜덤 배정이라 테스트에선 직접 지정) */
    Student equip(ToolType tool) {
        Student s = match.students().get(match.cat().seat());
        s.tool = tool;
        s.toolChecksLeft = CFG.tools().get(tool).totalChecks();
        return s;
    }

    void standInFrontOf(int seat) {
        match.teleportProctor(match.seatPosition(seat));
    }

    int otherSeat(int seat) {
        return (seat + 1) % CFG.match().studentCount();
    }

    // ---------- 준비 / 시작 ----------

    @Test
    @DisplayName("config 를 읽고 시작 평균은 시험지 답 기준 (약 60점대)")
    void initialState() {
        assertThat(match.phase()).isEqualTo(Phase.PREPARE);
        assertThat(match.target()).isEqualTo(70);
        assertThat(match.students()).hasSize(9);
        assertThat(match.classAverage()).isBetween(40.0, 80.0);
    }

    @Test
    @DisplayName("학생마다 활성화된 도구 중 하나를 랜덤으로 지참 (이어폰은 비활성)")
    void studentsCarryRandomTool() {
        for (Student st : match.students()) {
            assertThat(st.tool()).isNotNull().isNotEqualTo(ToolType.WIRELESS_EARPHONE);
            assertThat(st.toolChecksLeft()).isEqualTo(CFG.tools().get(st.tool()).totalChecks());
        }
        advance(CFG.match().prepareSec() * 1000L);
        assertThat(match.phase()).isEqualTo(Phase.PLAYING);
        assertThat(match.cat().seat()).isBetween(0, 8);
    }

    @Test
    @DisplayName("준비 단계에선 플레이 행동 불가")
    void noActionsDuringPrepare() {
        assertThat(match.catPossess(0)).isEqualTo(ActionResult.WRONG_PHASE);
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.WRONG_PHASE);
    }

    // ---------- 학생 ----------

    @Test
    @DisplayName("빙의 안 된 학생은 4분 안에 OMR 을 다 옮겨 적고, 빙의된 학생은 멈춤")
    void studentsTranscribe() {
        startPlaying();
        int possessed = match.cat().seat();
        advance(230_000);
        for (Student s : match.students()) {
            if (s.seat() == possessed) {
                assertThat(s.firstUnmarked()).isZero();
            } else {
                assertThat(s.firstUnmarked()).as("seat %d", s.seat()).isEqualTo(-1);
            }
        }
    }

    // ---------- 냥이 ----------

    @Test
    @DisplayName("빙의 이동은 쿨타임 3초")
    void possessCooldown() {
        startPlaying();
        int from = match.cat().seat();
        int to = otherSeat(from);
        assertThat(match.catPossess(from)).isEqualTo(ActionResult.INVALID);
        assertThat(match.catPossess(to)).isEqualTo(ActionResult.OK);
        assertThat(match.drainEvents()).contains(new Possessed(to));
        assertThat(match.catPossess(from)).isEqualTo(ActionResult.COOLDOWN);
        advance(3000);
        assertThat(match.catPossess(from)).isEqualTo(ActionResult.OK);
    }

    @Test
    @DisplayName("도구 사용 중엔 고정 시간 동안 아무것도 못 하고, 끝나야 정답을 앎")
    void toolLock() {
        startPlaying();
        Student s = equip(ToolType.AI_GLASSES);
        assertThat(match.catUseTool(List.of(0, 1, 2, 3))).isEqualTo(ActionResult.INVALID); // 1회 3문항까지
        assertThat(match.catUseTool(List.of(0, 1, 2))).isEqualTo(ActionResult.OK);
        assertThat(s.toolChecksLeft()).isEqualTo(6);

        assertThat(match.catPossess(otherSeat(match.cat().seat()))).isEqualTo(ActionResult.BUSY);
        advance(3900);
        assertThat(match.cat().knownQuestions()).isEmpty();
        advance(200);
        assertThat(match.cat().knownQuestions()).containsExactly(0, 1, 2);
        assertThat(match.drainEvents()).anyMatch(e -> e instanceof ToolFinished f && f.revealed().size() == 3);

        assertThat(match.catUseTool(List.of(0))).isEqualTo(ActionResult.INVALID); // 이미 아는 문항
    }

    @Test
    @DisplayName("확인한 문항만 고칠 수 있고, 1.5초 뒤 OMR 이 정답이 되며 점수가 오름")
    void remark() {
        startPlaying();
        Student s = match.students().get(match.cat().seat());
        int q = wrongQuestionsOfPossessed().getFirst();
        int before = s.score(answerKeyOf(match), 5);

        assertThat(match.catRemark(q)).isEqualTo(ActionResult.NOT_KNOWN);
        equip(ToolType.SMART_WATCH);
        match.catUseTool(List.of(q));
        advance(3000);
        assertThat(match.catRemark(q)).isEqualTo(ActionResult.OK);
        assertThat(match.drainEvents()).contains(new RemarkStarted(s.seat(), q, true));
        advance(1500);

        assertThat(s.omrAnswer(q)).isEqualTo(match.answer(q));
        assertThat(s.score(answerKeyOf(match), 5)).isEqualTo(before + 5);
        assertThat(match.catRemark(q)).isEqualTo(ActionResult.NO_CHANGE);
    }

    @Test
    @DisplayName("감독관 평균은 10초마다만 갱신 — 냥이가 고친 순간엔 안 바뀜")
    void proctorAverageIsDelayed() {
        startPlaying();
        double revealedAtStart = match.proctorVisibleAverage();
        assertThat(revealedAtStart).isEqualTo(match.classAverage());

        int q = wrongQuestionsOfPossessed().getFirst();
        equip(ToolType.SMART_WATCH);
        match.catUseTool(List.of(q));
        advance(3000);
        match.catRemark(q);
        advance(1500);  // 시작 후 4.5초: 실시간 평균은 바뀌었지만 감독관 화면은 그대로
        assertThat(match.proctorVisibleAverage()).isEqualTo(revealedAtStart);

        match.drainEvents();
        advance(5500);  // 시작 후 10초: 집계
        assertThat(match.proctorVisibleAverage()).isEqualTo(match.classAverage());
        assertThat(match.drainEvents()).filteredOn(e -> e instanceof AverageRevealed).hasSize(1);
    }

    // ---------- 감독관 ----------

    @Test
    @DisplayName("이동 속도는 config 값, 교실 밖으로 못 나감")
    void proctorMove() {
        startPlaying();
        Vec2 start = match.proctor().position();
        match.proctorMove(1, 0);
        advance(1000);
        assertThat(match.proctor().position().x()).isCloseTo(start.x() + 3.0, org.assertj.core.data.Offset.offset(0.01));
        advance(10_000);
        assertThat(match.proctor().position().x()).isEqualTo(CFG.classroom().halfWidthM());
    }

    @Test
    @DisplayName("책상 블록은 못 뚫고 앞에서 멈춤 — 멈춘 자리에서 검사는 가능")
    void desksBlockMovement() {
        startPlaying();
        // 교탁(0, 4)에서 학생 쪽(-z)으로 직진 → 앞줄 가운데 자리(서버 자리 7, (0, 2.2)) 책상 앞에서 멈춤
        match.proctorMove(0, -1);
        advance(2000);
        var room = CFG.classroom();
        double stopZ = 2.2 + room.deskFrontM() + room.proctorRadiusM();
        assertThat(match.proctor().position().z()).isCloseTo(stopZ, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(match.proctor().position().x()).isEqualTo(0.0);
        advance(1000);
        assertThat(match.proctor().inspectingSeat()).isEqualTo(7);
    }

    @Test
    @DisplayName("통로로는 지나갈 수 있음")
    void aislesAreWalkable() {
        startPlaying();
        match.teleportProctor(new Vec2(1.1, 4));  // 가운데 줄과 오른쪽 줄 사이 통로
        match.proctorMove(0, -1);
        advance(4000);
        assertThat(match.proctor().position().z()).isEqualTo(-CFG.classroom().halfDepthM());
    }

    @Test
    @DisplayName("학생 앞에 2초 서 있으면 검사 가능")
    void inspect() {
        startPlaying();
        standInFrontOf(4);
        advance(1900);
        assertThat(match.proctor().inspectingSeat()).isEqualTo(-1);
        advance(200);
        assertThat(match.proctor().inspectingSeat()).isEqualTo(4);
        assertThat(match.drainEvents()).contains(new InspectReady(4));
    }

    @Test
    @DisplayName("학생 앞이 아니면 파리채 못 씀 (횟수도 안 줄어듦)")
    void swatOutOfRange() {
        startPlaying();
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.OUT_OF_RANGE);
        assertThat(match.proctor().swatsLeft()).isEqualTo(3);
    }

    @Test
    @DisplayName("적발 성공: 목표 +10, 고친 답 원복, 냥이 5초 기절 후 랜덤 자리로")
    void swatSuccess() {
        startPlaying();
        int seat = match.cat().seat();
        Student s = match.students().get(seat);
        int q = wrongQuestionsOfPossessed().getFirst();
        Boolean original = s.omrAnswer(q);
        equip(ToolType.SMART_WATCH);
        match.catUseTool(List.of(q));
        advance(3000);
        match.catRemark(q);
        advance(1500);
        assertThat(s.omrAnswer(q)).isEqualTo(match.answer(q));

        standInFrontOf(seat);
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.OK);
        assertThat(match.target()).isEqualTo(80);
        assertThat(s.omrAnswer(q)).isEqualTo(original);
        assertThat(match.cat().isStunned()).isTrue();
        assertThat(match.catPossess(otherSeat(seat))).isEqualTo(ActionResult.STUNNED);

        advance(5000);
        assertThat(match.cat().isStunned()).isFalse();
        assertThat(match.cat().seat()).isBetween(0, 8);
        assertThat(match.drainEvents()).anyMatch(e -> e instanceof CatRelocated);
    }

    @Test
    @DisplayName("적발 실패: 목표 -5, 감독관 3초 경직")
    void swatFail() {
        startPlaying();
        standInFrontOf(otherSeat(match.cat().seat()));
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.OK);
        assertThat(match.target()).isEqualTo(65);
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.STUNNED);
        advance(3000);
        assertThat(match.proctorSwat()).isEqualTo(ActionResult.OK);
        assertThat(match.proctor().swatsLeft()).isEqualTo(1);
    }

    @Test
    @DisplayName("적발 3회 성공하면 감독관 즉시 승리")
    void threeCatchesWin() {
        startPlaying();
        for (int i = 0; i < 3; i++) {
            standInFrontOf(match.cat().seat());
            assertThat(match.proctorSwat()).isEqualTo(ActionResult.OK);
            if (i < 2) advance(5000);
        }
        assertThat(match.phase()).isEqualTo(Phase.ENDED);
        assertThat(match.winner()).isEqualTo(Role.PROCTOR);
        assertThat(match.endReason()).isEqualTo(EndReason.CATCH_3);
    }

    @Test
    @DisplayName("탐지기: 도구 사용 중이면 파괴 + 위치 노출, 아니면 목표 -3")
    void detector() {
        startPlaying();
        assertThat(match.proctorDetect()).isEqualTo(ActionResult.OK);
        assertThat(match.target()).isEqualTo(67);

        int seat = match.cat().seat();
        Student s = equip(ToolType.AI_GLASSES);
        match.catUseTool(List.of(0, 1, 2));
        match.drainEvents();
        assertThat(match.proctorDetect()).isEqualTo(ActionResult.OK);
        assertThat(match.drainEvents()).contains(new ToolDestroyed(ToolType.AI_GLASSES, seat), new DetectorResult(true, seat));
        assertThat(s.tool()).isNull();  // 그 학생 도구는 사라짐
        advance(5000);
        assertThat(match.cat().knownQuestions()).isEmpty(); // 파괴돼서 정답 못 얻음
        assertThat(match.catUseTool(List.of(3))).isEqualTo(ActionResult.NO_TOOL);

        match.proctorDetect();
        assertThat(match.proctorDetect()).isEqualTo(ActionResult.NO_USES_LEFT);
    }

    // ---------- 종료 ----------

    @Test
    @DisplayName("시간 종료 시 평균 >= 목표면 냥이 승")
    void timeUpCatWins() {
        startPlaying();
        match.setTarget(0);
        advance(CFG.match().durationSec() * 1000L);
        assertThat(match.winner()).isEqualTo(Role.CAT);
        assertThat(match.endReason()).isEqualTo(EndReason.AVG_REACHED);
    }

    @Test
    @DisplayName("시간 종료 시 평균 < 목표면 감독관 승")
    void timeUpProctorWins() {
        startPlaying();
        match.setTarget(101);
        advance(CFG.match().durationSec() * 1000L);
        assertThat(match.winner()).isEqualTo(Role.PROCTOR);
        assertThat(match.endReason()).isEqualTo(EndReason.AVG_FAILED);
        assertThat(match.drainEvents()).anyMatch(e -> e instanceof MatchEnded);
    }

    @Test
    @DisplayName("한쪽이 나가면 남은 쪽 승리")
    void forfeit() {
        startPlaying();
        match.forfeit(Role.CAT);
        assertThat(match.winner()).isEqualTo(Role.PROCTOR);
        assertThat(match.endReason()).isEqualTo(EndReason.DISCONNECT);
    }

    static boolean[] answerKeyOf(Match m) {
        boolean[] key = new boolean[CFG.match().questionCount()];
        for (int q = 0; q < key.length; q++) key[q] = m.answer(q);
        return key;
    }
}

package com.catchnyang.room;

import com.catchnyang.game.GameEvent.Possessed;
import com.catchnyang.game.GameEvent.ToolStarted;
import com.catchnyang.game.Match;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Role;
import com.catchnyang.game.ToolType;
import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.game.config.GameConfigLoader;
import com.catchnyang.room.GameCommand.*;
import com.catchnyang.room.GameRoomTest.Recorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/** 4번: 방이 내보내는 묶음 (Clock·스냅샷·학생 상세·감독관 위치·시야 필터) */
class RoomProtocolTest {

    static final GameConfig CFG = GameConfigLoader.loadDefault();
    static final long TICK = 1000L / CFG.server().tickHz();

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

    <T> List<T> of(Recorder r, Class<T> type) {
        return r.received.stream().filter(type::isInstance).map(type::cast).toList();
    }

    void startPlaying() {
        ticks(CFG.match().prepareSec() * 1000L);
        assertThat(match.phase()).isEqualTo(Phase.PLAYING);
        cat.clear();
        proctor.clear();
    }

    @Test
    @DisplayName("첫 tick: 둘 다 Clock + 자기 역할 스냅샷만 받음 (상대 비밀 없음)")
    void firstTickSnapshot() {
        room.tick();
        assertThat(cat.received.getFirst()).isInstanceOf(Outbound.Clock.class);
        RoleSnapshot cs = of(cat, Outbound.Snapshot.class).getFirst().snapshot();
        RoleSnapshot ps = of(proctor, Outbound.Snapshot.class).getFirst().snapshot();

        assertThat(cs.role()).isEqualTo(Role.CAT);
        assertThat(cs.cat()).isNotNull();
        assertThat(cs.proctor()).isNull();
        assertThat(cs.seats()).hasSize(9);

        assertThat(ps.role()).isEqualTo(Role.PROCTOR);
        assertThat(ps.cat()).isNull();          // 냥이 자리·확인한 정답·도구 전부 안 감
        assertThat(ps.proctor()).isNotNull();
        assertThat(ps.proctor().swatsLeft()).isEqualTo(CFG.proctor().swatter().maxAttempts());

        cat.clear();
        room.tick();
        assertThat(of(cat, Outbound.Snapshot.class)).isEmpty();  // 한 번만
    }

    @Test
    @DisplayName("재접속 요청하면 그 역할에게만 스냅샷 다시 보냄")
    void requestSnapshot() {
        room.tick();
        cat.clear();
        proctor.clear();
        room.requestSnapshot(Role.PROCTOR);
        room.tick();
        assertThat(of(proctor, Outbound.Snapshot.class)).hasSize(1);
        assertThat(of(cat, Outbound.Snapshot.class)).isEmpty();
    }

    @Test
    @DisplayName("시작하면 냥이는 첫 빙의 자리와 그 학생 시험지·OMR 을 받고, 감독관은 못 받음")
    void startGivesCatItsSeat() {
        ticks(CFG.match().prepareSec() * 1000L);
        int seat = match.cat().seat();
        assertThat(of(cat, Outbound.StudentDetail.class)).extracting(d -> d.info().seat()).contains(seat);
        assertThat(of(cat, Outbound.StudentDetail.class).getFirst().info().tool()).isNotNull();
        assertThat(of(proctor, Outbound.StudentDetail.class)).isEmpty();
    }

    @Test
    @DisplayName("빙의 이동: 냥이에게 새 학생 상세, ack 는 처리한 seq")
    void possessDetailAndAck() {
        startPlaying();
        int to = (match.cat().seat() + 1) % 9;
        room.submit(Role.CAT, 5, new Possess(to));
        room.tick();
        assertThat(of(cat, Outbound.StudentDetail.class)).extracting(d -> d.info().seat()).containsExactly(to);
        assertThat(of(cat, Outbound.Clock.class).getFirst().ackSeq()).isEqualTo(5);
        assertThat(cat.events()).contains(new Possessed(to));
        assertThat(of(proctor, Outbound.StudentDetail.class)).isEmpty();
    }

    @Test
    @DisplayName("거절에는 입력 seq 가 붙음")
    void rejectedCarriesSeq() {
        startPlaying();
        room.submit(Role.CAT, 9, new Remark(0));
        room.tick();
        assertThat(cat.rejected()).extracting(Outbound.Rejected::seq).containsExactly(9);
    }

    @Test
    @DisplayName("감독관이 움직이면 둘 다 위치를 받고, 멈추면 안 보냄")
    void proctorStateOnlyWhenChanged() {
        startPlaying();
        room.submit(Role.PROCTOR, 1, new Move(1, 0));
        room.tick();
        assertThat(of(cat, Outbound.ProctorState.class)).hasSize(1);
        assertThat(of(proctor, Outbound.ProctorState.class)).hasSize(1);

        room.submit(Role.PROCTOR, 2, new Move(0, 0));
        room.tick();
        cat.clear();
        room.tick();
        assertThat(of(cat, Outbound.ProctorState.class)).isEmpty();
    }

    @Test
    @DisplayName("보낼 게 없어도 1초에 한 번은 Clock (시간 동기화)")
    void clockEverySecond() {
        startPlaying();
        ticks(3000);
        assertThat(of(proctor, Outbound.Clock.class).size()).isBetween(3, 10);
    }

    @Test
    @DisplayName("실시간 평균은 냥이만")
    void liveAverageCatOnly() {
        ticks(CFG.match().prepareSec() * 1000L + 30_000);
        assertThat(of(cat, Outbound.LiveAverage.class)).isNotEmpty();
        assertThat(of(proctor, Outbound.LiveAverage.class)).isEmpty();
    }

    @Test
    @DisplayName("통계: 빙의·거절 안 된 행동만 셈")
    void stats() {
        startPlaying();
        room.submit(Role.CAT, new Possess((match.cat().seat() + 1) % 9));
        room.submit(Role.PROCTOR, new Detect());
        ticks(200);
        assertThat(room.stats().of(Role.CAT)).containsEntry("possessions", 1);
        assertThat(room.stats().of(Role.PROCTOR)).containsEntry("detectorUses", 1).containsEntry("detectorHits", 0);
    }

    // ---------- 시야 ----------

    @Test
    @DisplayName("시야각: 앞은 보이고 등 뒤는 안 보임, 바로 붙어 있으면 보임")
    void inFov() {
        Vec2 eye = new Vec2(0, 0);
        Vec2 forward = new Vec2(0, -1);
        assertThat(EventFilter.inFov(eye, forward, new Vec2(0, -3), 90)).isTrue();
        assertThat(EventFilter.inFov(eye, forward, new Vec2(2.9, -3), 90)).isTrue();   // 44도
        assertThat(EventFilter.inFov(eye, forward, new Vec2(3.2, -3), 90)).isFalse();  // 47도
        assertThat(EventFilter.inFov(eye, forward, new Vec2(0, 3), 90)).isFalse();
        assertThat(EventFilter.inFov(eye, forward, new Vec2(0, 0.05), 90)).isTrue();
    }

    @Test
    @DisplayName("AI 안경: 시야 안이면 거리 무관, 등 돌리면 안 보임")
    void aiGlassesNeedsFov() {
        startPlaying();
        int seat = 0;  // 맨 앞줄 왼쪽 (z = -2.2)
        var e = new ToolStarted(ToolType.AI_GLASSES, seat);
        // 기본 시선 -z, 교탁(z=4) 에서 보면 보임
        assertThat(EventFilter.proctorCanSeeTool(e, match, CFG)).isTrue();
        room.submit(Role.PROCTOR, new Look(0, 1));  // 뒤돌아 봄
        room.tick();
        assertThat(EventFilter.proctorCanSeeTool(e, match, CFG)).isFalse();
    }

    @Test
    @DisplayName("지우개 카메라: 시야 안이어도 1m 밖이면 안 보임")
    void eraserCameraRange() {
        startPlaying();
        var e = new ToolStarted(ToolType.ERASER_CAMERA, 4);  // 가운데 자리 (0,0)
        assertThat(EventFilter.proctorCanSeeTool(e, match, CFG)).isFalse();  // 교탁에서 4m
    }

    @Test
    @DisplayName("잘못된 시선 입력은 거절")
    void badLook() {
        startPlaying();
        room.submit(Role.PROCTOR, 3, new Look(0, 0));
        room.submit(Role.PROCTOR, 4, new Look(Double.NaN, 1));
        room.tick();
        assertThat(proctor.rejected()).extracting(Outbound.Rejected::seq).containsExactly(3, 4);
    }

    @Test
    @DisplayName("입력 도배는 상한에서 잘리지만 나가기는 항상 받음")
    void inputCap() {
        for (int i = 0; i < GameRoom.MAX_PENDING_INPUTS; i++) assertThat(room.submit(Role.PROCTOR, i + 1, new Swat())).isTrue();
        assertThat(room.submit(Role.PROCTOR, 999, new Swat())).isFalse();
        assertThat(room.submit(Role.PROCTOR, 1000, new Leave())).isTrue();
        room.tick();
        assertThat(room.isFinished()).isTrue();
    }
}

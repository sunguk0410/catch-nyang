package com.catchnyang.room;

import com.catchnyang.game.GameEvent;
import com.catchnyang.game.GameEvent.*;
import com.catchnyang.game.Match;
import com.catchnyang.game.Role;
import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;

import java.util.Optional;

/**
 * 역할별로 보낼 수 있는 사건만 남긴다 (시야 밖·상대 비밀 정보 미전송).
 * 여기서 새면 치트가 되므로 새 사건을 추가하면 반드시 여기도 정해야 함 (switch 가 강제).
 */
public final class EventFilter {

    private EventFilter() {}

    public static Optional<GameEvent> forRole(Role role, GameEvent event, Match match, GameConfig cfg) {
        boolean cat = role == Role.CAT;
        return switch (event) {
            // 둘 다 봄
            case PhaseChanged e -> Optional.of(e);
            case Possessed e -> Optional.of(e);          // 감독관에겐 "움찔" 단서
            case ToolDestroyed e -> Optional.of(e);
            case CatchResult e -> Optional.of(e);
            case DetectorResult e -> Optional.of(e);
            case TargetChanged e -> Optional.of(e);
            case MatchEnded e -> Optional.of(e);

            // 냥이만
            case ToolFinished e -> cat ? Optional.of(e) : Optional.empty();   // 알아낸 정답
            case CatRelocated e -> cat ? Optional.of(e) : Optional.empty();   // 튕겨 간 자리

            // 감독관만
            case InspectReady e -> cat ? Optional.empty() : Optional.of(e);
            case AverageRevealed e -> cat ? Optional.empty() : Optional.of(e); // 냥이는 실시간 평균을 스냅샷으로 받음

            // 감독관에겐 손동작만 (누가·몇 번인지는 숨김)
            case RemarkStarted e -> cat
                    ? Optional.of(e)
                    : Optional.of(new RemarkStarted(e.seat(), -1, false));

            // 감독관에겐 보이는 도구 이펙트만
            case ToolStarted e -> cat || proctorCanSeeTool(e, match, cfg) ? Optional.of(e) : Optional.empty();

            // OMR 내용: 냥이는 빙의한 학생 것만, 감독관은 검사 중인 학생 것만
            case OmrMarked e -> {
                int visibleSeat = cat ? match.cat().seat() : match.proctor().inspectingSeat();
                yield e.seat() == visibleSeat ? Optional.of(e) : Optional.empty();
            }
        };
    }

    /**
     * 도구 이펙트가 감독관에게 보이는지.
     * <ul>
     *   <li>detect=SOUND (무선 이어폰): 소리라서 시야와 무관, 거리만 본다.</li>
     *   <li>그 밖: 감독관 시야각(proctor.fovDeg) 안이어야 하고, visibleRangeM 이 있으면 그 거리 안이어야 함
     *       (AI 안경 = 시야 안이면 거리 무관).</li>
     * </ul>
     */
    static boolean proctorCanSeeTool(ToolStarted e, Match match, GameConfig cfg) {
        var spec = cfg.tools().get(e.tool());
        Vec2 seat = match.seatPosition(e.seat());
        Vec2 eye = match.proctor().position();
        Double range = spec.visibleRangeM();
        if (range != null && seat.dist(eye) > range) return false;
        if ("SOUND".equals(spec.detect())) return true;
        return inFov(eye, match.proctor().facing(), seat, cfg.proctor().fovDeg());
    }

    /** target 이 eye 에서 facing 기준 ±fov/2 안에 있는지. 바로 붙어 있으면(10cm 이내) 보이는 걸로 */
    static boolean inFov(Vec2 eye, Vec2 facing, Vec2 target, double fovDeg) {
        double tx = target.x() - eye.x();
        double tz = target.z() - eye.z();
        // hypot 대신 sqrt: JVM·JS 에서 결과가 비트 단위로 같아야 함 (sqrt 는 양쪽 다 IEEE 정확 반올림)
        double len = Math.sqrt(tx * tx + tz * tz);
        if (len < 0.1) return true;
        double fl = Math.sqrt(facing.x() * facing.x() + facing.z() * facing.z());
        double cos = (tx * facing.x() + tz * facing.z()) / (len * fl);
        return cos >= Math.cos(Math.toRadians(fovDeg / 2.0)) - 1e-9;
    }
}

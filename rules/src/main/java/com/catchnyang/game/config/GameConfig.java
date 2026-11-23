package com.catchnyang.game.config;

import com.catchnyang.game.ToolType;

import java.util.List;
import java.util.Map;

/** game-config.json 매핑. 수치는 전부 여기서만 읽는다. */
public record GameConfig(
        String version,
        Server server,
        MatchRules match,
        Classroom classroom,
        CatRules cat,
        Map<ToolType, ToolSpec> tools,
        ProctorRules proctor
) {
    public record Server(int tickHz) {}

    public record MatchRules(
            int durationSec,
            int prepareSec,
            int studentCount,
            int gridSize,
            int questionCount,
            int pointsPerQuestion,
            double initialAccuracyMin,
            double initialAccuracyMax,
            double studentMistakeRate,
            double transcribeIntervalSecMin,
            double transcribeIntervalSecMax,
            double mistakeFixDelaySecMin,
            double mistakeFixDelaySecMax,
            int initialTarget
    ) {}

    public record Classroom(
            double seatSpacingM,
            double interactRangeM,
            double halfWidthM,
            double halfDepthM,
            double proctorSpawnX,
            double proctorSpawnZ,
            // 자리마다 책상+의자+학생이 차지하는 직사각형 (자리 좌표 기준). 감독관은 못 지나감
            double deskHalfWidthM,  // 좌우 반폭
            double deskFrontM,      // 자리 좌표에서 칠판(+z) 쪽으로 (책상 앞 모서리)
            double deskBackM,       // 자리 좌표에서 뒤(-z) 쪽으로 (의자 등받이)
            double proctorRadiusM   // 감독관 몸 반지름
    ) {}

    public record CatRules(
            double possessCooldownSec,
            double remarkSec,
            double stunSec,
            Map<String, List<Integer>> lookRangeDeg,
            int fovDeg
    ) {}

    public record ToolSpec(
            int totalChecks,
            int perUse,
            double lockSec,
            String detect,
            Double visibleRangeM,
            Boolean enabled
    ) {
        public boolean isEnabled() {
            return enabled == null || enabled;
        }
    }

    public record ProctorRules(
            double moveSpeedMps,
            int fovDeg,                       // 감독관 시야각 (도구 이펙트가 보이는지 판정)
            double inspectHoldSec,
            double averageRevealIntervalSec,  // 감독관에게 보이는 반 평균 갱신 간격 (실시간이면 냥이 위치가 들킴)
            Swatter swatter,
            Detector detector
    ) {}

    /** 파리채 (적발) */
    public record Swatter(
            int maxAttempts,
            int winOnSuccessCount,
            int successTargetDelta,
            int failTargetDelta,
            double failStunSec
    ) {}

    /** 전파 탐지기 (알림) */
    public record Detector(
            int maxUses,
            double revealSec,
            int missTargetDelta
    ) {}
}

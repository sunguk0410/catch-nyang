package com.catchnyang.ai;

import com.catchnyang.game.Vec2;
import com.catchnyang.game.config.GameConfig;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 교실 길찾기 (감독관 봇용). 책상 블록 사이에는 세로 통로(열 사이)와 가로 통로(줄 사이)가 있다.
 * 경로: (지금 자리가 가로 통로가 아니면 세로로 가장 가까운 가로 통로까지) → 가로로 목표 근처 세로 통로까지
 * → 세로로 목표 가로 통로까지 → 목표.
 */
final class Nav {

    private final double[] bandZ;   // 가로 통로 중심 z (뒤 → 앞)
    private final double[] aisleX;  // 세로 통로 중심 x
    private final double[][] blockZ; // 줄마다 [minZ, maxZ]
    private final double halfW;
    private final double halfD;
    private final double[] rowZ;

    Nav(GameConfig cfg, Vec2[] seats) {
        var c = cfg.classroom();
        double r = c.proctorRadiusM();
        halfW = c.halfWidthM();
        halfD = c.halfDepthM();
        rowZ = distinctSorted(seats, false);
        double[] colX = distinctSorted(seats, true);
        blockZ = new double[rowZ.length][];
        for (int i = 0; i < rowZ.length; i++) {
            blockZ[i] = new double[]{rowZ[i] - c.deskBackM() - r, rowZ[i] + c.deskFrontM() + r};
        }
        // 가로 통로: 맨 뒤, 줄 사이, 맨 앞
        List<Double> bands = new ArrayList<>();
        bands.add((-halfD + blockZ[0][0]) / 2);
        for (int i = 0; i + 1 < rowZ.length; i++) bands.add((blockZ[i][1] + blockZ[i + 1][0]) / 2);
        bands.add((blockZ[rowZ.length - 1][1] + halfD) / 2);
        bandZ = toArray(bands);
        // 세로 통로: 맨 왼쪽, 열 사이, 맨 오른쪽
        double hw = c.deskHalfWidthM() + r;
        List<Double> aisles = new ArrayList<>();
        aisles.add((-halfW + colX[0] - hw) / 2);
        for (int i = 0; i + 1 < colX.length; i++) aisles.add((colX[i] + colX[i + 1]) / 2);
        aisles.add((colX[colX.length - 1] + hw + halfW) / 2);
        aisleX = toArray(aisles);
    }

    /** 그 학생을 검사하는 자리: 책상 바로 앞 가로 통로 */
    Vec2 inspectSpot(Vec2 seat) {
        double z = bandZ[0];
        for (double b : bandZ) {
            if (b > seat.z()) {
                z = b;
                break;
            }
        }
        return new Vec2(seat.x(), z);
    }

    List<Vec2> path(Vec2 from, Vec2 to) {
        List<Vec2> wp = new ArrayList<>();
        double z = from.z();
        if (!inBand(z)) {
            z = nearest(bandZ, z);
            wp.add(new Vec2(from.x(), z));
        }
        double ax = nearest(aisleX, to.x());
        if (Math.abs(to.z() - z) > 0.05) {
            wp.add(new Vec2(ax, z));
            wp.add(new Vec2(ax, to.z()));
        }
        wp.add(to);
        return wp;
    }

    /** 순찰용 아무 지점 (통로 위) */
    Vec2 randomAislePoint(com.catchnyang.game.SimpleRng rng) {
        return new Vec2(aisleX[rng.nextInt(aisleX.length)], bandZ[rng.nextInt(bandZ.length)]);
    }

    private boolean inBand(double z) {
        for (double[] b : blockZ) if (z > b[0] - 0.02 && z < b[1] + 0.02) return false;
        return true;
    }

    // 스트림은 브라우저 변환(TeaVM) 지원이 불확실해서 손으로
    private static double[] distinctSorted(Vec2[] seats, boolean x) {
        double[] all = new double[seats.length];
        for (int i = 0; i < seats.length; i++) all[i] = x ? seats[i].x() : seats[i].z();
        Arrays.sort(all);
        List<Double> out = new ArrayList<>();
        for (double v : all) if (out.isEmpty() || Math.abs(out.get(out.size() - 1) - v) > 1e-9) out.add(v);
        return toArray(out);
    }

    private static double[] toArray(List<Double> xs) {
        double[] a = new double[xs.size()];
        for (int i = 0; i < a.length; i++) a[i] = xs.get(i);
        return a;
    }

    private static double nearest(double[] xs, double v) {
        double best = xs[0];
        for (double x : xs) if (Math.abs(x - v) < Math.abs(best - v)) best = x;
        return best;
    }
}

package com.catchnyang.game;

/** 교실 바닥 평면 좌표 (m). x: 좌우, z: 앞뒤 */
public record Vec2(double x, double z) {
    public double dist(Vec2 o) {
        return Math.hypot(x - o.x, z - o.z);
    }
}

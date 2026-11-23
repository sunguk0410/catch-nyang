package com.catchnyang.game;

/** 교실 바닥 평면 좌표 (m). x: 좌우, z: 앞뒤 */
public record Vec2(double x, double z) {
    public double dist(Vec2 o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);  // hypot 대신: JVM·JS 결과가 같도록
    }
}

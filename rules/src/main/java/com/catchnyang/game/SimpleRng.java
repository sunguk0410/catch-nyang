package com.catchnyang.game;

import java.util.random.RandomGenerator;

/**
 * 같은 seed 면 JVM 이든 JS(TeaVM) 든 똑같은 수열을 내는 작은 난수기 (xorshift32).
 * TeaVM 은 SplittableRandom 이 없고 RandomGenerator 기본 구현도 일부라서, 엔진이 쓰는 메서드를 전부 직접 구현한다.
 */
public final class SimpleRng implements RandomGenerator {

    private int state;

    public SimpleRng(int seed) {
        this.state = seed == 0 ? 0x9E3779B9 : seed;
    }

    @Override
    public int nextInt() {
        int x = state;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        state = x;
        return x;
    }

    @Override
    public long nextLong() {
        return ((long) nextInt() << 32) ^ (nextInt() & 0xFFFFFFFFL);
    }

    /** [0, 1) — 상위 24비트만 써서 float 정밀도 문제 없이 양쪽 동일 */
    @Override
    public double nextDouble() {
        return (nextInt() >>> 8) / (double) (1 << 24);
    }

    @Override
    public double nextDouble(double origin, double bound) {
        return origin + (bound - origin) * nextDouble();
    }

    @Override
    public int nextInt(int bound) {
        return (int) (nextDouble() * bound);
    }

    @Override
    public boolean nextBoolean() {
        return (nextInt() & 1) == 1;
    }
}

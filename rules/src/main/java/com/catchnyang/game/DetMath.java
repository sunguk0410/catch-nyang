package com.catchnyang.game;

/**
 * JVM 과 브라우저(JS) 에서 비트 단위로 같은 결과를 내는 수학 함수.
 * Math.cos 같은 초월함수는 런타임마다 마지막 자리가 다를 수 있어서, 사칙연산만으로 계산한다.
 */
public final class DetMath {

    private DetMath() {}

    /** cos(도). 0~180도 범위에서 오차 1e-12 수준 (테일러 급수, 고정 항 수) */
    public static double cosDeg(double deg) {
        double x = deg * (3.141592653589793 / 180.0);
        // [-π, π] 로 접기
        while (x > 3.141592653589793) x -= 6.283185307179586;
        while (x < -3.141592653589793) x += 6.283185307179586;
        double x2 = x * x;
        double term = 1.0;
        double sum = 1.0;
        for (int n = 1; n <= 14; n++) {
            term *= -x2 / ((2 * n - 1) * (2 * n));
            sum += term;
        }
        return sum;
    }
}

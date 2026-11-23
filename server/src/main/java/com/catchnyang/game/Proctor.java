package com.catchnyang.game;

/** 감독관 상태 */
public final class Proctor {

    Vec2 position;
    double moveX, moveZ;  // 이동 입력 방향 (길이 ≤ 1)
    /** 바라보는 방향 (단위 벡터). 기본은 교탁에서 학생 쪽(-z) */
    Vec2 facing = new Vec2(0, -1);

    long stunnedUntil;

    int swatsLeft;
    int catchSuccesses;
    int detectorsLeft;

    // 검사: 같은 학생 앞에 inspectHoldSec 이상 서 있으면 inspecting
    int nearSeat = -1;
    long nearSince;
    boolean inspecting;

    Proctor(Vec2 spawn, int swats, int detectors) {
        this.position = spawn;
        this.swatsLeft = swats;
        this.detectorsLeft = detectors;
    }

    public Vec2 position() { return position; }
    public Vec2 facing() { return facing; }
    /** 파리채 헛손질 경직이 끝나는 시각 (게임 시간 ms) */
    public long stunnedUntil() { return stunnedUntil; }
    public int swatsLeft() { return swatsLeft; }
    public int catchSuccesses() { return catchSuccesses; }
    public int detectorsLeft() { return detectorsLeft; }
    /** 지금 시험지·OMR 을 보고 있는 학생 자리, 없으면 -1 */
    public int inspectingSeat() { return inspecting ? nearSeat : -1; }
}

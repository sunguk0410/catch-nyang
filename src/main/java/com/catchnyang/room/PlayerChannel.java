package com.catchnyang.room;

import java.util.List;

/** 방 → 플레이어 송신 통로. 구현은 웹소켓 세션 (4번 단계), 테스트에선 기록용 가짜. */
public interface PlayerChannel {

    /** 한 tick 동안 쌓인 것을 한 번에 보냄 (웹소켓 프레임 1개). 방 스레드에서 호출됨 */
    void send(List<Outbound> batch);
}

package com.catchnyang.room;

/**
 * 판이 끝나 방이 루프에서 빠질 때 호출됨 (그 방의 스레드, 마지막 tick 직후 — 방 상태를 읽어도 안전).
 * 오래 걸리는 일(DB 저장)은 비동기로 넘길 것. 빈으로 등록하면 {@link RoomBeans} 가 전부 모아서 부른다.
 */
@FunctionalInterface
public interface MatchEndListener {
    void onMatchEnded(GameRoom room);
}

package com.catchnyang.room;

import com.catchnyang.game.Match;
import com.catchnyang.game.config.GameConfig;

import java.util.SplittableRandom;
import java.util.UUID;
import java.util.function.Consumer;

/** 방 만들기 → 루프에 등록. 매칭(로비)이 두 플레이어 채널을 넘겨서 호출한다. */
public final class GameRooms {

    private final GameConfig cfg;
    private final GameLoop loop;

    public GameRooms(GameConfig cfg, GameLoop loop) {
        this.cfg = cfg;
        this.loop = loop;
    }

    public GameRoom create(PlayerChannel cat, PlayerChannel proctor) {
        return create(MatchInfo.anonymous(), cat, proctor, room -> {});
    }

    /**
     * @param beforeStart 루프에 올리기 직전에 호출 (MatchFound 전송·세션 등록 등).
     *                    여기서 보낸 메시지가 방의 첫 tick 메시지보다 먼저 나간다.
     */
    public GameRoom create(MatchInfo info, PlayerChannel cat, PlayerChannel proctor, Consumer<GameRoom> beforeStart) {
        String id = UUID.randomUUID().toString();
        GameRoom room = new GameRoom(id, new Match(cfg, new SplittableRandom()), cfg, info, cat, proctor);
        beforeStart.accept(room);
        loop.add(room);
        return room;
    }

    public int activeRooms() {
        return loop.activeRooms();
    }
}

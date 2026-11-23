package com.catchnyang.room;

import com.catchnyang.game.Match;
import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;

import java.util.SplittableRandom;

/** 가짜 판 하나를 루프 없이 바로 돌림 (서버 시작 시 JIT·클래스 로딩 예열용). 준비 시간 + 20초치 */
public final class RoomWarmup {

    private RoomWarmup() {}

    public static void run(GameConfig cfg, PlayerChannel cat, PlayerChannel proctor) {
        GameRoom room = new GameRoom("warmup", new Match(cfg, new SplittableRandom(1)), cfg, cat, proctor);
        int ticks = (cfg.match().prepareSec() + 20) * cfg.server().tickHz();
        for (int i = 0; i < ticks && !room.isFinished(); i++) {
            room.submit(Role.PROCTOR, i + 1, new GameCommand.Move(i % 2 == 0 ? 1 : -1, 0));
            room.submit(Role.PROCTOR, i + 1, new GameCommand.Look(1, i % 3 - 1));
            room.submit(Role.CAT, i + 1, new GameCommand.Possess(i % 9));
            if (i % 20 == 0) room.requestSnapshot(i % 40 == 0 ? Role.CAT : Role.PROCTOR);
            room.tick();
        }
    }
}

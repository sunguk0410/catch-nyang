package com.catchnyang.room;

import com.catchnyang.game.config.GameConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
public class RoomBeans {

    private static final Logger log = LoggerFactory.getLogger(RoomBeans.class);

    /**
     * 등록된 모든 MatchEndListener (로비 정리, DB 저장 …) 를 차례로 부름. 하나가 터져도 나머지는 불림.
     * 로비가 GameRooms → GameLoop 를 의존하므로 리스너는 판이 끝날 때 늦게 찾는다 (순환 의존 방지).
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    public GameLoop gameLoop(GameConfig cfg, MeterRegistry registry, ObjectProvider<MatchEndListener> listeners) {
        MatchEndListener all = room -> {
            for (MatchEndListener l : listeners.orderedStream().toList()) {
                try {
                    l.onMatchEnded(room);
                } catch (RuntimeException e) {
                    log.error("room {} end listener {} failed", room.id(), l.getClass().getSimpleName(), e);
                }
            }
        };
        return new GameLoop(1000L / cfg.server().tickHz(), registry, all);
    }

    @Bean
    public GameRooms gameRooms(GameConfig cfg, GameLoop loop) {
        return new GameRooms(cfg, loop);
    }
}

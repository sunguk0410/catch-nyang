package com.catchnyang.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GameWebSocketHandler gameWebSocketHandler;
    private final String[] allowedOrigins;

    public WebSocketConfig(GameWebSocketHandler gameWebSocketHandler,
                           @Value("${game.allowed-origins:*}") String[] allowedOrigins) {
        this.gameWebSocketHandler = gameWebSocketHandler;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // STOMP 없이 바이너리(Protobuf) 프레임을 직접 처리.
        // 배포 시 GAME_ALLOWED_ORIGINS=https://프론트도메인 (쉼표로 여러 개) 로 제한
        registry.addHandler(gameWebSocketHandler, "/ws")
                .setAllowedOriginPatterns(allowedOrigins);
    }
}

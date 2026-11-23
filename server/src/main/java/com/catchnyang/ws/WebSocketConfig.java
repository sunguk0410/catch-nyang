package com.catchnyang.ws;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    static final String WS_PATH = "/ws";

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
        registry.addHandler(gameWebSocketHandler, WS_PATH)
                .setAllowedOriginPatterns(allowedOrigins);
    }

    /**
     * permessage-deflate 끄기.
     * <p>
     * 브라우저는 기본으로 압축을 요청하고 Tomcat 은 받아들인다. 그런데 우리 프레임은 수십~수백 바이트 protobuf 라
     * 압축 이득이 거의 없고, 프레임마다 deflate CPU + 연결마다 압축 컨텍스트 메모리(수십 KB)만 든다.
     * <p>
     * Spring 의 HandshakeHandler 에서 확장을 빈 목록으로 걸러도 소용없다 — Tomcat(UpgradeUtil)은 "빈 목록이면
     * 기본 설치 확장 전부" 로 해석한다. 그래서 요청에서 Sec-WebSocket-Extensions 헤더 자체를 지워서
     * 클라가 아무 확장도 요청하지 않은 것처럼 만든다.
     */
    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> stripWebSocketExtensions() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(req.getHeader(EXT) == null ? req : new WithoutExtensions(req), res);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns(WS_PATH);
        reg.setName("stripWebSocketExtensions");
        return reg;
    }

    private static final String EXT = "Sec-WebSocket-Extensions";

    private static final class WithoutExtensions extends HttpServletRequestWrapper {
        WithoutExtensions(HttpServletRequest req) {
            super(req);
        }

        @Override
        public String getHeader(String name) {
            return EXT.equalsIgnoreCase(name) ? null : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return EXT.equalsIgnoreCase(name) ? Collections.emptyEnumeration() : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            List<String> names = Collections.list(super.getHeaderNames());
            names.removeIf(EXT::equalsIgnoreCase);
            return Collections.enumeration(names);
        }
    }
}

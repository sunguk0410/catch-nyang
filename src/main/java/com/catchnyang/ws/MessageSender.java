package com.catchnyang.ws;

import com.catchnyang.proto.ErrorMessage;
import com.catchnyang.proto.ServerMessage;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;

/**
 * ServerMessage → 바이너리 프레임. 세션은 {@link #decorate} 로 감싼 것을 쓴다
 * (여러 스레드 — 웹소켓·방·매칭 — 가 동시에 보내도 안전, 느린 클라 때문에 방 스레드가 오래 막히지 않음).
 */
@Component
class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace();

    /** 한 번 보내기에 이보다 오래 막히면 연결을 끊음 */
    static final int SEND_TIME_LIMIT_MS = 5_000;
    /** 못 보내고 쌓인 게 이보다 크면 연결을 끊음 (느린 클라) */
    static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final boolean debugLog;

    MessageSender(@Value("${game.debug-log-messages:false}") boolean debugLog) {
        this.debugLog = debugLog;
    }

    static WebSocketSession decorate(WebSocketSession raw) {
        return new ConcurrentWebSocketSessionDecorator(raw, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    /** 실패해도 예외 대신 false (연결 정리는 afterConnectionClosed 에서) */
    boolean send(WebSocketSession session, ServerMessage msg) {
        if (session == null || !session.isOpen()) return false;
        try {
            if (debugLog && log.isDebugEnabled() && !msg.hasTick()) log.debug("-> {} {}", session.getId(), JSON.print(msg));
            session.sendMessage(new BinaryMessage(msg.toByteArray()));
            return true;
        } catch (IOException | RuntimeException e) {
            log.debug("send to {} failed: {}", session.getId(), e.toString());
            return false;
        }
    }

    boolean error(WebSocketSession session, String code, String message) {
        return send(session, ServerMessage.newBuilder()
                .setError(ErrorMessage.newBuilder().setCode(code).setMessage(message))
                .build());
    }
}

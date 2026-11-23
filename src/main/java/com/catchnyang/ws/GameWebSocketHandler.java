package com.catchnyang.ws;

import com.catchnyang.proto.ClientMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** 바이너리 프레임 ↔ ClientMessage. 실제 처리는 {@link Lobby} */
@Component
public class GameWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace();

    /** 클라 메시지는 작다 (가장 큰 게 UseTool 몇 문항). 넘으면 연결째 끊김 */
    static final int MAX_CLIENT_MESSAGE_BYTES = 4 * 1024;

    private final Lobby lobby;
    private final boolean debugLog;

    public GameWebSocketHandler(Lobby lobby, @Value("${game.debug-log-messages:false}") boolean debugLog) {
        this.lobby = lobby;
        this.debugLog = debugLog;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("connected: {}", session.getId());
        session.setBinaryMessageSizeLimit(MAX_CLIENT_MESSAGE_BYTES);
        session.setTextMessageSizeLimit(MAX_CLIENT_MESSAGE_BYTES);
        lobby.connected(MessageSender.decorate(session));
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        ClientMessage msg;
        try {
            msg = ClientMessage.parseFrom(message.getPayload());
        } catch (InvalidProtocolBufferException e) {
            lobby.badMessage(session, "protobuf 파싱 실패");
            return;
        }
        if (debugLog && log.isDebugEnabled() && msg.getPayloadCase() != ClientMessage.PayloadCase.PROCTOR_MOVE) {
            try {
                log.debug("<- {} {}", session.getId(), JSON.print(msg));
            } catch (InvalidProtocolBufferException ignored) {
                // 로그용
            }
        }
        lobby.handle(session, msg);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error {}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("disconnected: {} {}", session.getId(), status);
        lobby.disconnected(session);
    }
}

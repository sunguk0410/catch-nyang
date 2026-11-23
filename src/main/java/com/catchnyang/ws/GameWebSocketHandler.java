package com.catchnyang.ws;

import com.catchnyang.proto.ClientMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import jakarta.websocket.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

/** 바이너리 프레임 ↔ ClientMessage. 연결 관리는 {@link Connections}, 처리는 {@link Lobby} */
@Component
public class GameWebSocketHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GameWebSocketHandler.class);
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace();

    /** 클라 메시지는 작다 (가장 큰 게 UseTool 몇 문항). 넘으면 연결째 끊김 */
    static final int MAX_CLIENT_MESSAGE_BYTES = 4 * 1024;

    /** Tomcat 블로킹 쓰기(ping·close)의 최대 대기 (기본 20초). 보조 스레드만 막히지만 오래 붙잡을 이유가 없음 */
    static final long BLOCKING_SEND_TIMEOUT_MS = 5_000;

    private final Lobby lobby;
    private final Connections connections;
    private final boolean debugLog;

    public GameWebSocketHandler(Lobby lobby, Connections connections,
                                @Value("${game.debug-log-messages:false}") boolean debugLog) {
        this.lobby = lobby;
        this.connections = connections;
        this.debugLog = debugLog;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setBinaryMessageSizeLimit(MAX_CLIENT_MESSAGE_BYTES);
        session.setTextMessageSizeLimit(MAX_CLIENT_MESSAGE_BYTES);
        if (session instanceof NativeWebSocketSession n && n.getNativeSession(Session.class) instanceof Session s) {
            s.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", BLOCKING_SEND_TIMEOUT_MS);
        }
        Connection c = connections.open(session);
        lobby.connected(c);
        log.debug("connected: {}", session.getId());
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        Connection c = connections.get(session.getId());
        if (c == null) return;
        c.touch();
        ClientMessage msg;
        try {
            msg = ClientMessage.parseFrom(message.getPayload());
        } catch (InvalidProtocolBufferException e) {
            lobby.badMessage(c, "protobuf 파싱 실패");
            return;
        }
        if (debugLog && msg.getPayloadCase() != ClientMessage.PayloadCase.PROCTOR_MOVE && log.isDebugEnabled()) {
            try {
                log.debug("<- {} {}", session.getId(), JSON.print(msg));
            } catch (InvalidProtocolBufferException ignored) {
                // 로그용
            }
        }
        lobby.handle(c, msg);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        Connection c = connections.get(session.getId());
        if (c != null) connections.pong(c, message.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("transport error {}: {}", session.getId(), exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Connection c = connections.remove(session.getId());
        log.debug("disconnected: {} {}", session.getId(), status);
        if (c != null) lobby.disconnected(c);  // 서버가 먼저 끊었으면 이미 처리됨 (두 번 불러도 안전)
    }
}

package com.catchnyang.ws;

import com.catchnyang.proto.ErrorMessage;
import com.catchnyang.proto.ServerMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** ServerMessage → 바이트 → 연결 송신 큐. 소켓 쓰기는 {@link Connection} 의 쓰기 스레드가 함 (여기선 절대 안 막힘) */
@Component
class MessageSender {

    private static final Logger log = LoggerFactory.getLogger(MessageSender.class);
    private static final JsonFormat.Printer JSON = JsonFormat.printer().omittingInsignificantWhitespace();

    private final boolean debugLog;

    MessageSender(@Value("${game.debug-log-messages:false}") boolean debugLog) {
        this.debugLog = debugLog;
    }

    /** 닫혔거나 밀려서 끊긴 연결이면 false (연결 정리는 끊김 처리 쪽에서) */
    boolean send(Connection c, ServerMessage msg) {
        if (c == null || !c.isOpen()) return false;
        if (debugLog && !msg.hasTick() && log.isDebugEnabled()) {
            try {
                log.debug("-> {} {}", c.getId(), JSON.print(msg));
            } catch (InvalidProtocolBufferException ignored) {
                // 로그용
            }
        }
        return c.send(msg.toByteArray());
    }

    boolean error(Connection c, String code, String message) {
        return send(c, ServerMessage.newBuilder()
                .setError(ErrorMessage.newBuilder().setCode(code).setMessage(message))
                .build());
    }
}

package com.catchnyang.ws;

import com.catchnyang.proto.ServerMessage;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.PlayerChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

/**
 * 방 → 웹소켓. 재접속하면 세션만 갈아끼운다 ({@link #attach}).
 * 끊겨 있는 동안 온 묶음은 버린다 (재접속 시 Snapshot 으로 따라잡음).
 */
final class WsPlayerChannel implements PlayerChannel {

    private static final Logger log = LoggerFactory.getLogger(WsPlayerChannel.class);

    private final MessageSender sender;
    private volatile WebSocketSession session;

    WsPlayerChannel(MessageSender sender, WebSocketSession session) {
        this.sender = sender;
        this.session = session;
    }

    void attach(WebSocketSession session) {
        this.session = session;
    }

    /** 지금 붙은 세션이 이것일 때만 뗌 (이미 새 세션으로 갈아낀 뒤 옛 세션이 닫히는 경우 무시) */
    boolean detach(WebSocketSession closing) {
        WebSocketSession s = session;
        if (s != null && s.getId().equals(closing.getId())) {
            session = null;
            return true;
        }
        return false;
    }

    boolean isConnected() {
        WebSocketSession s = session;
        return s != null && s.isOpen();
    }

    /** 방 스레드에서 호출 */
    @Override
    public void send(List<Outbound> batch) {
        WebSocketSession s = session;
        if (s == null || !s.isOpen()) return;
        for (ServerMessage m : ProtoMapper.toMessages(batch)) {
            if (!sender.send(s, m)) {
                log.debug("drop batch for {}", s.getId());
                return;
            }
        }
    }
}

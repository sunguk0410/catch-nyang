package com.catchnyang.ws;

import com.catchnyang.proto.ServerMessage;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.PlayerChannel;

import java.util.List;

/**
 * 방 → 웹소켓. 재접속하면 연결만 갈아끼운다 ({@link #attach}).
 * 끊겨 있는 동안 온 묶음은 직렬화도 안 하고 버린다 (재접속 시 Snapshot 으로 따라잡음).
 * 방 스레드에서 불리지만 송신 큐에 넣기만 하므로 막히지 않는다.
 */
final class WsPlayerChannel implements PlayerChannel {

    private final MessageSender sender;
    private volatile Connection conn;

    WsPlayerChannel(MessageSender sender, Connection conn) {
        this.sender = sender;
        this.conn = conn;
    }

    void attach(Connection conn) {
        this.conn = conn;
    }

    /** 지금 붙은 연결이 이것일 때만 뗌 (이미 새 연결로 갈아낀 뒤 옛 연결이 닫히는 경우 무시) */
    boolean detach(Connection closing) {
        if (conn == closing) {
            conn = null;
            return true;
        }
        return false;
    }

    boolean isConnected() {
        Connection c = conn;
        return c != null && c.isOpen();
    }

    @Override
    public void send(List<Outbound> batch) {
        Connection c = conn;
        if (c == null || !c.isOpen()) return;
        for (ServerMessage m : ProtoMapper.toMessages(batch)) {
            if (!sender.send(c, m)) return;
        }
    }
}

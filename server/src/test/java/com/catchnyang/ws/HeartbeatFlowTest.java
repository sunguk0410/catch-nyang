package com.catchnyang.ws;

import com.catchnyang.proto.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** 하트비트·압축 거절 통합 테스트 (짧은 ping 주기로 띄움) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.ws.ping-interval-sec=0.3", "game.ws.idle-timeout-sec=1",
                "game.reconnect-grace-sec=1", "game.debug-log-messages=false"})
class HeartbeatFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry registry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Lobby lobby;

    final List<WebSocketSession> sessions = new ArrayList<>();
    final List<Socket> sockets = new ArrayList<>();
    final List<String> roomCodes = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (WebSocketSession s : sessions) if (s.isOpen()) s.close();
        for (Socket s : sockets) s.close();
        long deadline = System.currentTimeMillis() + 5000;
        while (lobby.activeMatches() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);
        for (String code : roomCodes) {
            jdbc.update("UPDATE game_match SET rematch_of = NULL WHERE room_code = ?", code);
            jdbc.update("DELETE mp FROM match_player mp JOIN game_match gm ON gm.id = mp.match_id WHERE gm.room_code = ?", code);
            jdbc.update("DELETE FROM game_match WHERE room_code = ?", code);
        }
    }

    final class Inbox extends BinaryWebSocketHandler {
        final LinkedBlockingQueue<ServerMessage> q = new LinkedBlockingQueue<>();

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage m) throws Exception {
            q.add(ServerMessage.parseFrom(m.getPayload()));
        }

        ServerMessage await(Predicate<ServerMessage> cond, String what) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ServerMessage m = q.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && cond.test(m)) return m;
            }
            throw new AssertionError("timeout waiting for " + what);
        }
    }

    WebSocketSession connect(Inbox inbox, WebSocketHttpHeaders headers) throws Exception {
        WebSocketSession s = new StandardWebSocketClient()
                .execute(inbox, headers, URI.create("ws://localhost:" + port + "/ws")).get(5, TimeUnit.SECONDS);
        sessions.add(s);
        return s;
    }

    static void send(WebSocketSession s, ClientMessage.Builder b) throws IOException {
        s.sendMessage(new BinaryMessage(b.build().toByteArray()));
    }

    // ---------- 손으로 짠 웹소켓 클라: 핸드셰이크 후 입 다물기 ----------

    /** 핸드셰이크만 하고, 이후엔 우리가 원할 때만 쓰고 절대 안 읽는 (= pong 도 안 하는) 클라 */
    Socket rawClient() throws IOException {
        Socket s = new Socket("localhost", port);
        sockets.add(s);
        byte[] key = new byte[16];
        String req = "GET /ws HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(key) + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) head.write(in.read());
        assertThat(head.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 101");
        return s;
    }

    /** 마스킹된 바이너리 프레임 하나 (마스크 키 0 → payload 그대로) */
    static void writeFrame(Socket s, byte[] payload) throws IOException {
        assertThat(payload.length).isLessThan(126);
        OutputStream out = s.getOutputStream();
        out.write(0x82);
        out.write(0x80 | payload.length);
        out.write(new byte[4]);
        out.write(payload);
        out.flush();
    }

    @Test
    @DisplayName("permessage-deflate 를 요청해도 서버가 거절 → 압축 없이 연결됨")
    void compressionRefused() throws Exception {
        WebSocketHttpHeaders h = new WebSocketHttpHeaders();
        h.setSecWebSocketExtensions(List.of(new WebSocketExtension("permessage-deflate")));
        WebSocketSession s = connect(new Inbox(), h);
        assertThat(s.getExtensions()).isEmpty();

        // 서버 응답 헤더로도 확인 (클라 구현에 기대지 않고)
        try (Socket raw = new Socket("localhost", port)) {
            String req = "GET /ws HTTP/1.1\r\nHost: localhost:" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + Base64.getEncoder().encodeToString(new byte[16]) + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Extensions: permessage-deflate; client_max_window_bits\r\n\r\n";
            raw.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            InputStream in = raw.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            while (!head.toString(StandardCharsets.US_ASCII).endsWith("\r\n\r\n")) head.write(in.read());
            String response = head.toString(StandardCharsets.US_ASCII);
            assertThat(response).startsWith("HTTP/1.1 101");
            assertThat(response.toLowerCase()).doesNotContain("sec-websocket-extensions");
        }
    }

    @Test
    @DisplayName("정상 클라는 ping 에 자동 pong 해서 오래 조용해도 안 끊기고, RTT 가 기록됨")
    void healthyClientSurvives() throws Exception {
        long before = registry.get("ws.rtt").timer().count();
        WebSocketSession s = connect(new Inbox(), new WebSocketHttpHeaders());
        Thread.sleep(2500);  // idle-timeout(1초)의 2.5배 동안 아무것도 안 보냄
        assertThat(s.isOpen()).isTrue();
        assertThat(registry.get("ws.rtt").timer().count()).isGreaterThan(before);
    }

    @Test
    @DisplayName("게임 중 응답 없는 연결(반쯤 끊긴 TCP)을 잡아내서 상대에게 알리고, 유예 뒤 기권 처리")
    void deadPeerDetected() throws Exception {
        Inbox host = new Inbox();
        WebSocketSession hs = connect(host, new WebSocketHttpHeaders());
        send(hs, ClientMessage.newBuilder().setCreateRoom(CreateRoom.newBuilder().setNickname("호스트")));
        String code = host.await(ServerMessage::hasRoomCreated, "RoomCreated").getRoomCreated().getRoomCode();
        roomCodes.add(code);

        Socket ghost = rawClient();
        writeFrame(ghost, ClientMessage.newBuilder()
                .setJoinRoom(JoinRoom.newBuilder().setNickname("유령").setRoomCode(code)).build().toByteArray());
        host.await(ServerMessage::hasMatchFound, "MatchFound");
        // 이제 유령은 TCP 는 살아 있지만 아무 말도 안 함 (pong 도 안 함)

        long t0 = System.currentTimeMillis();
        OpponentConnection off = host.await(ServerMessage::hasOpponentConnection, "opponent off").getOpponentConnection();
        long detectedMs = System.currentTimeMillis() - t0;
        assertThat(off.getConnected()).isFalse();
        assertThat(detectedMs).isLessThan(2000);  // idle-timeout 1초 + 하트비트 주기 0.3초 안팎

        GameEvent end = host.await(m -> m.hasTick() && m.getTick().getEventsList().stream().anyMatch(GameEvent::hasMatchEnded),
                "MatchEnded").getTick().getEventsList().stream().filter(GameEvent::hasMatchEnded).findFirst().orElseThrow();
        assertThat(end.getMatchEnded().getReason()).isEqualTo(EndReason.END_REASON_DISCONNECT);
        assertThat(registry.get("ws.terminated").tag("reason", "IDLE").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("ping 을 안 기다리고 그냥 TCP 를 끊은 클라도 바로 처리됨 (기존 경로)")
    void abruptClose() throws Exception {
        Inbox host = new Inbox();
        WebSocketSession hs = connect(host, new WebSocketHttpHeaders());
        send(hs, ClientMessage.newBuilder().setCreateRoom(CreateRoom.newBuilder().setNickname("호스트")));
        String code = host.await(ServerMessage::hasRoomCreated, "RoomCreated").getRoomCreated().getRoomCode();
        roomCodes.add(code);
        Socket ghost = rawClient();
        writeFrame(ghost, ClientMessage.newBuilder()
                .setJoinRoom(JoinRoom.newBuilder().setNickname("유령").setRoomCode(code)).build().toByteArray());
        host.await(ServerMessage::hasMatchFound, "MatchFound");
        ghost.close();
        assertThat(host.await(ServerMessage::hasOpponentConnection, "opponent off").getOpponentConnection().getConnected())
                .isFalse();
        hs.close(CloseStatus.NORMAL);
    }
}

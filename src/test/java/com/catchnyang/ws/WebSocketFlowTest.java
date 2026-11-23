package com.catchnyang.ws;

import com.catchnyang.proto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** 통합: 실제 웹소켓 클라 둘로 매칭 → 게임 메시지 → 끊김·재접속 → 재대결 → DB 저장 (로컬 MySQL 필요) */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"game.reconnect-grace-sec=1", "game.rematch-window-sec=2", "game.debug-log-messages=false"})
class WebSocketFlowTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Lobby lobby;

    final List<Client> clients = new ArrayList<>();
    final List<String> roomCodes = new ArrayList<>();

    /** 받은 ServerMessage 를 큐에 쌓는 테스트 클라 */
    final class Client extends BinaryWebSocketHandler {
        final LinkedBlockingQueue<ServerMessage> inbox = new LinkedBlockingQueue<>();
        WebSocketSession session;
        int seq;

        Client connect() throws Exception {
            session = new StandardWebSocketClient().execute(this, "ws://localhost:" + port + "/ws").get(5, TimeUnit.SECONDS);
            clients.add(this);
            return this;
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession s, BinaryMessage message) throws Exception {
            inbox.add(ServerMessage.parseFrom(message.getPayload()));
        }

        int send(ClientMessage.Builder b) throws IOException {
            int n = ++seq;
            synchronized (this) {
                session.sendMessage(new BinaryMessage(b.setSeq(n).build().toByteArray()));
            }
            return n;
        }

        /** 조건에 맞는 메시지가 올 때까지 (그 전 것은 버림) */
        ServerMessage await(Predicate<ServerMessage> cond, String what) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ServerMessage m = inbox.poll(100, TimeUnit.MILLISECONDS);
                if (m != null && cond.test(m)) return m;
            }
            throw new AssertionError("timeout waiting for " + what);
        }

        void close() throws IOException {
            if (session.isOpen()) session.close(CloseStatus.NORMAL);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (Client c : clients) c.close();
        // 남은 판은 재접속 유예(1초) 뒤 기권으로 끝나고 비동기 저장됨 → 그걸 기다렸다가 지움
        long deadline = System.currentTimeMillis() + 5000;
        while (lobby.activeMatches() > 0 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500);
        for (String code : roomCodes) {
            jdbc.update("UPDATE game_match SET rematch_of = NULL WHERE room_code = ?", code);  // 자기 참조 FK
            jdbc.update("DELETE mp FROM match_player mp JOIN game_match gm ON gm.id = mp.match_id WHERE gm.room_code = ?", code);
            jdbc.update("DELETE FROM game_match WHERE room_code = ?", code);
        }
    }

    static ClientMessage.Builder msg() {
        return ClientMessage.newBuilder();
    }

    record Pair(Client cat, Client proctor, MatchFound catFound, MatchFound proctorFound) {}

    /** 방 코드로 둘을 매칭 (테스트 후 DB 정리하기 쉬우라고 방 코드 사용) */
    Pair matchByRoomCode() throws Exception {
        Client a = new Client().connect();
        Client b = new Client().connect();
        a.send(msg().setCreateRoom(CreateRoom.newBuilder().setNickname("호스트")));
        String code = a.await(ServerMessage::hasRoomCreated, "RoomCreated").getRoomCreated().getRoomCode();
        roomCodes.add(code);
        assertThat(code).hasSize(6);

        b.send(msg().setJoinRoom(JoinRoom.newBuilder().setNickname("손님").setRoomCode(code.toLowerCase())));
        MatchFound fa = a.await(ServerMessage::hasMatchFound, "MatchFound a").getMatchFound();
        MatchFound fb = b.await(ServerMessage::hasMatchFound, "MatchFound b").getMatchFound();
        assertThat(fa.getRoomId()).isEqualTo(fb.getRoomId());
        assertThat(fa.getMyRole()).isNotEqualTo(fb.getMyRole());
        assertThat(fa.getOpponentNickname()).isEqualTo("손님");
        assertThat(fb.getOpponentNickname()).isEqualTo("호스트");
        assertThat(fa.getSessionToken()).isNotEqualTo(fb.getSessionToken());
        return fa.getMyRole() == Role.CAT ? new Pair(a, b, fa, fb) : new Pair(b, a, fb, fa);
    }

    @Test
    @DisplayName("랜덤 매칭: 둘이 대기열에 들어가면 역할이 갈려 매칭되고 각자 자기 스냅샷을 받음")
    void randomQueue() throws Exception {
        Client a = new Client().connect();
        Client b = new Client().connect();
        a.send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("하나")));
        a.await(ServerMessage::hasQueueJoined, "QueueJoined");
        b.send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("둘")));

        MatchFound fa = a.await(ServerMessage::hasMatchFound, "MatchFound").getMatchFound();
        MatchFound fb = b.await(ServerMessage::hasMatchFound, "MatchFound").getMatchFound();
        assertThat(Map.of(fa.getMyRole(), 1, fb.getMyRole(), 2)).containsKeys(Role.CAT, Role.PROCTOR);
        assertThat(fa.getPrepareSeconds()).isEqualTo(10);

        Snapshot sa = a.await(ServerMessage::hasSnapshot, "Snapshot").getSnapshot();
        assertThat(sa.getMyRole()).isEqualTo(fa.getMyRole());
        assertThat(sa.getPhase()).isEqualTo(Phase.PREPARE);
        assertThat(sa.getSeatsCount()).isEqualTo(9);
        assertThat(sa.hasCat()).isEqualTo(fa.getMyRole() == Role.CAT);
        assertThat(sa.hasProctorView()).isEqualTo(fa.getMyRole() == Role.PROCTOR);

        // 정리: 기권 → 판 종료 (랜덤 매칭은 room_code 가 없어 DB 행을 직접 지움)
        a.send(msg().setLeaveMatch(LeaveMatch.getDefaultInstance()));
        a.await(m -> m.hasTick() && m.getTick().getEventsList().stream().anyMatch(GameEvent::hasMatchEnded), "MatchEnded");
        Thread.sleep(500);
        List<Long> ids = jdbc.queryForList("""
                SELECT gm.id FROM game_match gm
                JOIN match_player c ON c.match_id = gm.id AND c.nickname IN ('하나', '둘')
                WHERE gm.room_code IS NULL""", Long.class);
        for (Long id : ids) {
            jdbc.update("DELETE FROM match_player WHERE match_id = ?", id);
            jdbc.update("DELETE FROM game_match WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("대기열 취소, 잘못된 닉네임·방 코드, 매칭 전 게임 입력은 에러")
    void lobbyErrors() throws Exception {
        Client a = new Client().connect();
        a.send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname(" ")));
        assertThat(a.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("BAD_NICKNAME");

        a.send(msg().setJoinRoom(JoinRoom.newBuilder().setNickname("냥").setRoomCode("ZZZZZZ")));
        assertThat(a.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("ROOM_NOT_FOUND");

        a.send(msg().setSwat(Swat.getDefaultInstance()));
        assertThat(a.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("NOT_IN_MATCH");

        a.send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("냥")));
        a.await(ServerMessage::hasQueueJoined, "QueueJoined");
        a.send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("냥")));
        assertThat(a.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("BAD_STATE");
        a.send(msg().setCancelQueue(CancelQueue.getDefaultInstance()));
        a.await(ServerMessage::hasQueueCancelled, "QueueCancelled");

        a.send(msg().setReconnect(Reconnect.newBuilder().setSessionToken("nope")));
        assertThat(a.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("BAD_TOKEN");
    }

    @Test
    @DisplayName("방 코드 매칭 → 준비 단계 입력은 seq 와 함께 거절, 감독관 시선 입력은 둘 다 위치로 받음")
    void inGameMessages() throws Exception {
        Pair p = matchByRoomCode();
        p.cat().await(ServerMessage::hasSnapshot, "cat snapshot");
        p.proctor().await(ServerMessage::hasSnapshot, "proctor snapshot");

        int seq = p.cat().send(msg().setPossess(Possess.newBuilder().setSeat(3)));
        GameTick t = p.cat().await(m -> m.hasTick() && m.getTick().getRejectedCount() > 0, "rejected").getTick();
        assertThat(t.getRejected(0).getSeq()).isEqualTo(seq);
        assertThat(t.getRejected(0).getReason()).isEqualTo(RejectReason.WRONG_PHASE);
        assertThat(t.getAckSeq()).isEqualTo(seq);

        // 다른 역할의 행동
        int s2 = p.proctor().send(msg().setUseTool(UseTool.newBuilder().addQuestions(0)));
        GameTick t2 = p.proctor().await(m -> m.hasTick() && m.getTick().getRejectedCount() > 0, "rejected").getTick();
        assertThat(t2.getRejected(0).getSeq()).isEqualTo(s2);
        assertThat(t2.getRejected(0).getReason()).isEqualTo(RejectReason.INVALID);

        // 준비 중에도 시선은 돌릴 수 있음 → 둘 다 ProctorState 받음
        p.proctor().send(msg().setProctorMove(ProctorMove.newBuilder().setFaceX(1)));
        GameTick seen = p.cat().await(m -> m.hasTick() && m.getTick().hasProctor(), "proctor state").getTick();
        assertThat(seen.getProctor().getFacing().getX()).isEqualTo(1f);
    }

    @Test
    @DisplayName("끊긴 뒤 토큰으로 재접속하면 새 토큰 + 스냅샷, 옛 토큰은 다시 못 씀")
    void reconnect() throws Exception {
        Pair p = matchByRoomCode();
        p.cat().await(ServerMessage::hasSnapshot, "snapshot");
        String token = p.catFound().getSessionToken();

        p.cat().close();
        OpponentConnection off = p.proctor().await(ServerMessage::hasOpponentConnection, "opponent off").getOpponentConnection();
        assertThat(off.getConnected()).isFalse();
        assertThat(off.getGraceMs()).isEqualTo(1000);

        Client back = new Client().connect();
        back.send(msg().setReconnect(Reconnect.newBuilder().setSessionToken(token)));
        Reconnected r = back.await(ServerMessage::hasReconnected, "Reconnected").getReconnected();
        assertThat(r.getRoomId()).isEqualTo(p.catFound().getRoomId());
        assertThat(r.getMyRole()).isEqualTo(Role.CAT);
        assertThat(r.getSessionToken()).isNotEqualTo(token);
        Snapshot s = back.await(ServerMessage::hasSnapshot, "snapshot after reconnect").getSnapshot();
        assertThat(s.hasCat()).isTrue();
        assertThat(p.proctor().await(ServerMessage::hasOpponentConnection, "opponent on").getOpponentConnection().getConnected()).isTrue();

        // 유예(1초)가 지나도 판은 계속됨
        Thread.sleep(1500);
        int seq = back.send(msg().setRemark(Remark.newBuilder().setQuestion(0)));
        assertThat(back.await(m -> m.hasTick() && m.getTick().getAckSeq() == seq, "ack").getTick().getPhase())
                .isEqualTo(Phase.PREPARE);

        Client thief = new Client().connect();
        thief.send(msg().setReconnect(Reconnect.newBuilder().setSessionToken(token)));
        assertThat(thief.await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("BAD_TOKEN");
    }

    @Test
    @DisplayName("유예 안에 안 돌아오면 기권 → 상대 승리 → 결과가 DB 에 저장되고 둘 다 로비로 돌아감")
    void disconnectForfeitAndSave() throws Exception {
        Pair p = matchByRoomCode();
        String code = roomCodes.getLast();
        p.cat().await(ServerMessage::hasSnapshot, "snapshot");
        p.cat().close();

        MatchEnded end = p.proctor().await(m -> m.hasTick()
                && m.getTick().getEventsList().stream().anyMatch(GameEvent::hasMatchEnded), "MatchEnded")
                .getTick().getEventsList().stream().filter(GameEvent::hasMatchEnded).findFirst().orElseThrow()
                .getMatchEnded();
        assertThat(end.getWinner()).isEqualTo(Role.PROCTOR);
        assertThat(end.getReason()).isEqualTo(EndReason.DISCONNECT);

        // 비동기 저장 기다림
        long deadline = System.currentTimeMillis() + 5000;
        List<Map<String, Object>> rows = List.of();
        while (System.currentTimeMillis() < deadline && rows.isEmpty()) {
            rows = jdbc.queryForList("SELECT * FROM game_match WHERE room_code = ?", code);
            Thread.sleep(100);
        }
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.getFirst();
        assertThat(row.get("match_type")).isEqualTo("ROOM_CODE");
        assertThat(row.get("winner_role")).isEqualTo("PROCTOR");
        assertThat(row.get("end_reason")).isEqualTo("DISCONNECT");
        assertThat(row.get("rule_version")).isEqualTo("0.1.0");

        List<Map<String, Object>> players = jdbc.queryForList(
                "SELECT role, nickname, stats FROM match_player WHERE match_id = ? ORDER BY role", row.get("id"));
        assertThat(players).extracting(m -> m.get("role")).containsExactly("CAT", "PROCTOR");
        assertThat(players.getFirst().get("nickname")).isEqualTo(p.catFound().getOpponentNickname().equals("손님") ? "호스트" : "손님");
        assertThat(players.getFirst().get("stats").toString()).contains("possessions");

        // 남은 사람은 다시 대기열에 들어갈 수 있음
        p.proctor().send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("또해")));
        p.proctor().await(ServerMessage::hasQueueJoined, "QueueJoined again");
        p.proctor().send(msg().setCancelQueue(CancelQueue.getDefaultInstance()));
        p.proctor().await(ServerMessage::hasQueueCancelled, "cancel");
    }

    // =====================================================================
    // 재대결
    // =====================================================================

    static Predicate<ServerMessage> rematch(RematchState state) {
        return m -> m.hasRematchUpdate() && m.getRematchUpdate().getState() == state;
    }

    static Predicate<ServerMessage> matchEnded() {
        return m -> m.hasTick() && m.getTick().getEventsList().stream().anyMatch(GameEvent::hasMatchEnded);
    }

    /** 방 코드 매칭 후 냥이가 기권해서 판을 끝냄 → 둘 다 재대결 가능 알림을 받음 */
    Pair endedPair() throws Exception {
        Pair p = matchByRoomCode();
        p.cat().send(msg().setLeaveMatch(LeaveMatch.getDefaultInstance()));
        for (Client c : List.of(p.cat(), p.proctor())) {
            c.await(matchEnded(), "MatchEnded");
            RematchUpdate u = c.await(ServerMessage::hasRematchUpdate, "rematch available").getRematchUpdate();
            assertThat(u.getState()).isEqualTo(RematchState.REMATCH_AVAILABLE);
            assertThat(u.getExpiresMs()).isBetween(1, 2000);
        }
        return p;
    }

    long savedMatchId(String code, String winnerRole) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<Long> ids = jdbc.queryForList(
                    "SELECT id FROM game_match WHERE room_code = ? AND winner_role = ? ORDER BY id", Long.class, code, winnerRole);
            if (!ids.isEmpty()) return ids.getLast();
            Thread.sleep(100);
        }
        throw new AssertionError("game_match not saved");
    }

    @Test
    @DisplayName("재대결: 한쪽 신청 → 상대에게 제안 → 수락하면 역할 바꿔 새 판, DB rematch_of 로 이어짐")
    void rematchAccepted() throws Exception {
        Pair p = endedPair();
        String code = roomCodes.getLast();

        p.proctor().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        p.proctor().await(rematch(RematchState.REMATCH_WAITING), "waiting");
        p.cat().await(rematch(RematchState.REMATCH_OFFERED), "offered");

        p.cat().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        MatchFound oldCat = p.cat().await(ServerMessage::hasMatchFound, "rematch found").getMatchFound();
        MatchFound oldProctor = p.proctor().await(ServerMessage::hasMatchFound, "rematch found").getMatchFound();
        assertThat(oldCat.getRematch()).isTrue();
        assertThat(oldCat.getMyRole()).isEqualTo(Role.PROCTOR);       // 역할이 바뀜
        assertThat(oldProctor.getMyRole()).isEqualTo(Role.CAT);
        assertThat(oldCat.getRoomId()).isNotEqualTo(p.catFound().getRoomId());
        assertThat(p.cat().await(ServerMessage::hasSnapshot, "snapshot").getSnapshot().getMyRole()).isEqualTo(Role.PROCTOR);

        long firstId = savedMatchId(code, "PROCTOR");  // 첫 판: 냥이 기권 → 감독관 승

        // 새 판을 끝내면 rematch_of = 이전 판 id
        p.proctor().send(msg().setLeaveMatch(LeaveMatch.getDefaultInstance()));  // 이제 냥이인 쪽이 기권
        p.proctor().await(matchEnded(), "MatchEnded 2");
        long secondId = savedMatchId(code, "PROCTOR");
        long deadline = System.currentTimeMillis() + 5000;
        while (secondId == firstId && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            secondId = savedMatchId(code, "PROCTOR");
        }
        Map<String, Object> second = jdbc.queryForMap("SELECT * FROM game_match WHERE id = ?", secondId);
        assertThat(((Number) second.get("rematch_of")).longValue()).isEqualTo(firstId);
        assertThat(second.get("match_type")).isEqualTo("ROOM_CODE");
        List<Map<String, Object>> players = jdbc.queryForList(
                "SELECT role, nickname FROM match_player WHERE match_id = ? ORDER BY role", secondId);
        assertThat(players.getFirst().get("nickname")).isEqualTo(p.proctorFound().getOpponentNickname().equals("손님") ? "호스트" : "손님");
    }

    @Test
    @DisplayName("재대결 거절: 신청한 쪽이 DECLINED 를 받고, 그 뒤엔 재대결 불가")
    void rematchDeclined() throws Exception {
        Pair p = endedPair();
        p.cat().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        p.proctor().await(rematch(RematchState.REMATCH_OFFERED), "offered");
        p.proctor().send(msg().setDeclineRematch(DeclineRematch.getDefaultInstance()));
        p.cat().await(rematch(RematchState.REMATCH_DECLINED), "declined");

        p.proctor().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        assertThat(p.proctor().await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("NO_REMATCH");
    }

    @Test
    @DisplayName("상대가 대기열로 가거나 나가면 재대결은 취소됨")
    void rematchCancelledWhenOpponentLeaves() throws Exception {
        Pair p = endedPair();
        p.proctor().send(msg().setJoinQueue(JoinQueue.newBuilder().setNickname("다른판")));
        p.cat().await(rematch(RematchState.REMATCH_DECLINED), "declined");
        p.proctor().send(msg().setCancelQueue(CancelQueue.getDefaultInstance()));

        Pair q = endedPair();
        q.proctor().close();
        q.cat().await(rematch(RematchState.REMATCH_DECLINED), "declined on disconnect");
    }

    @Test
    @DisplayName("아무도 안 하면 창(2초)이 지나 둘 다 EXPIRED")
    void rematchExpires() throws Exception {
        Pair p = endedPair();
        p.cat().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        p.cat().await(rematch(RematchState.REMATCH_EXPIRED), "expired cat");
        p.proctor().await(rematch(RematchState.REMATCH_EXPIRED), "expired proctor");
        p.proctor().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        assertThat(p.proctor().await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("NO_REMATCH");
    }

    @Test
    @DisplayName("끊겨서 진 판은 재대결 제안이 안 옴")
    void noRematchAfterDisconnect() throws Exception {
        Pair p = matchByRoomCode();
        p.cat().close();
        p.proctor().await(matchEnded(), "MatchEnded");
        Thread.sleep(300);
        assertThat(p.proctor().inbox).noneMatch(ServerMessage::hasRematchUpdate);
        p.proctor().send(msg().setRequestRematch(RequestRematch.getDefaultInstance()));
        assertThat(p.proctor().await(ServerMessage::hasError, "error").getError().getCode()).isEqualTo("NO_REMATCH");
    }
}

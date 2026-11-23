package com.catchnyang.ws;

import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.proto.*;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.GameRoom;
import com.catchnyang.room.GameRooms;
import com.catchnyang.room.MatchEndListener;
import com.catchnyang.room.MatchInfo;
import com.catchnyang.room.MatchType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 로비: 연결 ↔ 플레이어, 랜덤 매칭, 방 코드, 재접속, 게임 입력 전달.
 * <p>
 * 상태 전이(대기열·방 코드·매칭 성사·끊김·재접속·판 종료)는 전부 {@link #lock} 하나로 직렬화한다 —
 * 로비 메시지는 드물어서 경합이 거의 없고, 경쟁 조건(취소 vs 매칭, 끊김 vs 재접속)을 단순하게 막을 수 있다.
 * 게임 입력은 락 없이 방 입력 큐로 바로 들어간다.
 */
@Component
public class Lobby implements MatchEndListener, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Lobby.class);

    static final int NICKNAME_MAX = 20;          // DB match_player.nickname VARCHAR(20)
    static final int ROOM_CODE_LENGTH = 6;       // DB game_match.room_code CHAR(6)
    private static final char[] CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray(); // 헷갈리는 0/O, 1/I 제외

    enum State { IDLE, QUEUED, HOSTING, IN_MATCH }

    /** 플레이어 하나. 재접속하면 같은 객체에 새 세션이 붙는다 */
    static final class Player {
        volatile WebSocketSession session;
        String nickname;
        State state = State.IDLE;
        Ticket ticket;
        String roomCode;
        volatile ActiveMatch match;
        volatile Role role;

        Player(WebSocketSession session) {
            this.session = session;
        }
    }

    record Ticket(long id, Player player) {}

    static final class Seat {
        final Role role;
        final Player player;
        final WsPlayerChannel channel;
        String token;
        ScheduledFuture<?> grace;

        Seat(Role role, Player player, WsPlayerChannel channel) {
            this.role = role;
            this.player = player;
            this.channel = channel;
        }
    }

    static final class ActiveMatch {
        final GameRoom room;
        final Map<Role, Seat> seats = new EnumMap<>(Role.class);

        ActiveMatch(GameRoom room) {
            this.room = room;
        }

        Seat opponentOf(Role role) {
            return seats.get(role == Role.CAT ? Role.PROCTOR : Role.CAT);
        }
    }

    private final Object lock = new Object();
    private final GameRooms rooms;
    private final GameConfig cfg;
    private final MessageSender sender;
    private final long graceMs;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong ticketIds = new AtomicLong();

    private final Map<String, Player> bySession = new ConcurrentHashMap<>();
    private final Map<String, Player> roomCodes = new ConcurrentHashMap<>();
    private final Map<String, Seat> tokens = new ConcurrentHashMap<>();
    private final Map<String, ActiveMatch> matches = new ConcurrentHashMap<>();
    private final Matchmaker<Ticket> matchmaker = new Matchmaker<>(this::pair);
    private final ScheduledExecutorService timers =
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("reconnect-timer").daemon().factory());

    public Lobby(GameRooms rooms, GameConfig cfg, MessageSender sender,
                 @Value("${game.reconnect-grace-sec:30}") double graceSec) {
        this.rooms = rooms;
        this.cfg = cfg;
        this.sender = sender;
        this.graceMs = Math.round(graceSec * 1000);
        matchmaker.start();
    }

    // =====================================================================
    // 연결
    // =====================================================================

    void connected(WebSocketSession decorated) {
        bySession.put(decorated.getId(), new Player(decorated));
    }

    void disconnected(WebSocketSession raw) {
        synchronized (lock) {
            Player p = bySession.remove(raw.getId());
            if (p == null) return;  // 재접속으로 이미 다른 세션에 넘어간 옛 연결
            switch (p.state) {
                case QUEUED -> leaveQueue(p);
                case HOSTING -> closeRoomCode(p);
                case IN_MATCH -> {
                    Seat seat = p.match.seats.get(p.role);
                    if (!seat.channel.detach(raw)) return;
                    startGrace(p.match, seat);
                }
                case IDLE -> {}
            }
        }
    }

    // =====================================================================
    // 메시지
    // =====================================================================

    void handle(WebSocketSession raw, ClientMessage msg) {
        Player p = bySession.get(raw.getId());
        if (p == null) return;
        WebSocketSession s = p.session;

        if (ProtoMapper.isGameInput(msg)) {
            gameInput(p, s, msg);
            return;
        }
        switch (msg.getPayloadCase()) {
            case PING -> sender.send(s, ServerMessage.newBuilder()
                    .setPong(Pong.newBuilder()
                            .setClientTimeMs(msg.getPing().getClientTimeMs())
                            .setServerTimeMs(System.currentTimeMillis()))
                    .build());
            case JOIN_QUEUE -> joinQueue(p, msg.getJoinQueue().getNickname());
            case CANCEL_QUEUE -> cancel(p);
            case CREATE_ROOM -> createRoom(p, msg.getCreateRoom().getNickname());
            case JOIN_ROOM -> joinRoom(p, msg.getJoinRoom().getNickname(), msg.getJoinRoom().getRoomCode());
            case RECONNECT -> reconnect(p, raw, msg.getReconnect().getSessionToken());
            default -> sender.error(s, "BAD_MESSAGE", "payload 없음");
        }
    }

    /** 파싱 실패 등. 이 연결의 (감싼) 세션으로 보내야 다른 스레드 송신과 안 섞임 */
    void badMessage(WebSocketSession raw, String why) {
        Player p = bySession.get(raw.getId());
        if (p != null) sender.error(p.session, "BAD_MESSAGE", why);
    }

    private void gameInput(Player p, WebSocketSession s, ClientMessage msg) {
        ActiveMatch m = p.match;
        Role role = p.role;
        if (m == null || role == null) {
            sender.error(s, "NOT_IN_MATCH", msg.getPayloadCase().name());
            return;
        }
        for (GameCommand c : ProtoMapper.toCommands(msg)) {
            if (!m.room.submit(role, msg.getSeq(), c)) {
                sender.send(s, ServerMessage.newBuilder()
                        .setTick(GameTick.newBuilder().addRejected(
                                Rejected.newBuilder().setSeq(msg.getSeq()).setReason(RejectReason.RATE_LIMITED)))
                        .build());
                return;
            }
        }
    }

    // ---------- 랜덤 매칭 ----------

    private void joinQueue(Player p, String nickname) {
        String nick = validNickname(nickname);
        synchronized (lock) {
            if (nick == null) {
                sender.error(p.session, "BAD_NICKNAME", "닉네임은 1~" + NICKNAME_MAX + "자");
                return;
            }
            if (p.state != State.IDLE) {
                sender.error(p.session, "BAD_STATE", p.state.name());
                return;
            }
            p.nickname = nick;
            p.state = State.QUEUED;
            p.ticket = new Ticket(ticketIds.incrementAndGet(), p);
            sender.send(p.session, ServerMessage.newBuilder().setQueueJoined(QueueJoined.getDefaultInstance()).build());
            matchmaker.enqueue(p.ticket);  // 락 안에서 넣어야 QueueJoined 가 MatchFound 보다 먼저 나감
        }
    }

    /** 매칭 스레드에서 호출 */
    private List<Ticket> pair(Ticket a, Ticket b) {
        synchronized (lock) {
            boolean va = valid(a);
            boolean vb = valid(b);
            if (va && vb && a.player() != b.player()) {
                startMatch(a.player(), b.player(), MatchType.RANDOM, null);
                return List.of();
            }
            if (va) return List.of(a);
            if (vb) return List.of(b);
            return List.of();
        }
    }

    private static boolean valid(Ticket t) {
        return t.player().state == State.QUEUED && t.player().ticket == t && t.player().session.isOpen();
    }

    private void leaveQueue(Player p) {
        matchmaker.remove(p.ticket);
        p.ticket = null;
        p.state = State.IDLE;
    }

    private void cancel(Player p) {
        synchronized (lock) {
            switch (p.state) {
                case QUEUED -> leaveQueue(p);
                case HOSTING -> closeRoomCode(p);
                default -> {
                    sender.error(p.session, "BAD_STATE", p.state.name());
                    return;
                }
            }
            sender.send(p.session, ServerMessage.newBuilder().setQueueCancelled(QueueCancelled.getDefaultInstance()).build());
        }
    }

    // ---------- 방 코드 ----------

    private void createRoom(Player p, String nickname) {
        String nick = validNickname(nickname);
        synchronized (lock) {
            if (nick == null) {
                sender.error(p.session, "BAD_NICKNAME", "닉네임은 1~" + NICKNAME_MAX + "자");
                return;
            }
            if (p.state != State.IDLE) {
                sender.error(p.session, "BAD_STATE", p.state.name());
                return;
            }
            String code;
            do {
                code = newRoomCode();
            } while (roomCodes.putIfAbsent(code, p) != null);
            p.nickname = nick;
            p.state = State.HOSTING;
            p.roomCode = code;
            sender.send(p.session, ServerMessage.newBuilder().setRoomCreated(RoomCreated.newBuilder().setRoomCode(code)).build());
        }
    }

    private void joinRoom(Player p, String nickname, String rawCode) {
        String nick = validNickname(nickname);
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        synchronized (lock) {
            if (nick == null) {
                sender.error(p.session, "BAD_NICKNAME", "닉네임은 1~" + NICKNAME_MAX + "자");
                return;
            }
            if (p.state != State.IDLE) {
                sender.error(p.session, "BAD_STATE", p.state.name());
                return;
            }
            Player host = roomCodes.get(code);
            if (host == null || host == p || host.state != State.HOSTING || !host.session.isOpen()) {
                sender.error(p.session, "ROOM_NOT_FOUND", code);
                return;
            }
            roomCodes.remove(code);
            host.roomCode = null;
            p.nickname = nick;
            startMatch(host, p, MatchType.ROOM_CODE, code);
        }
    }

    private void closeRoomCode(Player p) {
        if (p.roomCode != null) roomCodes.remove(p.roomCode, p);
        p.roomCode = null;
        p.state = State.IDLE;
    }

    private String newRoomCode() {
        char[] c = new char[ROOM_CODE_LENGTH];
        for (int i = 0; i < c.length; i++) c[i] = CODE_CHARS[random.nextInt(CODE_CHARS.length)];
        return new String(c);
    }

    // ---------- 매칭 성사 ----------

    /** lock 안에서 호출. 역할은 랜덤 */
    private void startMatch(Player a, Player b, MatchType type, String roomCode) {
        boolean aIsCat = random.nextBoolean();
        Player cat = aIsCat ? a : b;
        Player proctor = aIsCat ? b : a;
        a.ticket = null;
        b.ticket = null;

        WsPlayerChannel catCh = new WsPlayerChannel(sender, cat.session);
        WsPlayerChannel proctorCh = new WsPlayerChannel(sender, proctor.session);
        MatchInfo info = new MatchInfo(type, roomCode, cat.nickname, proctor.nickname, Instant.now());

        rooms.create(info, catCh, proctorCh, room -> {
            ActiveMatch m = new ActiveMatch(room);
            m.seats.put(Role.CAT, new Seat(Role.CAT, cat, catCh));
            m.seats.put(Role.PROCTOR, new Seat(Role.PROCTOR, proctor, proctorCh));
            matches.put(room.id(), m);
            for (Seat seat : m.seats.values()) {
                Player pl = seat.player;
                pl.state = State.IN_MATCH;
                pl.match = m;
                pl.role = seat.role;
                seat.token = issueToken(seat);
                sender.send(pl.session, ServerMessage.newBuilder().setMatchFound(MatchFound.newBuilder()
                        .setRoomId(room.id())
                        .setMyRole(ProtoMapper.role(seat.role))
                        .setOpponentNickname(m.opponentOf(seat.role).player.nickname)
                        .setSessionToken(seat.token)
                        .setPrepareSeconds(cfg.match().prepareSec())
                        .setReconnectGraceSeconds((int) Math.ceil(graceMs / 1000.0)))
                        .build());
            }
            log.info("match {} started: type={} code={} cat={} proctor={}",
                    room.id(), type, roomCode, cat.nickname, proctor.nickname);
        });
    }

    // ---------- 끊김·재접속 ----------

    /** lock 안에서 호출 */
    private void startGrace(ActiveMatch m, Seat seat) {
        if (seat.grace != null) seat.grace.cancel(false);
        seat.grace = timers.schedule(() -> graceExpired(m, seat), graceMs, TimeUnit.MILLISECONDS);
        Seat opp = m.opponentOf(seat.role);
        sender.send(opp.player.session, ServerMessage.newBuilder().setOpponentConnection(
                OpponentConnection.newBuilder().setConnected(false).setGraceMs((int) graceMs)).build());
        log.info("match {} {} disconnected, grace {}ms", m.room.id(), seat.role, graceMs);
    }

    private void graceExpired(ActiveMatch m, Seat seat) {
        synchronized (lock) {
            if (seat.channel.isConnected() || matches.get(m.room.id()) != m) return;
            log.info("match {} {} did not reconnect → forfeit", m.room.id(), seat.role);
            m.room.submit(seat.role, new GameCommand.Leave());
        }
    }

    private void reconnect(Player fresh, WebSocketSession raw, String token) {
        synchronized (lock) {
            if (fresh.state != State.IDLE) {
                sender.error(fresh.session, "BAD_STATE", fresh.state.name());
                return;
            }
            Seat seat = token == null ? null : tokens.remove(token);
            if (seat == null) {
                sender.error(fresh.session, "BAD_TOKEN", "만료됐거나 이미 쓴 토큰");
                return;
            }
            ActiveMatch m = seat.player.match;
            Player p = seat.player;
            WebSocketSession old = p.session;

            // 옛 연결이 아직 살아 있으면(서버가 끊김을 늦게 알아챈 경우) 넘겨받고 닫는다.
            // bySession 에서 먼저 빼서 그 연결의 disconnected() 가 아무것도 안 하게 함
            if (old != null) bySession.remove(old.getId());
            p.session = fresh.session;
            bySession.put(raw.getId(), p);
            seat.channel.attach(fresh.session);
            if (seat.grace != null) {
                seat.grace.cancel(false);
                seat.grace = null;
            }
            seat.token = issueToken(seat);

            sender.send(p.session, ServerMessage.newBuilder().setReconnected(Reconnected.newBuilder()
                    .setRoomId(m.room.id())
                    .setMyRole(ProtoMapper.role(seat.role))
                    .setOpponentNickname(m.opponentOf(seat.role).player.nickname)
                    .setSessionToken(seat.token)).build());
            m.room.requestSnapshot(seat.role);
            sender.send(m.opponentOf(seat.role).player.session, ServerMessage.newBuilder().setOpponentConnection(
                    OpponentConnection.newBuilder().setConnected(true)).build());
            log.info("match {} {} reconnected", m.room.id(), seat.role);

            if (old != null && old.isOpen() && !old.getId().equals(raw.getId())) {
                try {
                    old.close(CloseStatus.POLICY_VIOLATION.withReason("reconnected elsewhere"));
                } catch (IOException ignored) {
                    // 어차피 버리는 연결
                }
            }
        }
    }

    private String issueToken(Seat seat) {
        if (seat.token != null) tokens.remove(seat.token);
        byte[] b = new byte[24];
        random.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        tokens.put(t, seat);
        return t;
    }

    // ---------- 판 종료 (방 스레드) ----------

    @Override
    public void onMatchEnded(GameRoom room) {
        synchronized (lock) {
            ActiveMatch m = matches.remove(room.id());
            if (m == null) return;
            for (Seat seat : m.seats.values()) {
                if (seat.token != null) tokens.remove(seat.token);
                if (seat.grace != null) seat.grace.cancel(false);
                Player p = seat.player;
                p.state = State.IDLE;
                p.match = null;
                p.role = null;
            }
        }
    }

    // ---------- 기타 ----------

    /** 앞뒤 공백 제거, 1~20자(코드포인트), 제어문자 없음. 아니면 null */
    static String validNickname(String raw) {
        if (raw == null) return null;
        String s = raw.strip();
        int n = s.codePointCount(0, s.length());
        if (n < 1 || n > NICKNAME_MAX) return null;
        if (s.codePoints().anyMatch(Character::isISOControl)) return null;
        return s;
    }

    int queueSize() {
        return matchmaker.size();
    }

    int activeMatches() {
        return matches.size();
    }

    @Override
    public void close() {
        matchmaker.close();
        timers.shutdownNow();
    }
}

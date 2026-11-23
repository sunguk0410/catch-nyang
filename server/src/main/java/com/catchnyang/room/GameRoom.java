package com.catchnyang.room;

import com.catchnyang.game.Match;
import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.GameCommand.Leave;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 서버의 방 하나 = {@link RoomCore}(규칙·묶음 만들기, rules 모듈) + 스레드 경계 + 플레이어 둘에게 전송.
 * <ul>
 *   <li>{@link #submit} · {@link #requestSnapshot} 은 아무 스레드(웹소켓)에서나 호출 — 큐에 넣기만 한다.</li>
 *   <li>{@link #tick} 은 이 방 전용 스레드에서만 호출 — 큐를 비워 RoomCore 에 넘기고, 나온 묶음을 보낸다.</li>
 * </ul>
 */
public final class GameRoom {

    private static final Logger log = LoggerFactory.getLogger(GameRoom.class);

    /** 한 방에 쌓일 수 있는 미처리 입력 상한 (넘치면 버림 — 도배 방지) */
    static final int MAX_PENDING_INPUTS = 256;

    private record Input(Role role, int seq, GameCommand command) {}

    private final String id;
    private final RoomCore core;
    private final MatchInfo info;
    private final Map<Role, PlayerChannel> channels = new EnumMap<>(Role.class);
    private final Queue<Input> inputs = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingInputs = new AtomicInteger();
    private final Map<Role, AtomicBoolean> snapshotRequested = new EnumMap<>(Role.class);

    private volatile boolean finished;

    public GameRoom(String id, Match match, GameConfig cfg, PlayerChannel cat, PlayerChannel proctor) {
        this(id, match, cfg, MatchInfo.anonymous(), cat, proctor);
    }

    public GameRoom(String id, Match match, GameConfig cfg, MatchInfo info, PlayerChannel cat, PlayerChannel proctor) {
        this.id = id;
        this.core = new RoomCore(match, cfg);
        this.info = info;
        channels.put(Role.CAT, cat);
        channels.put(Role.PROCTOR, proctor);
        for (Role r : Role.values()) snapshotRequested.put(r, new AtomicBoolean(false));
    }

    /** 네트워크 스레드에서 호출. 다음 tick 에 적용됨 */
    public void submit(Role role, GameCommand command) {
        submit(role, 0, command);
    }

    /** seq: 클라 입력 순번. 처리되면 그 역할의 다음 묶음 Clock.ackSeq 로 돌아감 */
    public boolean submit(Role role, int seq, GameCommand command) {
        if (finished) return false;
        // 나가기는 상한과 무관하게 받음 (끊김 처리가 막히면 안 됨)
        if (pendingInputs.incrementAndGet() > MAX_PENDING_INPUTS && !(command instanceof Leave)) {
            pendingInputs.decrementAndGet();
            return false;
        }
        inputs.add(new Input(role, seq, command));
        return true;
    }

    /** 다음 tick 에 그 역할에게 전체 상태를 보냄 (재접속 등). 아무 스레드에서나 */
    public void requestSnapshot(Role role) {
        snapshotRequested.get(role).set(true);
    }

    /** 방 스레드에서만 호출 */
    void tick() {
        if (finished) return;
        Input in;
        while ((in = inputs.poll()) != null) {
            pendingInputs.decrementAndGet();
            core.submit(in.role(), in.seq(), in.command());
        }
        for (Role r : Role.values()) {
            if (snapshotRequested.get(r).getAndSet(false)) core.requestSnapshot(r);
        }
        core.tick().forEach(this::send);
        if (core.isFinished()) finished = true;
    }

    private void send(Role role, List<Outbound> batch) {
        if (batch.isEmpty()) return;
        try {
            channels.get(role).send(batch);
        } catch (RuntimeException e) {
            // 한 플레이어 송신 실패가 게임 루프를 멈추면 안 됨. 끊김 처리는 네트워크 계층이 Leave 로 넣음
            log.warn("room {} send to {} failed: {}", id, role, e.toString());
        }
    }

    public String id() { return id; }
    public Match match() { return core.match(); }
    public MatchInfo info() { return info; }
    /** 판이 끝난 뒤 읽을 것 */
    public MatchStats stats() { return core.stats(); }
    public boolean isFinished() { return finished; }
}

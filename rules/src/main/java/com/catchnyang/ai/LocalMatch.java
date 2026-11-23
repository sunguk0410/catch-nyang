package com.catchnyang.ai;

import com.catchnyang.game.Match;
import com.catchnyang.game.Role;
import com.catchnyang.game.SimpleRng;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.RoomCore;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 서버 없이 도는 한 판: RoomCore + AI. 싱글 모드(브라우저)와 봇끼리 시뮬레이션(밸런스 확인)에 쓴다.
 * 봇은 자기 역할에게 간 묶음만 보고 판단하고, 명령은 다음 tick 에 적용된다 (사람과 같은 조건).
 */
public final class LocalMatch {

    private final RoomCore core;
    private final Role human;
    private final Map<Role, BotView> views = new EnumMap<>(Role.class);
    private final Map<Role, Bot> bots = new EnumMap<>(Role.class);

    /**
     * @param human 사람이 맡는 역할. null 이면 봇끼리
     */
    public LocalMatch(GameConfig cfg, int seed, Role human) {
        this.core = new RoomCore(new Match(cfg, new SimpleRng(seed)), cfg);
        this.human = human;
        for (Role r : Role.values()) {
            if (r == human) continue;
            views.put(r, new BotView(cfg, r));
            SimpleRng botRng = new SimpleRng(seed * 31 + r.ordinal() + 7);
            bots.put(r, r == Role.CAT ? new CatBot(botRng) : new ProctorBot(botRng));
        }
    }

    /** 사람 입력. 다음 tick 에 적용 */
    public void submit(int seq, GameCommand command) {
        if (human != null) core.submit(human, seq, command);
    }

    public void requestSnapshot() {
        if (human != null) core.requestSnapshot(human);
    }

    /** 한 tick 진행. 사람에게 보낼 묶음 (봇끼리면 빈 목록) */
    public List<Outbound> tick() {
        Map<Role, List<Outbound>> out = tickAllRoles();
        return human == null ? new ArrayList<>() : out.get(human);
    }

    /** 한 tick 진행 (봇은 자기 묶음을 보고 다음 명령을 냄). 두 역할의 묶음을 다 돌려줌 — 테스트·비교용 */
    public Map<Role, List<Outbound>> tickAllRoles() {
        Map<Role, List<Outbound>> out = core.tick();
        for (var e : views.entrySet()) {
            Role r = e.getKey();
            BotView view = e.getValue();
            view.apply(out.get(r));
            for (GameCommand c : bots.get(r).decide(view)) core.submit(r, 0, c);
        }
        return out;
    }

    public boolean isFinished() {
        return core.isFinished();
    }

    public Match match() {
        return core.match();
    }

    public RoomCore core() {
        return core;
    }
}

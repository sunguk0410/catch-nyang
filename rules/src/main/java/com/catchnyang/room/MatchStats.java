package com.catchnyang.room;

import com.catchnyang.game.GameEvent;
import com.catchnyang.game.GameEvent.*;
import com.catchnyang.game.Role;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 사건 흐름을 보고 역할별 통계를 센다 (DB match_player.stats). 방 스레드에서만 갱신 */
public final class MatchStats {

    private final Map<Role, Map<String, Integer>> stats = new EnumMap<>(Role.class);

    public MatchStats() {
        stats.put(Role.CAT, zeros(List.of(
                "possessions", "toolUses", "questionsRevealed", "remarks", "toolsDestroyed", "timesCaught")));
        stats.put(Role.PROCTOR, zeros(List.of(
                "inspections", "swats", "catches", "swatMisses", "detectorUses", "detectorHits")));
    }

    private static Map<String, Integer> zeros(List<String> keys) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : keys) m.put(k, 0);
        return m;
    }

    void accept(GameEvent event) {
        switch (event) {
            case Possessed e -> inc(Role.CAT, "possessions");
            case ToolStarted e -> inc(Role.CAT, "toolUses");
            case ToolFinished e -> add(Role.CAT, "questionsRevealed", e.revealed().size());
            case RemarkStarted e -> {
                if (e.byCat()) inc(Role.CAT, "remarks");
            }
            case ToolDestroyed e -> inc(Role.CAT, "toolsDestroyed");
            case InspectReady e -> inc(Role.PROCTOR, "inspections");
            case CatchResult e -> {
                inc(Role.PROCTOR, "swats");
                if (e.success()) {
                    inc(Role.PROCTOR, "catches");
                    inc(Role.CAT, "timesCaught");
                } else {
                    inc(Role.PROCTOR, "swatMisses");
                }
            }
            case DetectorResult e -> {
                inc(Role.PROCTOR, "detectorUses");
                if (e.hit()) inc(Role.PROCTOR, "detectorHits");
            }
            default -> {}
        }
    }

    private void inc(Role role, String key) {
        add(role, key, 1);
    }

    private void add(Role role, String key, int n) {
        stats.get(role).merge(key, n, Integer::sum);
    }

    /** 판이 끝난 뒤(방 스레드가 더 안 건드릴 때) 읽을 것 */
    public Map<String, Integer> of(Role role) {
        return Collections.unmodifiableMap(stats.get(role));
    }
}

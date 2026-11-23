package com.catchnyang.history;

import com.catchnyang.game.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.TreeMap;

/** 판 결과 한 건을 game_match + match_player 2줄로 한 트랜잭션에 저장 */
@Service
public class MatchResultWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** DECIMAL(4,1) 범위 */
    private static final BigDecimal MAX_AVG = new BigDecimal("999.9");

    private final GameMatchRepository matches;
    private final MatchPlayerRepository players;

    public MatchResultWriter(GameMatchRepository matches, MatchPlayerRepository players) {
        this.matches = matches;
        this.players = players;
    }

    /** @param rematchOf 이전 판의 game_match.id (재대결이 아니거나 이전 판 저장이 실패했으면 null) */
    @Transactional
    public long save(MatchResult r, Long rematchOf) {
        BigDecimal avg = BigDecimal.valueOf(r.finalAverage()).setScale(1, RoundingMode.HALF_UP).min(MAX_AVG);
        int target = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, r.finalTarget()));
        GameMatchEntity match = matches.save(new GameMatchEntity(
                r.type().name(), r.roomCode(), rematchOf, utc(r.startedAt()), utc(r.endedAt()),
                r.winner().name(), r.endReason().name(), avg, target, r.ruleVersion()));

        players.save(new MatchPlayerEntity(match.getId(), Role.CAT.name(), r.catNickname(), json(r.catStats())));
        players.save(new MatchPlayerEntity(match.getId(), Role.PROCTOR.name(), r.proctorNickname(), json(r.proctorStats())));
        return match.getId();
    }

    private static LocalDateTime utc(Instant t) {
        return LocalDateTime.ofInstant(t, ZoneOffset.UTC);
    }

    private static String json(Map<String, Integer> stats) {
        return JSON.writeValueAsString(new TreeMap<>(stats));
    }
}

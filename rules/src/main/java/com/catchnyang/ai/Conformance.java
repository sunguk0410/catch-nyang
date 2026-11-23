package com.catchnyang.ai;

import com.catchnyang.game.Match;
import com.catchnyang.game.Role;
import com.catchnyang.game.config.GameConfig;
import com.catchnyang.room.Outbound;
import com.catchnyang.room.OutboundJson;

import java.util.List;
import java.util.Map;

/**
 * JVM(서버)과 브라우저(JS 로 변환된 rules)가 같은 규칙으로 도는지 확인하는 기록.
 * 봇끼리 한 판을 돌리며 두 역할에게 간 모든 메시지(JSON)를 해시에 넣고, 일정 간격마다 해시·상태를 한 줄씩 남긴다.
 * 메시지가 한 글자라도 다르면 그 뒤 해시가 전부 달라진다.
 * <p>
 * rules 테스트(JVM)와 web 테스트(JS)가 같은 기준 파일(rules/src/test/resources/conformance)과 비교한다.
 */
public final class Conformance {

    private Conformance() {}

    public static String run(GameConfig cfg, int seed) {
        LocalMatch m = new LocalMatch(cfg, seed, null);
        int maxTicks = (cfg.match().prepareSec() + cfg.match().durationSec() + 5) * cfg.server().tickHz();
        int hash = 0x811C9DC5;  // FNV-1a 32bit (int 연산이라 JVM·JS 같음)
        StringBuilder log = new StringBuilder();
        int t = 0;
        for (; t < maxTicks && !m.isFinished(); t++) {
            Map<Role, List<Outbound>> out = m.tickAllRoles();
            for (Role r : Role.values()) {
                List<Outbound> batch = out.get(r);
                if (batch.isEmpty()) continue;
                String json = OutboundJson.messages(batch);
                hash = fnv(hash, r.name());
                hash = fnv(hash, json);
            }
            if (t % 200 == 0) log.append(line(t, hash, m.match())).append('\n');
        }
        Match mm = m.match();
        log.append("END ").append(line(t, hash, mm)).append(" winner=").append(mm.winner()).append(" reason=").append(mm.endReason()).append('\n');
        return log.toString();
    }

    private static String line(int t, int hash, Match m) {
        return "t=" + t + " hash=" + Integer.toHexString(hash) + " phase=" + m.phase() + " target=" + m.target()
                + " avg=" + OutboundJson.num(m.classAverage()) + " cat=" + m.cat().seat() + " known=" + m.cat().knownQuestions().size()
                + " swats=" + m.proctor().swatsLeft() + " detectors=" + m.proctor().detectorsLeft();
    }

    private static int fnv(int h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x01000193;
        }
        return h;
    }
}

package com.catchnyang.ai;

import com.catchnyang.game.GameEvent;
import com.catchnyang.game.SimpleRng;
import com.catchnyang.game.Vec2;
import com.catchnyang.room.EventFilter;
import com.catchnyang.room.GameCommand;
import com.catchnyang.room.StudentInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 감독관. 사람 감독관이 볼 수 있는 것만으로 판단한다.
 * <ul>
 *   <li>의심도: 시야 안에서 본 움찔(+)·지우는 손동작(+)·도구 빛(++), 검사로 찾은 "시험지와 다른 마킹"(+), 탐지기 명중(+++).
 *       시간이 지나면 식음 (냥이는 계속 옮겨 다니므로)</li>
 *   <li>순찰: 의심 가는 학생이 없으면 앞에 서서 교실 전체를 지켜보거나, 아무 학생에게 가서 검사</li>
 *   <li>걸으면서도 시선은 학생들 쪽 (이동과 시선은 따로 — 사람도 옆걸음질하며 지켜볼 수 있음)</li>
 *   <li>의심도가 높으면 그 학생 앞으로 가서 검사</li>
 *   <li>파리채는 "지금 냥이가 거기 있다" 는 최근 증거(움찔·도구 빛·탐지기 명중을 10초 안에 봄)가 있을 때만.
 *       다르게 마킹된 OMR 은 냥이가 "지나간" 흔적일 뿐이라 그것만으론 안 침 (헛손질 = 목표 -5)</li>
 *   <li>도구 빛을 보면 잠깐 뒤 전파 탐지기 (사람처럼 반응 시간). 단서가 없을 땐 가끔 운에 맡겨 씀</li>
 * </ul>
 */
public final class ProctorBot implements Bot {

    /** 이 이상이면 그 학생에게 감 */
    static final double APPROACH = 2.0;
    /** "냥이가 지금 거기 있다" 는 증거의 유효 시간 */
    static final long PRESENCE_MS = 10_000;
    /** tick(50ms) 마다 의심도에 곱함 → 반감기 약 12초 */
    static final double DECAY = 0.9971;

    private final SimpleRng rng;
    private Nav nav;
    private double[] sus = new double[0];

    private int target = -1;
    private List<Vec2> path = new ArrayList<>();
    private int pathIdx;
    private Vec2 spot;
    private long arrivedAt = -1;
    private boolean evaluated;
    private long nextThinkAt;
    private long detectAt = -1;
    private long nextGambleAt = 30_000;
    /** 냥이가 있다고 본 자리와 그때 시각 (-1 = 모름) */
    private int presence = -1;
    private long presenceAt;
    /** 10초마다 공개되는 반 평균 — 올랐으면 냥이가 활동 중 */
    private double lastRevealed = Double.NaN;
    private boolean averageRising;
    private long lastProgressAt;
    private Vec2 lastPos;

    /** 지켜보기(앞에 서서 교실 전체 감시) 끝나는 시각, -1 = 안 하는 중 */
    private long watchUntil = -1;
    private Vec2 watchPost;

    private double sentDx = Double.NaN;
    private double sentDz = Double.NaN;
    private Vec2 sentFacing = new Vec2(0, -1);

    public ProctorBot(SimpleRng rng) {
        this.rng = rng;
    }

    @Override
    public List<GameCommand> decide(BotView v) {
        List<GameCommand> out = new ArrayList<>();
        if (v.seats.length == 0) return out;
        if (nav == null) {
            nav = new Nav(v.cfg, v.seats);
            sus = new double[v.seats.length];
        }
        perceive(v);
        for (int i = 0; i < sus.length; i++) sus[i] *= DECAY;

        if (!v.playing() || v.proctorStunned()) {
            stop(out);
            return out;
        }

        // 도구 빛을 봤으면 반응 시간 뒤 탐지기 (도구가 도는 2~4초 안에)
        if (detectAt >= 0 && v.now >= detectAt) {
            detectAt = -1;
            if (v.detectorsLeft > 0) out.add(new GameCommand.Detect());
        } else if (v.detectorsLeft >= 2 && v.now >= nextGambleAt && maxSus() < 1.5 && averageRising) {
            // 단서는 없는데 평균이 오르는 중 = 냥이가 어딘가서 활동 중 → 가끔 운에 맡김 (도구 쓰는 중이면 명중)
            nextGambleAt = v.now + 40_000 + rng.nextInt(30_000);
            out.add(new GameCommand.Detect());
        }

        if (v.now >= nextThinkAt) {
            nextThinkAt = v.now + 300 + rng.nextInt(400);
            think(v, out);
        }
        move(v, out);
        return out;
    }

    // ---------------------------------------------------------------------

    private void perceive(BotView v) {
        for (GameEvent e : v.fresh) {
            switch (e) {
                case GameEvent.Possessed x -> {
                    if (sees(v, x.seat())) {
                        // 움찔 = 냥이가 방금 여기로 옮김 → 다른 자리 의심은 크게 줄임
                        for (int i = 0; i < sus.length; i++) if (i != x.seat()) sus[i] *= 0.4;
                        sus[x.seat()] += 3;
                        seen(x.seat(), v.now);
                    }
                }
                case GameEvent.RemarkStarted x -> {
                    if (sees(v, x.seat())) {
                        sus[x.seat()] += 0.8;
                        if (x.seat() == presence) presenceAt = v.now;
                    }
                }
                case GameEvent.ToolStarted x -> {
                    // 서버가 시야·거리 안일 때만 보내줌 = 확실한 단서
                    for (int i = 0; i < sus.length; i++) if (i != x.seat()) sus[i] *= 0.5;
                    sus[x.seat()] += 6;
                    seen(x.seat(), v.now);
                    if (detectAt < 0) detectAt = v.now + 300 + rng.nextInt(700);
                }
                case GameEvent.DetectorResult x -> {
                    if (x.hit() && x.seat() >= 0) {
                        sus[x.seat()] += 8;
                        seen(x.seat(), v.now);
                    }
                }
                case GameEvent.CatchResult x -> {
                    if (x.success()) {
                        // 냥이가 다른 자리로 튕김 → 지금까지의 단서는 무효
                        for (int i = 0; i < sus.length; i++) sus[i] = 0;
                        presence = -1;
                    } else {
                        sus[x.seat()] = 0;
                        if (presence == x.seat()) presence = -1;
                    }
                    target = -1;
                }
                case GameEvent.InspectReady x -> evaluated = false;
                case GameEvent.AverageRevealed x -> {
                    // 학생 스스로 실수를 고쳐도 조금씩 오르내림 → 확실히 오를 때만 (냥이 2~3문항 고친 정도)
                    averageRising = !Double.isNaN(lastRevealed) && x.average() > lastRevealed + 1.5;
                    lastRevealed = x.average();
                }
                default -> {}
            }
        }
    }

    private void seen(int seat, long now) {
        presence = seat;
        presenceAt = now;
    }

    private boolean catIsAt(int seat, long now) {
        return presence == seat && now - presenceAt < PRESENCE_MS;
    }

    private double maxSus() {
        double m = 0;
        for (double s : sus) m = Math.max(m, s);
        return m;
    }

    private boolean sees(BotView v, int seat) {
        return EventFilter.inFov(v.proctorPos, v.proctorFacing, v.seats[seat], v.cfg.proctor().fovDeg());
    }

    private void think(BotView v, List<GameCommand> out) {
        int best = 0;
        for (int i = 1; i < sus.length; i++) if (sus[i] > sus[best]) best = i;

        // 더 수상한 학생이 생기면 그쪽으로
        if (sus[best] >= APPROACH && best != target && (target < 0 || sus[best] > sus[target] + 1)) {
            goTo(v, best);
            return;
        }

        if (target < 0) {
            if (watchUntil >= 0 && v.now < watchUntil) return;  // 지켜보는 중
            watchUntil = -1;
            if (rng.nextInt(100) < 45) {
                // 앞에 서서 교실 전체 지켜보기
                watchPost = new Vec2((rng.nextDouble() - 0.5) * 2.0, v.cfg.classroom().proctorSpawnZ() - 0.4);
                path = nav.path(v.proctorPos, watchPost);
                pathIdx = 0;
                watchUntil = v.now + 4_000 + rng.nextInt(5_000);
            } else {
                goTo(v, rng.nextInt(v.seats.length));  // 아무 학생 검사
            }
            return;
        }

        if (arrivedAt < 0) {
            // 이동 중 — 막혀서 안 움직이면 길 다시 찾기
            if (lastPos != null && lastPos.dist(v.proctorPos) > 0.05) lastProgressAt = v.now;
            lastPos = v.proctorPos;
            if (v.now - lastProgressAt > 2000) goTo(v, target);
            return;
        }

        // 학생 앞에 도착: 냥이가 지금 여기 있다는 최근 증거가 있으면 바로 파리채
        if (catIsAt(target, v.now) && v.swatsLeft > 0) {
            out.add(new GameCommand.Swat());
            target = -1;
            return;
        }
        // 아니면 검사 결과를 보고 다음으로 (다르게 마킹된 칸 = 냥이가 지나간 흔적 → 의심만 올림)
        StudentInfo st = v.inspected;
        if (!evaluated && v.inspectingSeat == target && st != null && st.seat() == target) {
            evaluated = true;
            int diff = 0;
            for (int q = 0; q < st.omr().size(); q++) {
                Boolean m = st.omr().get(q);
                if (m != null && !m.equals(st.paper().get(q))) diff++;
            }
            if (diff == 0) sus[target] *= 0.5;
            else if (diff == 1) sus[target] += 0.5;  // 학생 실수일 수도
            else sus[target] += 1.5 * diff;          // 실수는 한 번에 하나뿐 → 여러 개면 냥이 흔적
        }
        long stay = v.cfg.proctor().inspectHoldSec() > 0 ? Math.round(v.cfg.proctor().inspectHoldSec() * 1000) + 800 : 800;
        if (v.now - arrivedAt > stay) {
            sus[target] *= 0.6;
            target = -1;
        }
    }

    private void goTo(BotView v, int seat) {
        watchUntil = -1;
        target = seat;
        spot = nav.inspectSpot(v.seats[seat]);
        path = nav.path(v.proctorPos, spot);
        pathIdx = 0;
        arrivedAt = -1;
        evaluated = false;
        lastProgressAt = v.now;
        lastPos = v.proctorPos;
    }

    private void move(BotView v, List<GameCommand> out) {
        if (target < 0 && watchUntil < 0) {
            stop(out);
            lookAtClass(v, out);
            return;
        }
        if (target >= 0 && arrivedAt >= 0) {
            stop(out);
            face(out, v.seats[target].x() - v.proctorPos.x(), v.seats[target].z() - v.proctorPos.z());
            return;
        }
        while (pathIdx < path.size() && path.get(pathIdx).dist(v.proctorPos) < 0.15) pathIdx++;
        if (pathIdx >= path.size()) {
            if (target >= 0) arrivedAt = v.now;
            stop(out);
            lookAtClass(v, out);
            return;
        }
        Vec2 wp = path.get(pathIdx);
        double dx = wp.x() - v.proctorPos.x();
        double dz = wp.z() - v.proctorPos.z();
        double len = Math.sqrt(dx * dx + dz * dz);
        dx /= len;
        dz /= len;
        if (Double.isNaN(sentDx) || Math.abs(dx - sentDx) > 0.05 || Math.abs(dz - sentDz) > 0.05) {
            out.add(new GameCommand.Move(dx, dz));
            sentDx = dx;
            sentDz = dz;
        }
        lookAtClass(v, out);
    }

    /** 가장 수상한 학생, 없으면 교실 가운데를 봄 */
    private void lookAtClass(BotView v, List<GameCommand> out) {
        int best = 0;
        for (int i = 1; i < sus.length; i++) if (sus[i] > sus[best]) best = i;
        Vec2 at = sus[best] >= 1.0 ? v.seats[best] : new Vec2(0, -0.5);
        double fx = at.x() - v.proctorPos.x();
        double fz = at.z() - v.proctorPos.z();
        if (fx * fx + fz * fz < 1.0) {
            fx = 0;
            fz = -1;
        }
        face(out, fx, fz);
    }

    private void stop(List<GameCommand> out) {
        if (sentDx != 0 || sentDz != 0) {
            out.add(new GameCommand.Move(0, 0));
            sentDx = 0;
            sentDz = 0;
        }
    }

    private void face(List<GameCommand> out, double fx, double fz) {
        double len = Math.sqrt(fx * fx + fz * fz);
        if (len < 1e-6) return;
        Vec2 f = new Vec2(fx / len, fz / len);
        if (Math.abs(f.x() - sentFacing.x()) > 0.05 || Math.abs(f.z() - sentFacing.z()) > 0.05) {
            out.add(new GameCommand.Look(f.x(), f.z()));
            sentFacing = f;
        }
    }
}

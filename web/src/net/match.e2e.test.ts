import { describe, expect, it } from 'vitest';
import { Phase, RematchState, Role, type ServerMessage } from '../gen/catchnyang/v1/game_pb';
import { GameStore } from '../state/game-store';
import { Connection } from './connection';

const URL = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env.SERVER_URL ?? 'ws://localhost:8080/ws';

/** 화면 없이 연결 + 상태만 가진 클라 (앱이 하는 메시지 처리를 그대로) */
class Client {
  readonly store = new GameStore(() => performance.now());
  readonly inbox: ServerMessage[] = [];
  readonly conn = new Connection(URL, { onMessage: (m) => this.handle(m) });
  token = '';

  private handle(m: ServerMessage) {
    this.inbox.push(m);
    const p = m.payload;
    if (p.case === 'matchFound') {
      this.store.startMatch(p.value);
      this.token = p.value.sessionToken;
    } else if (p.case === 'snapshot') this.store.applySnapshot(p.value);
    else if (p.case === 'tick') this.store.applyTick(p.value);
    else if (p.case === 'rematchUpdate') this.store.setRematch(p.value.state, p.value.expiresMs);
  }

  async waitFor<T>(pick: (m: ServerMessage) => T | undefined, what: string, timeoutMs = 5000): Promise<T> {
    const deadline = Date.now() + timeoutMs;
    let i = 0;
    while (Date.now() < deadline) {
      for (; i < this.inbox.length; i++) {
        const v = pick(this.inbox[i]);
        if (v !== undefined) return v;
      }
      await sleep(20);
    }
    throw new Error('timeout: ' + what);
  }

  async until(cond: () => boolean, what: string, timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    while (!cond()) {
      if (Date.now() > deadline) throw new Error('timeout: ' + what);
      await sleep(20);
    }
  }
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('실제 서버와 한 판', () => {
  it('방 코드 매칭 → 진행 → 빙의·이동 → 기권 → 재대결(역할 바뀜)', async () => {
    const a = new Client();
    const b = new Client();
    await a.conn.open();
    await b.conn.open();

    a.conn.send({ case: 'createRoom', value: { nickname: 'e2e-a' } });
    const code = await a.waitFor((m) => (m.payload.case === 'roomCreated' ? m.payload.value.roomCode : undefined), 'roomCreated');
    b.conn.send({ case: 'joinRoom', value: { nickname: 'e2e-b', roomCode: code } });

    await a.until(() => a.store.hasSnapshot, 'snapshot a');
    await b.until(() => b.store.hasSnapshot, 'snapshot b');
    expect(a.store.role).not.toBe(b.store.role);
    const cat = a.store.role === Role.CAT ? a : b;
    const proctor = cat === a ? b : a;
    expect(cat.store.cat).not.toBeNull();
    expect(proctor.store.cat).toBeNull();          // 감독관에겐 냥이 정보가 아예 없음
    expect(cat.store.seats).toHaveLength(9);
    expect(cat.store.phase).toBe(Phase.PREPARE);

    // 준비 10초 → 진행. 냥이는 시작 자리와 그 학생 시험지를 받음
    await cat.until(() => cat.store.phase === Phase.PLAYING && (cat.store.cat?.seat ?? -1) >= 0, 'playing', 15_000);
    const start = cat.store.cat!.seat;
    expect(cat.store.currentStudent()?.paper).toHaveLength(20);

    // 빙의 이동 → ack + 새 학생 상세
    const to = (start + 1) % 9;
    const seq = cat.conn.send({ case: 'possess', value: { seat: to } });
    await cat.until(() => cat.store.ackSeq >= seq && cat.store.cat!.seat === to, 'possess ack');
    expect(cat.store.currentStudent()?.seat).toBe(to);
    expect(cat.store.possessCooldown()).toBeGreaterThan(2000);

    // 감독관 이동 → 둘 다 위치를 받음
    proctor.conn.send({ case: 'proctorMove', value: { dx: 1, dz: 0, faceX: 1, faceZ: -1 } });
    await cat.until(() => cat.store.proctorPos.x > 0.5, 'proctor moved (cat view)');
    await proctor.until(() => proctor.store.proctorPos.x > 0.5, 'proctor moved (proctor view)');
    proctor.conn.send({ case: 'proctorMove', value: { dx: 0, dz: 0 } });

    // 기권 → 결과 + 재대결 가능
    cat.conn.send({ case: 'leaveMatch', value: {} });
    await proctor.until(() => proctor.store.result !== null, 'result');
    expect(proctor.store.iWon()).toBe(true);
    expect(cat.store.iWon()).toBe(false);
    await cat.until(() => cat.store.rematchState === RematchState.AVAILABLE, 'rematch available');

    cat.conn.send({ case: 'requestRematch', value: {} });
    await proctor.until(() => proctor.store.rematchState === RematchState.OFFERED, 'offered');
    proctor.conn.send({ case: 'requestRematch', value: {} });

    // 역할이 바뀐 새 판
    await proctor.until(() => proctor.store.rematch && proctor.store.hasSnapshot && proctor.store.role === Role.CAT, 'rematch started');
    await cat.until(() => cat.store.rematch && cat.store.role === Role.PROCTOR, 'rematch started (other)');

    a.conn.send({ case: 'leaveMatch', value: {} });
    await a.until(() => a.store.result !== null, 'end rematch');
    a.conn.close();
    b.conn.close();
  });
});

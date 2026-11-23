import { describe, expect, it } from 'vitest';
import { Phase, RematchState, Role, type ServerMessage } from '../gen/catchnyang/v1/game_pb';
import { GameStore } from '../state/game-store';
import { LocalServer } from './local-server';

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** 앱이 하는 것처럼 메시지를 GameStore 에 적용하는 작은 하네스 */
function harness() {
  const store = new GameStore();
  const inbox: ServerMessage[] = [];
  const server = new LocalServer((m) => {
    inbox.push(m);
    const p = m.payload;
    if (p.case === 'matchFound') store.startMatch(p.value);
    else if (p.case === 'snapshot') store.applySnapshot(p.value);
    else if (p.case === 'tick') store.applyTick(p.value);
    else if (p.case === 'rematchUpdate') store.setRematch(p.value.state, p.value.expiresMs);
  });
  return { store, inbox, server };
}

async function until(cond: () => boolean, what: string, timeoutMs = 3000) {
  const deadline = Date.now() + timeoutMs;
  while (!cond()) {
    if (Date.now() > deadline) throw new Error('timeout: ' + what);
    await sleep(20);
  }
}

describe('싱글 모드 (브라우저 안 가짜 서버 + AI)', () => {
  it('시작 → 서버와 같은 메시지로 스냅샷·tick → 기권 → 결과 → 역할 바꿔 다시', async () => {
    const { store, inbox, server } = harness();
    server.start(Role.PROCTOR);

    const found = inbox[0];
    expect(found.payload.case).toBe('matchFound');
    expect(store.role).toBe(Role.PROCTOR);
    expect(store.opponentNickname).toBe('AI 냥이');

    await until(() => store.hasSnapshot, 'snapshot');
    expect(store.phase).toBe(Phase.PREPARE);
    expect(store.seats).toHaveLength(9);
    expect(store.proctor?.swatsLeft).toBe(3);
    expect(store.cat).toBeNull();                // 감독관에겐 냥이 정보가 없음 (싱글도 같은 필터)

    // 준비 중 이동 입력은 거절 + seq 가 돌아옴
    const seq = server.send({ case: 'proctorMove', value: { dx: 1, dz: 0, faceX: 1, faceZ: 0 } });
    await until(() => store.ackSeq >= seq, 'ack');
    await until(() => store.proctorFacing.x > 0.9, 'look applied in prepare');

    server.send({ case: 'leaveMatch', value: {} });
    await until(() => store.result !== null, 'result');
    expect(store.iWon()).toBe(false);
    await until(() => store.rematchState === RematchState.AVAILABLE, 'rematch available');

    server.send({ case: 'requestRematch', value: {} });
    await until(() => store.rematch && store.hasSnapshot, 'rematch started');
    expect(store.role).toBe(Role.CAT);           // 역할이 바뀜
    expect(store.opponentNickname).toBe('AI 감독관');
    expect(store.cat).not.toBeNull();
    server.close();
  });

  it('냥이로 하면 진행 단계에서 AI 감독관이 실제로 움직임', async () => {
    const { store, server } = harness();
    server.start(Role.CAT);
    await until(() => store.hasSnapshot, 'snapshot');
    const start = { ...store.proctorPos };
    // 준비 10초를 기다리지 않도록: 이 테스트는 20초 안에 끝나야 함
    await until(() => store.phase === Phase.PLAYING, 'playing', 15_000);
    await until(() => Math.hypot(store.proctorPos.x - start.x, store.proctorPos.z - start.z) > 0.5, 'AI proctor moved', 5000);
    expect(store.cat?.seat).toBeGreaterThanOrEqual(0);
    server.close();
  }, 25_000);
});

import { create, type MessageInitShape } from '@bufbuild/protobuf';
import { describe, expect, it } from 'vitest';
import {
  GameTickSchema,
  Mark,
  Phase,
  RejectReason,
  Role,
  SnapshotSchema,
  ToolType,
  type GameEvent,
  type GameTick,
  type Snapshot,
} from '../gen/catchnyang/v1/game_pb';
import { GameStore } from './game-store';

/** create() 에 넘기는 "부분 초기값" 모양 (완성된 메시지 타입은 뺌) */
type SnapInit = Exclude<MessageInitShape<typeof SnapshotSchema>, Snapshot>;
type TickInit = Exclude<MessageInitShape<typeof GameTickSchema>, GameTick>;

function clock(start = 1000) {
  const c = { t: start, now: () => c.t };
  return c;
}

const seats = Array.from({ length: 9 }, (_, i) => ({ x: ((i % 3) - 1) * 2.2, z: (Math.floor(i / 3) - 1) * 2.2 }));

function catSnapshot(over: SnapInit = {}) {
  return create(SnapshotSchema, {
    myRole: Role.CAT,
    phase: Phase.PLAYING,
    remainingMs: 60_000,
    target: 70,
    seats,
    questionCount: 20,
    proctor: { position: { x: 0, z: 4 }, facing: { x: 0, z: -1 } },
    cat: {
      seat: 4,
      possessCooldownMs: 0,
      liveAverage: 60,
      known: { 3: true },
      tools: Array(9).fill(ToolType.AI_GLASSES),
      current: {
        seat: 4,
        paper: Array(20).fill(true),
        omr: Array(20).fill(Mark.EMPTY),
        tool: ToolType.AI_GLASSES,
        toolChecksLeft: 9,
      },
    },
    ...over,
  });
}

function tick(events: TickInit['events'] = [], over: TickInit = {}) {
  return create(GameTickSchema, { phase: Phase.PLAYING, remainingMs: 59_000, events, ...over });
}

describe('GameStore', () => {
  it('스냅샷은 상태를 통째로 갈아끼움', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(catSnapshot());
    expect(s.role).toBe(Role.CAT);
    expect(s.cat?.seat).toBe(4);
    expect(s.cat?.known.get(3)).toBe(true);
    expect(s.currentStudent()?.toolChecksLeft).toBe(9);
    expect(s.proctor).toBeNull();

    // 두 번째 스냅샷: 이전 학생 상세는 사라지고 새 것만
    s.applySnapshot(catSnapshot({ cat: { seat: -1, tools: [], known: {} } }));
    expect(s.currentStudent()).toBeUndefined();
    expect(s.catStunned()).toBe(true);
  });

  it('남은 시간은 서버 값에서 흐른 시간을 뺌, tick 마다 다시 맞춤', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(catSnapshot());
    c.t += 1500;
    expect(s.remaining()).toBe(58_500);
    s.applyTick(tick([], { remainingMs: 58_000 }));
    expect(s.remaining()).toBe(58_000);
  });

  it('tick 에 안 온 필드는 그대로', () => {
    const s = new GameStore(clock().now);
    s.applySnapshot(catSnapshot());
    s.applyTick(tick());
    expect(s.proctorPos).toEqual({ x: 0, z: 4 });
    expect(s.cat?.liveAverage).toBe(60);
    s.applyTick(tick([], { liveAverage: 62.5, proctor: { position: { x: 1, z: 2 } } }));
    expect(s.cat?.liveAverage).toBe(62.5);
    expect(s.proctorPos).toEqual({ x: 1, z: 2 });
  });

  it('빙의 이동 → 자리 바뀌고 쿨타임, 새 학생 상세가 같은 tick 에 옴', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(catSnapshot());
    s.applyTick(
      tick([{ kind: { case: 'possessed', value: { seat: 2 } } }], {
        students: [{ seat: 2, paper: Array(20).fill(false), omr: Array(20).fill(Mark.X), tool: ToolType.SMART_WATCH, toolChecksLeft: 6 }],
      }),
    );
    expect(s.cat?.seat).toBe(2);
    expect(s.possessCooldown()).toBe(3000);
    expect(s.currentStudent()?.tool).toBe(ToolType.SMART_WATCH);
    c.t += 3000;
    expect(s.possessCooldown()).toBe(0);
  });

  it('도구: 시작하면 바쁨 + 남은 횟수 차감, 끝나면 정답을 앎', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(catSnapshot());
    s.noteToolRequest(3);
    s.applyTick(tick([{ kind: { case: 'toolStarted', value: { tool: ToolType.AI_GLASSES, seat: 4 } } }]));
    expect(s.catBusy()).toBe(true);
    expect(s.currentStudent()?.toolChecksLeft).toBe(6);
    s.applyTick(tick([{ kind: { case: 'toolFinished', value: { tool: ToolType.AI_GLASSES, seat: 4, revealed: { 0: false, 1: true } } } }]));
    expect(s.catBusy()).toBe(false);
    expect(s.cat?.known.get(0)).toBe(false);
    expect(s.cat?.known.get(1)).toBe(true);
  });

  it('OmrMarked 는 절대값 — 같은 게 두 번 와도 결과 같음', () => {
    const s = new GameStore(clock().now);
    s.applySnapshot(catSnapshot());
    const e = { kind: { case: 'omrMarked' as const, value: { seat: 4, question: 5, value: Mark.O } } };
    s.applyTick(tick([e, e]));
    expect(s.currentStudent()?.omr[5]).toBe(Mark.O);
  });

  it('적발당하면 기절 → CatRelocated 로 새 자리', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(catSnapshot());
    s.applyTick(tick([{ kind: { case: 'catchResult', value: { seat: 4, success: true } } }]));
    expect(s.catStunned()).toBe(true);
    expect(s.catchSuccesses).toBe(1);
    expect(s.currentStudent()).toBeUndefined();
    s.applyTick(tick([{ kind: { case: 'catRelocated', value: { seat: 7 } } }]));
    expect(s.cat?.seat).toBe(7);
    expect(s.catStunned()).toBe(false);
  });

  it('감독관: 헛손질하면 경직·횟수 차감, 탐지기 명중 자리는 2초만 표시', () => {
    const c = clock();
    const s = new GameStore(c.now);
    s.applySnapshot(create(SnapshotSchema, {
      myRole: Role.PROCTOR, phase: Phase.PLAYING, seats, remainingMs: 1000,
      proctorView: { swatsLeft: 3, detectorsLeft: 3, inspectingSeat: -1, visibleAverage: 58 },
    }));
    expect(s.cat).toBeNull();
    s.applyTick(tick([
      { kind: { case: 'catchResult', value: { seat: 1, success: false } } },
      { kind: { case: 'detectorResult', value: { hit: true, seat: 5 } } },
      { kind: { case: 'averageRevealed', value: { average: 61 } } },
    ]));
    expect(s.proctor?.swatsLeft).toBe(2);
    expect(s.proctorStunned()).toBe(true);
    expect(s.proctor?.detectorsLeft).toBe(2);
    expect(s.revealedSeats()).toEqual([5]);
    expect(s.average()).toBe(61);
    c.t += 2001;
    expect(s.revealedSeats()).toEqual([]);
  });

  it('감독관이 검사 중인 학생에게서 멀어지면 검사 해제', () => {
    const s = new GameStore(clock().now);
    s.applySnapshot(create(SnapshotSchema, {
      myRole: Role.PROCTOR, phase: Phase.PLAYING, seats,
      proctorView: { swatsLeft: 3, detectorsLeft: 3, inspectingSeat: -1 },
    }));
    s.applyTick(tick([{ kind: { case: 'inspectReady', value: { seat: 4 } } }], {
      students: [{ seat: 4, paper: Array(20).fill(true), omr: Array(20).fill(Mark.O) }],
    }));
    expect(s.inspectedStudent()?.seat).toBe(4);
    s.updateInspectRange({ x: 0.5, z: 0.3 });
    expect(s.inspectedStudent()?.seat).toBe(4);
    s.updateInspectRange({ x: 2, z: 0 });
    expect(s.inspectedStudent()).toBeUndefined();
  });

  it('사건·거절은 구독자에게 순서대로 전달', () => {
    const s = new GameStore(clock().now);
    s.applySnapshot(catSnapshot());
    const seen: string[] = [];
    s.onEvent((e: GameEvent) => seen.push(e.kind.case ?? ''));
    const rejected: RejectReason[] = [];
    s.onRejected((r) => rejected.push(r.reason));
    s.applyTick(tick(
      [{ kind: { case: 'targetChanged', value: { target: 80 } } }, { kind: { case: 'phaseChanged', value: { phase: Phase.ENDED } } }],
      { rejected: [{ seq: 3, reason: RejectReason.COOLDOWN }] },
    ));
    expect(seen).toEqual(['targetChanged', 'phaseChanged']);
    expect(rejected).toEqual([RejectReason.COOLDOWN]);
    expect(s.target).toBe(80);
  });

  it('도배로 버려진 거절(시계 없음)은 시간을 건드리지 않음', () => {
    const s = new GameStore(clock().now);
    s.applySnapshot(catSnapshot());
    s.applyTick(create(GameTickSchema, { rejected: [{ seq: 9, reason: RejectReason.RATE_LIMITED }] }));
    expect(s.phase).toBe(Phase.PLAYING);
    expect(s.remaining()).toBe(60_000);
  });
});

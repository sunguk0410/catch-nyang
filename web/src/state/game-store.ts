import {
  EndReason,
  Mark,
  Phase,
  RejectReason,
  RematchState,
  Role,
  ToolType,
  type GameEvent,
  type GameTick,
  type MatchFound,
  type Snapshot,
  type StudentDetail,
} from '../gen/catchnyang/v1/game_pb';
import { CFG, toolSpec } from '../config';

export interface Vec2 {
  x: number;
  z: number;
}

export interface StudentState {
  seat: number;
  paper: boolean[];
  omr: Mark[];
  tool: ToolType;
  toolChecksLeft: number;
}

export interface CatState {
  /** 빙의 중인 자리, -1 = 시작 전·기절 중 */
  seat: number;
  stunnedUntil: number;
  possessReadyAt: number;
  /** 확인한 문항 → 정답 (O=true) */
  known: Map<number, boolean>;
  liveAverage: number;
  /** 도구 사용·마킹이 끝나는 시각 (그 전엔 다른 행동 불가) */
  busyUntil: number;
  busyKind: 'tool' | 'remark' | null;
  /** 자리별 지참 도구 */
  tools: ToolType[];
}

export interface ProctorState {
  visibleAverage: number;
  swatsLeft: number;
  detectorsLeft: number;
  stunnedUntil: number;
  /** 검사 중인 자리 (서버가 알려준 것), -1 = 없음 */
  inspectingSeat: number;
  /** 탐지기에 걸린 자리 → 미니맵 표시가 끝나는 시각 */
  revealed: Map<number, number>;
}

export interface MatchResult {
  winner: Role;
  reason: EndReason;
  finalAverage: number;
  finalTarget: number;
}

export interface RejectedNotice {
  seq: number;
  reason: RejectReason;
  at: number;
}

type Listener<T> = (v: T) => void;

/**
 * 클라 쪽 게임 상태. 서버 메시지만으로 바뀐다 (규칙 판정은 서버가 함).
 * <ul>
 *   <li>{@link applySnapshot}: 통째로 갈아끼움</li>
 *   <li>{@link applyTick}: 바뀐 것만 반영. 안 온 필드는 그대로</li>
 * </ul>
 * 화면은 매 프레임 이 객체를 읽어 그리고, 순간 연출(움찔·이펙트)은 {@link onEvent} 로 받는다.
 */
export class GameStore {
  // ---------- 판 ----------
  roomId = '';
  role: Role = Role.UNSPECIFIED;
  opponentNickname = '';
  rematch = false;

  phase: Phase = Phase.UNSPECIFIED;
  matchTimeMs = 0;
  private remainingMs = 0;
  private remainingSyncedAt = 0;
  target = CFG.match.initialTarget;
  catchSuccesses = 0;
  questionCount = CFG.match.questionCount;
  seats: Vec2[] = [];
  proctorPos: Vec2 = { x: CFG.classroom.proctorSpawnX, z: CFG.classroom.proctorSpawnZ };
  proctorFacing: Vec2 = { x: 0, z: -1 };
  /** 서버가 마지막으로 보낸 감독관 위치를 받은 시각 (보간용) */
  proctorSyncedAt = 0;
  ackSeq = 0;
  hasSnapshot = false;

  cat: CatState | null = null;
  proctor: ProctorState | null = null;
  /** 내가 지금 볼 수 있는 학생들의 시험지·OMR (냥이: 빙의한 학생, 감독관: 검사한 학생) */
  students = new Map<number, StudentState>();

  result: MatchResult | null = null;
  rematchState: RematchState = RematchState.UNSPECIFIED;
  private rematchExpiresAt = 0;
  opponentConnected = true;
  private opponentGraceUntil = 0;

  /** 보냈지만 아직 결과가 안 온 도구 사용 문항 수 (남은 확인 횟수 갱신용) */
  private pendingToolQuestions = 0;

  private readonly eventListeners: Listener<GameEvent>[] = [];
  private readonly rejectListeners: Listener<RejectedNotice>[] = [];

  constructor(private readonly now: () => number = () => performance.now()) {}

  // =====================================================================
  // 구독
  // =====================================================================

  onEvent(l: Listener<GameEvent>): () => void {
    this.eventListeners.push(l);
    return () => this.eventListeners.splice(this.eventListeners.indexOf(l), 1);
  }

  onRejected(l: Listener<RejectedNotice>): () => void {
    this.rejectListeners.push(l);
    return () => this.rejectListeners.splice(this.rejectListeners.indexOf(l), 1);
  }

  // =====================================================================
  // 서버 메시지 적용
  // =====================================================================

  startMatch(m: MatchFound): void {
    this.roomId = m.roomId;
    this.role = m.myRole;
    this.opponentNickname = m.opponentNickname;
    this.rematch = m.rematch;
    this.result = null;
    this.rematchState = RematchState.UNSPECIFIED;
    this.opponentConnected = true;
    this.hasSnapshot = false;
    this.students.clear();
  }

  applySnapshot(s: Snapshot): void {
    const now = this.now();
    this.role = s.myRole;
    this.phase = s.phase;
    this.matchTimeMs = s.matchTimeMs;
    this.syncRemaining(s.remainingMs);
    this.target = s.target;
    this.catchSuccesses = s.catchSuccesses;
    this.questionCount = s.questionCount;
    this.seats = s.seats.map((v) => ({ x: v.x, z: v.z }));
    if (s.proctor?.position) this.proctorPos = { x: s.proctor.position.x, z: s.proctor.position.z };
    if (s.proctor?.facing) this.proctorFacing = { x: s.proctor.facing.x, z: s.proctor.facing.z };
    this.proctorSyncedAt = now;
    this.students.clear();
    this.pendingToolQuestions = 0;

    if (s.cat) {
      const c = s.cat;
      this.cat = {
        seat: c.seat,
        stunnedUntil: c.stunned ? now + c.stunnedMs : 0,
        possessReadyAt: now + c.possessCooldownMs,
        known: new Map(Object.entries(c.known).map(([q, a]) => [Number(q), a])),
        liveAverage: c.liveAverage,
        // 스냅샷엔 언제 끝나는지가 없음 → 도구라 치고 가장 긴 고정 시간만큼 (다음 사건이 오면 바로 풀림)
        busyUntil: c.busy ? now + 4000 : 0,
        busyKind: c.busy ? 'tool' : null,
        tools: [...c.tools],
      };
      if (c.current) this.setStudent(c.current);
    } else {
      this.cat = null;
    }

    if (s.proctorView) {
      const p = s.proctorView;
      this.proctor = {
        visibleAverage: p.visibleAverage,
        swatsLeft: p.swatsLeft,
        detectorsLeft: p.detectorsLeft,
        stunnedUntil: now + p.stunnedMs,
        inspectingSeat: p.inspectingSeat,
        revealed: new Map(),
      };
      if (p.inspecting) this.setStudent(p.inspecting);
    } else {
      this.proctor = null;
    }
    this.hasSnapshot = true;
  }

  applyTick(t: GameTick): void {
    const now = this.now();
    // 입력 도배로 버려진 경우엔 시계 없이 rejected 만 옴
    if (t.phase !== Phase.UNSPECIFIED) {
      this.phase = t.phase;
      this.matchTimeMs = t.matchTimeMs;
      this.syncRemaining(t.remainingMs);
      if (t.ackSeq > this.ackSeq) this.ackSeq = t.ackSeq;
    }
    if (t.proctor?.position) {
      this.proctorPos = { x: t.proctor.position.x, z: t.proctor.position.z };
      this.proctorSyncedAt = now;
    }
    if (t.proctor?.facing) this.proctorFacing = { x: t.proctor.facing.x, z: t.proctor.facing.z };
    if (t.liveAverage !== undefined && this.cat) this.cat.liveAverage = t.liveAverage;

    for (const e of t.events) this.applyEvent(e, now);
    // 학생 상세는 사건(빙의·검사 시작) 뒤에 온다 — 같은 tick 의 OmrMarked 보다 최신
    for (const d of t.students) this.setStudent(d);

    for (const r of t.rejected) {
      if (r.reason === RejectReason.COOLDOWN && this.cat) {
        // 쿨타임 표시가 서버와 어긋났으면 조금 더 기다리게
        this.cat.possessReadyAt = Math.max(this.cat.possessReadyAt, now + 300);
      }
      const notice = { seq: r.seq, reason: r.reason, at: now };
      for (const l of this.rejectListeners) l(notice);
    }
  }

  private applyEvent(e: GameEvent, now: number): void {
    const k = e.kind;
    const cat = this.cat;
    const proctor = this.proctor;
    switch (k.case) {
      case 'phaseChanged':
        this.phase = k.value.phase;
        break;
      case 'possessed':
        if (cat) {
          cat.seat = k.value.seat;
          cat.possessReadyAt = now + CFG.cat.possessCooldownSec * 1000;
        }
        break;
      case 'catRelocated':
        if (cat) {
          cat.seat = k.value.seat;
          cat.stunnedUntil = 0;
        }
        break;
      case 'toolStarted':
        if (cat && k.value.seat === cat.seat) {
          const spec = toolSpec(k.value.tool);
          cat.busyKind = 'tool';
          cat.busyUntil = now + (spec?.lockSec ?? 4) * 1000;
          const st = this.students.get(cat.seat);
          if (st) st.toolChecksLeft = Math.max(0, st.toolChecksLeft - this.pendingToolQuestions);
          this.pendingToolQuestions = 0;
        }
        break;
      case 'toolFinished':
        if (cat) {
          for (const [q, a] of Object.entries(k.value.revealed)) cat.known.set(Number(q), a);
          if (cat.busyKind === 'tool') this.clearBusy();
        }
        break;
      case 'toolDestroyed': {
        if (cat) {
          cat.tools[k.value.seat] = ToolType.UNSPECIFIED;
          if (k.value.seat === cat.seat && cat.busyKind === 'tool') this.clearBusy();
        }
        const st = this.students.get(k.value.seat);
        if (st) {
          st.tool = ToolType.UNSPECIFIED;
          st.toolChecksLeft = 0;
        }
        break;
      }
      case 'remarkStarted':
        if (cat && k.value.byCat) {
          cat.busyKind = 'remark';
          cat.busyUntil = now + CFG.cat.remarkSec * 1000;
        }
        break;
      case 'omrMarked': {
        const st = this.students.get(k.value.seat);
        if (st) st.omr[k.value.question] = k.value.value;
        if (cat && cat.busyKind === 'remark' && k.value.seat === cat.seat) this.clearBusy();
        break;
      }
      case 'inspectReady':
        if (proctor) proctor.inspectingSeat = k.value.seat;
        break;
      case 'catchResult':
        if (proctor) {
          proctor.swatsLeft = Math.max(0, proctor.swatsLeft - 1);
          if (!k.value.success) proctor.stunnedUntil = now + CFG.proctor.swatter.failStunSec * 1000;
        }
        if (k.value.success) {
          this.catchSuccesses++;
          if (cat) {
            // 기절 → 기절이 끝나면 CatRelocated 로 새 자리
            cat.seat = -1;
            cat.stunnedUntil = now + CFG.cat.stunSec * 1000;
            this.clearBusy();
            this.students.clear();
          }
        }
        break;
      case 'detectorResult':
        if (proctor) {
          proctor.detectorsLeft = Math.max(0, proctor.detectorsLeft - 1);
          if (k.value.hit && k.value.seat >= 0) {
            proctor.revealed.set(k.value.seat, now + CFG.proctor.detector.revealSec * 1000);
          }
        }
        break;
      case 'targetChanged':
        this.target = k.value.target;
        break;
      case 'averageRevealed':
        if (proctor) proctor.visibleAverage = k.value.average;
        break;
      case 'matchEnded':
        this.phase = Phase.ENDED;
        this.result = {
          winner: k.value.winner,
          reason: k.value.reason,
          finalAverage: k.value.finalAverage,
          finalTarget: k.value.finalTarget,
        };
        break;
      case undefined:
        break;
    }
    for (const l of this.eventListeners) l(e);
  }

  private clearBusy(): void {
    if (!this.cat) return;
    this.cat.busyUntil = 0;
    this.cat.busyKind = null;
  }

  private setStudent(d: StudentDetail): void {
    this.students.set(d.seat, {
      seat: d.seat,
      paper: [...d.paper],
      omr: [...d.omr],
      tool: d.tool,
      toolChecksLeft: d.toolChecksLeft,
    });
  }

  private syncRemaining(ms: number): void {
    this.remainingMs = ms;
    this.remainingSyncedAt = this.now();
  }

  // ---------- 로비 쪽 메시지 ----------

  setRematch(state: RematchState, expiresMs: number): void {
    this.rematchState = state;
    this.rematchExpiresAt = this.now() + expiresMs;
  }

  setOpponentConnection(connected: boolean, graceMs: number): void {
    this.opponentConnected = connected;
    this.opponentGraceUntil = connected ? 0 : this.now() + graceMs;
  }

  // =====================================================================
  // 내가 보낸 입력 (결과는 서버가 사건으로 알려줌)
  // =====================================================================

  /** UseTool 을 보낼 때 — 도구가 시작되면 그만큼 남은 횟수를 깎으려고 기억 */
  noteToolRequest(questions: number): void {
    this.pendingToolQuestions = questions;
  }

  /** 감독관이 검사 중이던 학생에게서 멀어지면 검사 해제 (서버는 따로 안 알려줌) */
  updateInspectRange(pos: Vec2): void {
    const p = this.proctor;
    if (!p || p.inspectingSeat < 0) return;
    const seat = this.seats[p.inspectingSeat];
    if (!seat || Math.hypot(seat.x - pos.x, seat.z - pos.z) > CFG.classroom.interactRangeM + 0.05) {
      this.students.delete(p.inspectingSeat);
      p.inspectingSeat = -1;
    }
  }

  // =====================================================================
  // 읽기 도우미 (화면용)
  // =====================================================================

  /** 현재 단계 남은 시간 (ms) — 서버 값을 받은 뒤 흐른 시간만큼 빼서 매 프레임 부드럽게 */
  remaining(): number {
    if (this.phase === Phase.ENDED) return 0;
    return Math.max(0, this.remainingMs - (this.now() - this.remainingSyncedAt));
  }

  rematchRemaining(): number {
    return Math.max(0, this.rematchExpiresAt - this.now());
  }

  opponentGraceRemaining(): number {
    return this.opponentConnected ? 0 : Math.max(0, this.opponentGraceUntil - this.now());
  }

  /** 내 화면에 보이는 반 평균 (냥이: 실시간, 감독관: 10초마다 공개값) */
  average(): number {
    if (this.cat) return this.cat.liveAverage;
    if (this.proctor) return this.proctor.visibleAverage;
    return 0;
  }

  catBusy(): boolean {
    return !!this.cat && this.cat.busyUntil > this.now();
  }

  catStunned(): boolean {
    return !!this.cat && (this.cat.seat < 0 || this.cat.stunnedUntil > this.now());
  }

  possessCooldown(): number {
    return this.cat ? Math.max(0, this.cat.possessReadyAt - this.now()) : 0;
  }

  proctorStunned(): boolean {
    return !!this.proctor && this.proctor.stunnedUntil > this.now();
  }

  /** 지금 빙의한 학생 (시험지·OMR·도구) */
  currentStudent(): StudentState | undefined {
    return this.cat && this.cat.seat >= 0 ? this.students.get(this.cat.seat) : undefined;
  }

  inspectedStudent(): StudentState | undefined {
    return this.proctor && this.proctor.inspectingSeat >= 0 ? this.students.get(this.proctor.inspectingSeat) : undefined;
  }

  /** 탐지기로 드러난 자리 중 아직 표시 중인 것 */
  revealedSeats(): number[] {
    if (!this.proctor) return [];
    const now = this.now();
    const out: number[] = [];
    for (const [seat, until] of this.proctor.revealed) {
      if (until > now) out.push(seat);
      else this.proctor.revealed.delete(seat);
    }
    return out;
  }

  iWon(): boolean {
    return !!this.result && this.result.winner === this.role;
  }
}

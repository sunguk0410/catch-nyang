import { EndReason, Phase, RejectReason, RematchState, Role, ToolType } from '../gen/catchnyang/v1/game_pb';
import { CFG, TOOL_NAMES, toolSpec, type LookMode } from '../config';
import type { GameStore } from '../state/game-store';
import type { GameScene } from '../scene/game-scene';
import { seatLabel, toMinimap } from '../scene/layout';
import { h, mmss, setText, show, toggleClass } from './dom';
import { OmrPanel } from './omr-panel';

export interface HudActions {
  possess(seat: number): void;
  useTool(questions: number[]): void;
  remark(question: number): void;
  setLookMode(mode: LookMode): void;
  swat(): void;
  detect(): void;
  leave(): void;
  requestRematch(): void;
  declineRematch(): void;
  backToLobby(): void;
}

const TOOL_ICON: Record<ToolType, string> = {
  [ToolType.UNSPECIFIED]: '—',
  [ToolType.AI_GLASSES]: '🕶️',
  [ToolType.SMART_WATCH]: '⌚',
  [ToolType.ERASER_CAMERA]: '📷',
  [ToolType.WIRELESS_EARPHONE]: '🎧',
};

const REJECT_TEXT: Partial<Record<RejectReason, string>> = {
  [RejectReason.WRONG_PHASE]: '아직 시작 전이에요',
  [RejectReason.STUNNED]: '지금은 움직일 수 없어요',
  [RejectReason.BUSY]: '도구 사용·마킹이 끝나야 해요',
  [RejectReason.COOLDOWN]: '빙의 쿨타임이에요',
  [RejectReason.INVALID]: '할 수 없는 행동이에요',
  [RejectReason.NOT_KNOWN]: 'AI 로 확인한 문항만 고칠 수 있어요',
  [RejectReason.NO_CHANGE]: '이미 정답이에요',
  [RejectReason.NO_USES_LEFT]: '남은 횟수가 없어요',
  [RejectReason.NO_TOOL]: '이 학생은 도구가 없어요',
  [RejectReason.OUT_OF_RANGE]: '학생 바로 앞에서 써야 해요',
  [RejectReason.RATE_LIMITED]: '입력이 너무 많아요',
};

const END_TEXT: Record<EndReason, string> = {
  [EndReason.UNSPECIFIED]: '',
  [EndReason.AVG_REACHED]: '시간 종료 — 반 평균이 목표를 넘었다',
  [EndReason.AVG_FAILED]: '시간 종료 — 반 평균이 목표에 못 미쳤다',
  [EndReason.CATCH_3]: '냥이를 3번 적발했다',
  [EndReason.DISCONNECT]: '상대가 나갔다',
};

/** 게임 중 화면 위 UI. 판마다 역할에 맞게 다시 만들고({@link mount}), 매 프레임 {@link update} */
export class Hud {
  readonly el = h('div', { class: 'hud' });
  private parts: { update(): void }[] = [];
  private readonly toasts = h('div', { class: 'toasts' });

  /** 냥이 시험지 접기/펴기 (Tab). 판마다 HUD 를 다시 만들어도 키 리스너는 하나만 */
  private paperToggle: (() => void) | null = null;

  constructor(private readonly store: GameStore, private readonly scene: GameScene, private readonly actions: HudActions) {
    store.onRejected((r) => {
      const text = REJECT_TEXT[r.reason];
      if (text) this.toast(text);
    });
    window.addEventListener('keydown', (ev) => {
      if (ev.code === 'Tab' && this.paperToggle && this.el.isConnected) {
        ev.preventDefault();
        this.paperToggle();
      }
    });
  }

  mount(): void {
    this.el.replaceChildren();
    this.parts = [];
    this.paperToggle = null;
    this.paper = undefined;
    const add = (p: { el: HTMLElement; update(): void }) => {
      this.el.append(p.el);
      this.parts.push(p);
    };
    add(this.topBar());
    if (this.store.role === Role.CAT) {
      add(this.catSeats());
      add(this.catTool());
      add(this.catLook());
      add(this.catPaper());
      add(this.stunOverlay('기절! 잠시 후 다른 학생에게 튕겨 나가요', () => this.store.catStunned() && this.store.phase === Phase.PLAYING));
    } else {
      add(this.proctorMap());
      add(this.proctorUses());
      add(this.proctorHelp());
      add(this.proctorInspect());
      add(this.crosshair());
      add(this.stunOverlay('헛손질! 잠깐 경직', () => this.store.proctorStunned()));
    }
    add(this.prepareOverlay());
    add(this.opponentBanner());
    add(this.result());
    this.el.append(this.toasts);
  }

  update(): void {
    for (const p of this.parts) p.update();
  }

  toast(text: string): void {
    const t = h('div', { class: 'toast', text });
    this.toasts.append(t);
    setTimeout(() => t.classList.add('out'), 1800);
    setTimeout(() => t.remove(), 2200);
    while (this.toasts.children.length > 4) this.toasts.firstElementChild?.remove();
  }

  // =====================================================================
  // 공통
  // =====================================================================

  private topBar() {
    const s = this.store;
    const time = h('div', { class: 'tb-time' });
    const phase = h('div', { class: 'tb-phase' });
    const avg = h('div', { class: 'tb-num' });
    const avgLabel = h('div', { class: 'tb-label', text: s.role === Role.CAT ? '반 평균 (실시간)' : '반 평균 (10초마다)' });
    const target = h('div', { class: 'tb-num' });
    const catches = h('div', { class: 'tb-num' });
    const role = h('div', { class: `tb-role ${s.role === Role.CAT ? 'cat' : 'proctor'}`, text: s.role === Role.CAT ? '🐱 냥이' : '👮 감독관' });
    const opp = h('div', { class: 'tb-opp', text: `vs ${s.opponentNickname}` });
    const leave = h('button', { class: 'btn ghost small', text: '기권', onclick: () => {
      if (confirm('기권하면 상대가 이겨요. 나갈까요?')) this.actions.leave();
    } });
    const avgBox = h('div', { class: 'tb-stat' }, avg, avgLabel);
    const el = h('div', { class: 'topbar' },
      h('div', { class: 'tb-left' }, role, opp),
      h('div', { class: 'tb-center' },
        avgBox,
        h('div', { class: 'tb-stat main' }, time, phase),
        h('div', { class: 'tb-stat' }, target, h('div', { class: 'tb-label', text: '목표 점수' })),
        h('div', { class: 'tb-stat' }, catches, h('div', { class: 'tb-label', text: '적발' })),
      ),
      h('div', { class: 'tb-right' }, leave));
    return {
      el,
      update: () => {
        setText(time, mmss(s.remaining()));
        setText(phase, s.phase === Phase.PREPARE ? '준비' : s.phase === Phase.ENDED ? '종료' : '남은 시간');
        const a = s.average();
        setText(avg, a.toFixed(1));
        setText(target, String(s.target));
        setText(catches, `${s.catchSuccesses} / ${CFG.proctor.swatter.winOnSuccessCount}`);
        toggleClass(avgBox, 'good', s.role === Role.CAT && a >= s.target);
        toggleClass(avgBox, 'bad', s.role === Role.CAT && a < s.target);
        toggleClass(time, 'urgent', s.phase === Phase.PLAYING && s.remaining() < 30_000);
        show(leave, s.phase !== Phase.ENDED);
      },
    };
  }

  private prepareOverlay() {
    const s = this.store;
    const num = h('div', { class: 'prep-num' });
    const hint = h('div', { class: 'prep-hint', text: s.role === Role.CAT
      ? '학생 몸에 빙의해서 AI 도구로 정답을 알아내고, OMR 을 고쳐 반 평균을 목표까지 올리세요'
      : '이상하게 마킹하는 학생을 찾아 파리채로 적발하세요. 3번 적발하거나 평균이 목표 미만이면 승리' });
    const el = h('div', { class: 'prepare' }, h('div', { class: 'prep-title', text: s.role === Role.CAT ? '🐱 당신은 냥이' : '👮 당신은 감독관' }), num, hint);
    return {
      el,
      update: () => {
        const on = s.phase === Phase.PREPARE && s.hasSnapshot;
        show(el, on);
        if (on) setText(num, String(Math.ceil(s.remaining() / 1000)));
      },
    };
  }

  private opponentBanner() {
    const s = this.store;
    const text = h('span');
    const el = h('div', { class: 'banner warn' }, text);
    return {
      el,
      update: () => {
        const on = !s.opponentConnected && s.phase !== Phase.ENDED;
        show(el, on);
        if (on) setText(text, `상대 연결이 끊겼어요 — ${Math.ceil(s.opponentGraceRemaining() / 1000)}초 안에 안 돌아오면 승리`);
      },
    };
  }

  private stunOverlay(message: string, active: () => boolean) {
    const el = h('div', { class: 'stun' }, h('div', { class: 'stun-text', text: message }));
    return { el, update: () => show(el, active()) };
  }

  private result() {
    const s = this.store;
    const title = h('div', { class: 'res-title' });
    const reason = h('div', { class: 'res-reason' });
    const score = h('div', { class: 'res-score' });
    const rematchText = h('div', { class: 'res-rematch-text' });
    const btnRematch = h('button', { class: 'btn primary', text: '역할 바꿔 한 판 더', onclick: () => this.actions.requestRematch() });
    const btnAccept = h('button', { class: 'btn primary', text: '수락', onclick: () => this.actions.requestRematch() });
    const btnDecline = h('button', { class: 'btn', text: '거절', onclick: () => this.actions.declineRematch() });
    const btnLobby = h('button', { class: 'btn ghost', text: '로비로', onclick: () => this.actions.backToLobby() });
    const card = h('div', { class: 'res-card' }, title, reason, score,
      h('div', { class: 'res-rematch' }, rematchText, h('div', { class: 'row' }, btnRematch, btnAccept, btnDecline)), btnLobby);
    const el = h('div', { class: 'result' }, card);
    return {
      el,
      update: () => {
        const r = s.result;
        show(el, !!r);
        if (!r) return;
        const won = s.iWon();
        setText(title, won ? '승리!' : '패배');
        toggleClass(card, 'won', won);
        setText(reason, END_TEXT[r.reason]);
        setText(score, `최종 평균 ${r.finalAverage.toFixed(1)}점 · 목표 ${r.finalTarget}점`);
        const st = s.rematchState;
        const left = Math.ceil(s.rematchRemaining() / 1000);
        setText(rematchText, {
          [RematchState.UNSPECIFIED]: '',
          [RematchState.AVAILABLE]: `${left}초 안에 재대결을 신청할 수 있어요`,
          [RematchState.WAITING]: `상대 응답 기다리는 중… (${left}초)`,
          [RematchState.OFFERED]: `상대가 재대결을 신청했어요! (${left}초)`,
          [RematchState.DECLINED]: '상대가 거절했거나 나갔어요',
          [RematchState.EXPIRED]: '재대결 시간이 지났어요',
        }[st]);
        show(btnRematch, st === RematchState.AVAILABLE);
        show(btnAccept, st === RematchState.OFFERED);
        show(btnDecline, st === RematchState.OFFERED || st === RematchState.WAITING);
        setText(btnDecline, st === RematchState.WAITING ? '신청 취소' : '거절');
      },
    };
  }

  // =====================================================================
  // 냥이
  // =====================================================================

  /** 우상단: 자리 선택(3×3) 겸 미니맵. 감독관은 내 시야 안일 때만 점으로 표시 */
  private catSeats() {
    const s = this.store;
    const buttons: { seat: number; el: HTMLButtonElement; tool: HTMLElement }[] = [];
    const grid = h('div', { class: 'seatgrid' });
    const order = s.seats.map((_, i) => i).sort((a, b) => seatLabel(a) - seatLabel(b));
    for (const seat of order) {
      const tool = h('span', { class: 'seat-tool' });
      const el = h('button', { class: 'seat', onclick: () => this.actions.possess(seat) },
        h('span', { class: 'seat-num', text: String(seatLabel(seat)) }), tool);
      buttons.push({ seat, el, tool });
      grid.append(el);
    }
    const dot = h('div', { class: 'mm-proctor' });
    const cd = h('div', { class: 'seat-cd' });
    // 점 좌표는 칠판 표시 아래 영역 기준 (자리 버튼과 같은 영역)
    const area = h('div', { class: 'mm-area' }, dot);
    const map = h('div', { class: 'minimap' }, h('div', { class: 'mm-board', text: '칠판' }), grid, area);
    const el = h('div', { class: 'panel tr' }, h('div', { class: 'panel-title', text: '빙의 이동' }), map, cd);
    return {
      el,
      update: () => {
        const c = s.cat;
        if (!c) return;
        const cooldown = s.possessCooldown();
        const blocked = cooldown > 0 || s.catBusy() || s.catStunned() || s.phase !== Phase.PLAYING;
        for (const b of buttons) {
          toggleClass(b.el, 'me', b.seat === c.seat);
          b.el.disabled = blocked || b.seat === c.seat;
          setText(b.tool, TOOL_ICON[c.tools[b.seat] ?? ToolType.UNSPECIFIED]);
          b.el.title = `${seatLabel(b.seat)}번 · ${TOOL_NAMES[c.tools[b.seat] ?? ToolType.UNSPECIFIED]}`;
        }
        setText(cd, cooldown > 0 ? `쿨타임 ${(cooldown / 1000).toFixed(1)}초` : s.catBusy() ? '도구·마킹 중엔 이동 불가' : '다른 학생을 누르거나 3D 화면에서 클릭');
        const visible = this.scene.proctorInCatView();
        show(dot, visible);
        if (visible) {
          const m = toMinimap(s.proctorPos, CFG.classroom.halfWidthM, CFG.classroom.halfDepthM);
          dot.style.left = `${m.left * 100}%`;
          dot.style.top = `${m.top * 100}%`;
        }
      },
    };
  }

  /** 우하단: 빙의한 학생의 도구 */
  private catTool() {
    const s = this.store;
    const name = h('div', { class: 'tool-name' });
    const info = h('div', { class: 'tool-info' });
    const bar = h('div', { class: 'bar-fill' });
    const progress = h('div', { class: 'bar' }, bar);
    const btn = h('button', { class: 'btn primary', onclick: () => {
      const qs = [...this.paper!.selected].sort((a, b) => a - b);
      if (qs.length === 0) {
        this.toast('시험지에서 확인할 문항을 먼저 체크하세요');
        return;
      }
      this.actions.useTool(qs);
      this.paper!.clearSelection();
    } });
    const el = h('div', { class: 'panel br' }, h('div', { class: 'panel-title', text: '도구' }), name, info, progress, btn);
    let busyStart = 0;
    let busyTotal = 1;
    return {
      el,
      update: () => {
        const st = s.currentStudent();
        const tool = st?.tool ?? ToolType.UNSPECIFIED;
        const spec = toolSpec(tool);
        setText(name, `${TOOL_ICON[tool]} ${TOOL_NAMES[tool]}`);
        const left = st?.toolChecksLeft ?? 0;
        setText(info, spec ? `남은 확인 ${left} / ${spec.totalChecks} · 한 번에 ${spec.perUse}문항 · ${spec.lockSec}초 고정` : '이 학생은 도구가 없어요 — 다른 학생으로 옮기세요');
        const busy = s.catBusy();
        const c = s.cat;
        if (busy && c) {
          if (busyStart === 0) {
            busyStart = performance.now();
            busyTotal = Math.max(1, c.busyUntil - busyStart);
          }
          bar.style.width = `${Math.min(100, ((performance.now() - busyStart) / busyTotal) * 100)}%`;
        } else {
          busyStart = 0;
          bar.style.width = '0%';
        }
        show(progress, busy);
        const n = this.paper?.selected.size ?? 0;
        setText(btn, busy ? (c?.busyKind === 'remark' ? '마킹 중…' : '확인 중…') : n > 0 ? `선택한 ${n}문항 확인` : '확인할 문항 체크');
        btn.disabled = busy || !spec || left <= 0 || s.catStunned() || s.phase !== Phase.PLAYING;
      },
    };
  }

  /** 좌하단: 살피기 (고개 돌리는 범위) */
  private catLook() {
    const modes: [LookMode, string, string][] = [['left', '◀ 왼쪽', 'Q'], ['center', '기본', 'W'], ['right', '오른쪽 ▶', 'E']];
    const btns = modes.map(([m, label, key]) =>
      h('button', { class: 'btn small', onclick: () => this.actions.setLookMode(m) }, label, h('kbd', { text: key })));
    const el = h('div', { class: 'panel bl' },
      h('div', { class: 'panel-title', text: '살피기' }),
      h('div', { class: 'row' }, ...btns),
      h('div', { class: 'hint', text: '화면을 끌어서 고개 돌리기 · 감독관에겐 안 보여요' }));
    return {
      el,
      update: () => modes.forEach(([m], i) => toggleClass(btns[i], 'active', this.scene.lookMode === m)),
    };
  }

  private paper?: OmrPanel;

  /** 가운데: 시험지·OMR. 모르는 문항 체크 → 도구, 아는데 틀린 문항 클릭 → 고치기 */
  private catPaper() {
    const s = this.store;
    const panel = new OmrPanel(s.questionCount, { mode: 'cat', onRemark: (q) => this.actions.remark(q) });
    this.paper = panel;
    const toggle = h('button', { class: 'btn small ghost paper-toggle', text: '시험지 접기 (Tab)' });
    const body = h('div', { class: 'paper-body' }, panel.el,
      h('div', { class: 'hint', text: '☑ 모르는 문항 체크 → 도구로 확인 · 빨간 문항 클릭 → 정답으로 고치기 (1.5초)' }));
    const el = h('div', { class: 'paper' }, toggle, body);
    let open = true;
    const flip = () => {
      open = !open;
      show(body, open);
      setText(toggle, open ? '시험지 접기 (Tab)' : '시험지 펴기 (Tab)');
    };
    toggle.addEventListener('click', flip);
    this.paperToggle = flip;
    return {
      el,
      update: () => {
        const c = s.cat;
        const st = s.currentStudent();
        show(el, !!c && c.seat >= 0 && s.phase !== Phase.ENDED);
        if (!c || !st) return;
        const spec = toolSpec(st.tool);
        const max = spec ? Math.min(spec.perUse, st.toolChecksLeft) : 0;
        const locked = s.catBusy() || s.catStunned() || s.phase !== Phase.PLAYING;
        panel.update(`${seatLabel(st.seat)}번 학생의 시험지 · OMR  (아는 정답 ${c.known.size}개)`, st, c.known, locked ? 0 : max, locked);
      },
    };
  }

  // =====================================================================
  // 감독관
  // =====================================================================

  private proctorMap() {
    const s = this.store;
    const seatEls = new Map<number, HTMLElement>();
    const area = h('div', { class: 'mm-area' });
    const map = h('div', { class: 'minimap free' }, h('div', { class: 'mm-board', text: '칠판' }), area);
    s.seats.forEach((p, seat) => {
      const m = toMinimap(p, CFG.classroom.halfWidthM, CFG.classroom.halfDepthM);
      const el = h('div', { class: 'mm-seat', text: String(seatLabel(seat)), style: { left: `${m.left * 100}%`, top: `${m.top * 100}%` } });
      seatEls.set(seat, el);
      area.append(el);
    });
    const me = h('div', { class: 'mm-me' });
    area.append(me);
    const el = h('div', { class: 'panel tr' }, h('div', { class: 'panel-title', text: '교실' }), map);
    return {
      el,
      update: () => {
        const { pos, facing } = this.scene.proctorLocal();
        const m = toMinimap(pos, CFG.classroom.halfWidthM, CFG.classroom.halfDepthM);
        me.style.left = `${m.left * 100}%`;
        me.style.top = `${m.top * 100}%`;
        // 미니맵은 위 = +z, 왼쪽 = +x → 화면 각도로 변환
        // 화면 오른쪽 = -x, 화면 위 = +z → 위 기준 시계 방향 각도 = atan2(-fx, fz)
        me.style.transform = `translate(-50%,-50%) rotate(${Math.atan2(-facing.x, facing.z)}rad)`;
        const revealed = new Set(s.revealedSeats());
        for (const [seat, el] of seatEls) {
          toggleClass(el, 'revealed', revealed.has(seat));
          toggleClass(el, 'inspecting', s.proctor?.inspectingSeat === seat);
        }
      },
    };
  }

  private proctorUses() {
    const s = this.store;
    const swats = h('div', { class: 'uses' });
    const dets = h('div', { class: 'uses' });
    const swatBtn = h('button', { class: 'btn primary', onclick: () => this.actions.swat() }, '파리채 ', h('kbd', { text: '클릭/Space' }));
    const detBtn = h('button', { class: 'btn', onclick: () => this.actions.detect() }, '전파 탐지기 ', h('kbd', { text: 'E' }));
    const el = h('div', { class: 'panel br' },
      h('div', { class: 'panel-title', text: '적발' }),
      h('div', { class: 'uses-row' }, h('span', { text: '파리채' }), swats),
      h('div', { class: 'uses-row' }, h('span', { text: '탐지기' }), dets),
      h('div', { class: 'row' }, swatBtn, detBtn));
    return {
      el,
      update: () => {
        const p = s.proctor;
        if (!p) return;
        setText(swats, '●'.repeat(p.swatsLeft) + '○'.repeat(Math.max(0, CFG.proctor.swatter.maxAttempts - p.swatsLeft)));
        setText(dets, '●'.repeat(p.detectorsLeft) + '○'.repeat(Math.max(0, CFG.proctor.detector.maxUses - p.detectorsLeft)));
        const off = s.phase !== Phase.PLAYING || s.proctorStunned();
        swatBtn.disabled = off || p.swatsLeft <= 0;
        detBtn.disabled = off || p.detectorsLeft <= 0;
      },
    };
  }

  private proctorHelp() {
    const lockHint = h('div', { class: 'lock-hint', text: '화면을 클릭하면 시점이 잠겨요 (Esc 로 풀기)' });
    const el = h('div', { class: 'panel bl' },
      h('div', { class: 'panel-title', text: '조작' }),
      h('div', { class: 'hint' }, h('kbd', { text: 'WASD' }), ' 이동 · ', h('kbd', { text: '마우스' }), ' 시선'),
      h('div', { class: 'hint', text: '학생 바로 앞에 2초 서 있으면 시험지·OMR 검사' }),
      lockHint);
    return { el, update: () => show(lockHint, !document.pointerLockElement) };
  }

  private proctorInspect() {
    const s = this.store;
    const panel = new OmrPanel(s.questionCount, { mode: 'proctor' });
    const ring = h('div', { class: 'inspect-ring' });
    const ringText = h('div', { class: 'inspect-text' });
    const near = h('div', { class: 'inspect-near' }, ring, ringText);
    const box = h('div', { class: 'inspect-box' }, panel.el,
      h('div', { class: 'hint', text: '주황 = 시험지와 다르게 마킹 (냥이가 고쳤거나, 학생 실수)' }));
    const el = h('div', { class: 'inspect' }, near, box);
    return {
      el,
      update: () => {
        const st = s.inspectedStudent();
        const prog = this.scene.inspectProgress();
        show(box, !!st);
        show(near, !st && prog > 0 && s.phase === Phase.PLAYING);
        if (!st && prog > 0) {
          ring.style.setProperty('--p', String(prog));
          setText(ringText, `${seatLabel(this.scene.near.seat)}번 학생 검사 중…`);
        }
        if (st) panel.update(`${seatLabel(st.seat)}번 학생 검사`, st);
      },
    };
  }

  private crosshair() {
    const el = h('div', { class: 'crosshair' });
    return { el, update: () => {} };
  }
}

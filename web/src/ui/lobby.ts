import { h, setText, show } from './dom';

export interface LobbyActions {
  /** 싱글: AI 상대로 바로 시작 (서버 필요 없음) */
  startSingle(role: 'cat' | 'proctor'): void;
  joinQueue(nickname: string): void;
  createRoom(nickname: string): void;
  joinRoom(nickname: string, code: string): void;
  cancel(): void;
}

export type LobbyState =
  | { kind: 'connecting' }
  | { kind: 'idle' }
  | { kind: 'queued' }
  | { kind: 'hosting'; code: string }
  | { kind: 'reconnecting' };

const NICK_KEY = 'catchnyang.nickname';

/** 첫 화면: 싱글(AI 상대, 오프라인 OK) / 멀티(닉네임 → 랜덤 매칭 / 방 만들기 / 코드로 입장) */
export class Lobby {
  readonly el: HTMLElement;
  private readonly nick: HTMLInputElement;
  private readonly code: HTMLInputElement;
  private readonly status = h('div', { class: 'lobby-status' });
  private readonly error = h('div', { class: 'lobby-error' });
  private readonly main: HTMLElement;
  private readonly single: HTMLElement;
  private readonly multiTitle: HTMLElement;
  private readonly waiting: HTMLElement;
  private readonly waitText = h('div', { class: 'wait-text' });
  private readonly codeBox = h('div', { class: 'code-box' });

  constructor(private readonly actions: LobbyActions) {
    let saved = '';
    try {
      saved = localStorage.getItem(NICK_KEY) ?? '';
    } catch {
      // 저장소 막힌 환경
    }
    this.nick = h('input', { class: 'input', placeholder: '닉네임 (1~20자)', maxlength: '20', value: saved });
    this.code = h('input', { class: 'input code', placeholder: '방 코드', maxlength: '6' });
    this.code.addEventListener('input', () => (this.code.value = this.code.value.toUpperCase()));
    this.code.addEventListener('keydown', (ev) => {
      if (ev.key === 'Enter') this.join();
    });

    this.single = h('div', { class: 'lobby-section' },
      h('div', { class: 'section-title' }, h('b', { text: '싱글' }), h('span', { text: 'AI 상대 · 오프라인 가능' })),
      h('div', { class: 'single-buttons' },
        h('button', { class: 'btn big role-btn cat', onclick: () => this.actions.startSingle('cat') },
          h('span', { class: 'role-emoji', text: '🐱' }), h('b', { text: '냥이로 하기' }), h('small', { text: 'AI 감독관을 속여라' })),
        h('button', { class: 'btn big role-btn proctor', onclick: () => this.actions.startSingle('proctor') },
          h('span', { class: 'role-emoji', text: '👮' }), h('b', { text: '감독관으로 하기' }), h('small', { text: 'AI 냥이를 잡아라' }))));

    this.multiTitle = h('div', { class: 'section-title' }, h('b', { text: '멀티' }), h('span', { text: '친구·랜덤 1:1' }));
    this.main = h('div', { class: 'lobby-main' },
      h('label', { class: 'field' }, h('span', { text: '닉네임' }), this.nick),
      h('button', { class: 'btn primary big', text: '🎲 랜덤 매칭', onclick: () => this.withNick((n) => this.actions.joinQueue(n)) }),
      h('div', { class: 'or', text: '또는 친구와' }),
      h('div', { class: 'row' },
        h('button', { class: 'btn', text: '방 만들기', onclick: () => this.withNick((n) => this.actions.createRoom(n)) }),
        this.code,
        h('button', { class: 'btn', text: '입장', onclick: () => this.join() })));

    this.waiting = h('div', { class: 'lobby-wait' },
      h('div', { class: 'spinner' }), this.waitText, this.codeBox,
      h('button', { class: 'btn ghost', text: '취소', onclick: () => this.actions.cancel() }));

    this.el = h('div', { class: 'lobby' },
      h('div', { class: 'lobby-card' },
        h('div', { class: 'logo', text: '🐱' }),
        h('h1', { text: '냥이를 잡아라' }),
        h('p', { class: 'tagline', text: '시험 끝, OMR 옮겨 적는 시간. 학생 몸에 숨어든 냥이를 찾아라' }),
        this.single,
        h('div', { class: 'lobby-section' }, this.multiTitle, this.main, this.waiting, this.status),
        this.error,
        h('div', { class: 'roles' },
          h('div', { class: 'role-card cat' }, h('b', { text: '🐱 냥이' }), h('span', { text: '학생에게 빙의해 AI 도구로 정답을 알아내고 OMR 을 고쳐 반 평균을 올린다' })),
          h('div', { class: 'role-card proctor' }, h('b', { text: '👮 감독관' }), h('span', { text: '교실을 돌며 수상한 학생을 검사하고 파리채로 적발한다' })))));
    this.set({ kind: 'connecting' });
  }

  set(state: LobbyState): void {
    const busy = state.kind === 'queued' || state.kind === 'hosting';
    // 싱글은 서버와 무관하게 언제나 (멀티 대기 중일 때만 숨김)
    show(this.single, !busy && state.kind !== 'reconnecting');
    show(this.main, state.kind === 'idle');
    show(this.waiting, busy);
    show(this.codeBox, state.kind === 'hosting');
    setText(this.status, state.kind === 'connecting' ? '서버에 연결 중… (싱글은 바로 할 수 있어요)' : state.kind === 'reconnecting' ? '진행 중이던 판에 다시 들어가는 중…' : '');
    if (state.kind === 'queued') setText(this.waitText, '상대를 찾는 중…');
    if (state.kind === 'hosting') {
      setText(this.waitText, '친구에게 이 코드를 알려주세요');
      this.codeBox.replaceChildren(
        h('span', { class: 'code-text', text: state.code }),
        h('button', { class: 'btn small', text: '복사', onclick: () => void navigator.clipboard?.writeText(state.code) }));
    }
    if (state.kind !== 'idle') setText(this.error, '');
  }

  showError(message: string): void {
    setText(this.error, message);
  }

  private join(): void {
    const code = this.code.value.trim();
    if (code.length !== 6) {
      this.showError('방 코드 6자리를 입력하세요');
      return;
    }
    this.withNick((n) => this.actions.joinRoom(n, code));
  }

  private withNick(f: (nick: string) => void): void {
    const n = this.nick.value.trim();
    if (!n) {
      this.showError('닉네임을 입력하세요');
      this.nick.focus();
      return;
    }
    try {
      localStorage.setItem(NICK_KEY, n);
    } catch {
      // 무시
    }
    setText(this.error, '');
    f(n);
  }
}

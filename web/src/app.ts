import { Phase, RematchState, type ServerMessage } from './gen/catchnyang/v1/game_pb';
import { Connection, serverUrls, type ClientPayload } from './net/connection';
import { GameStore } from './state/game-store';
import { GameScene } from './scene/game-scene';
import { Hud } from './ui/hud';
import { Lobby } from './ui/lobby';
import { h } from './ui/dom';

const TOKEN_KEY = 'catchnyang.session';

const ERROR_TEXT: Record<string, string> = {
  BAD_NICKNAME: '닉네임은 1~20자로 입력하세요',
  ROOM_NOT_FOUND: '그런 방 코드가 없어요',
  BAD_STATE: '지금은 할 수 없어요',
  BAD_TOKEN: '진행 중이던 판이 이미 끝났어요',
  NO_REMATCH: '재대결할 수 있는 시간이 지났어요',
};

/**
 * 앱 전체: 서버 메시지 → 상태(GameStore) → 화면(로비 / 3D + HUD).
 * 끊기면 받아 둔 일회용 토큰으로 자동 재접속 (새로고침해도 유예 시간 안이면 이어서 함).
 */
export class App {
  private conn!: Connection;
  private readonly store = new GameStore();
  private readonly lobby: Lobby;
  private readonly scene: GameScene;
  private readonly hud: Hud;
  private readonly gameEl = h('div', { class: 'game' });
  private screen: 'lobby' | 'game' = 'lobby';
  private token = '';
  private graceSec = 30;
  private reconnecting = false;

  /** 붙어 볼 주소들과, 마지막으로 붙었던 주소 번호 (재접속은 거기부터) */
  private readonly urls: string[];
  private urlIndex = 0;

  constructor(root: HTMLElement, urls: string[] = serverUrls()) {
    this.urls = urls;
    this.lobby = new Lobby({
      joinQueue: (nickname) => this.send({ case: 'joinQueue', value: { nickname } }),
      createRoom: (nickname) => this.send({ case: 'createRoom', value: { nickname } }),
      joinRoom: (nickname, roomCode) => this.send({ case: 'joinRoom', value: { nickname, roomCode } }),
      cancel: () => this.send({ case: 'cancelQueue', value: {} }),
    });

    this.scene = new GameScene(this.gameEl, this.store, {
      possess: (seat) => this.send({ case: 'possess', value: { seat } }),
      proctorMove: (dx, dz, faceX, faceZ) => this.send({ case: 'proctorMove', value: { dx, dz, faceX, faceZ } }),
      swat: () => this.send({ case: 'swat', value: {} }),
      detect: () => this.send({ case: 'detect', value: {} }),
    });

    this.hud = new Hud(this.store, this.scene, {
      possess: (seat) => this.send({ case: 'possess', value: { seat } }),
      useTool: (questions) => {
        this.store.noteToolRequest(questions.length);
        this.send({ case: 'useTool', value: { questions } });
      },
      remark: (question) => this.send({ case: 'remark', value: { question } }),
      setLookMode: (m) => this.scene.setLookMode(m),
      swat: () => this.send({ case: 'swat', value: {} }),
      detect: () => this.send({ case: 'detect', value: {} }),
      leave: () => this.send({ case: 'leaveMatch', value: {} }),
      requestRematch: () => this.send({ case: 'requestRematch', value: {} }),
      declineRematch: () => this.send({ case: 'declineRematch', value: {} }),
      backToLobby: () => this.backToLobby(),
    });
    this.gameEl.append(this.hud.el);

    root.append(this.lobby.el, this.gameEl);
    this.showScreen('lobby');
    requestAnimationFrame(this.loop);
  }

  async start(): Promise<void> {
    const saved = readToken();
    await this.connect();
    if (saved) {
      this.token = saved;
      this.lobby.set({ kind: 'reconnecting' });
      this.send({ case: 'reconnect', value: { sessionToken: saved } });
    } else {
      this.lobby.set({ kind: 'idle' });
    }
  }

  // =====================================================================
  // 연결
  // =====================================================================

  private async connect(): Promise<void> {
    for (let attempt = 0; ; attempt++) {
      const index = (this.urlIndex + attempt) % this.urls.length;
      const conn = new Connection(this.urls[index], {
        onMessage: (m) => this.onMessage(m),
        onClose: () => this.onDisconnected(),
      });
      try {
        await conn.open(3000);
        this.conn = conn;
        this.urlIndex = index;
        this.lobby.showError('');
        return;
      } catch {
        // 주소가 여러 개면 다음 주소는 바로 시도
        if ((attempt + 1) % this.urls.length !== 0) continue;
        this.lobby.showError(attempt === 0 ? '서버에 연결할 수 없어요. 다시 시도하는 중…' : `서버에 연결할 수 없어요 (${attempt + 1}번째 시도)`);
        await sleep(Math.min(5000, 1000 * (attempt + 1)));
      }
    }
  }

  private onDisconnected(): void {
    const inMatch = this.screen === 'game' && !this.store.result && !!this.token;
    if (inMatch) {
      this.hud.toast('연결이 끊겼어요 — 다시 연결하는 중…');
      void this.reconnect();
    } else {
      this.showScreen('lobby');
      this.lobby.set({ kind: 'connecting' });
      void this.connect().then(() => this.lobby.set({ kind: 'idle' }));
    }
  }

  /** 게임 중 끊김: 유예 시간 동안 새 연결로 Reconnect 를 계속 시도 */
  private async reconnect(): Promise<void> {
    if (this.reconnecting) return;
    this.reconnecting = true;
    const deadline = performance.now() + this.graceSec * 1000;
    for (let attempt = 0; performance.now() < deadline; attempt++) {
      const index = (this.urlIndex + attempt) % this.urls.length;
      const conn = new Connection(this.urls[index], { onMessage: (m) => this.onMessage(m), onClose: () => this.onDisconnected() });
      try {
        await conn.open(2000);
        this.conn = conn;
        this.urlIndex = index;
        this.reconnecting = false;
        this.send({ case: 'reconnect', value: { sessionToken: this.token } });
        return;
      } catch {
        await sleep(1000);
      }
    }
    this.reconnecting = false;
    clearToken();
    this.token = '';
    this.backToLobby();
    this.lobby.showError('다시 연결하지 못해서 판이 끝났어요');
  }

  private send(payload: ClientPayload): number {
    return this.conn?.send(payload) ?? 0;
  }

  // =====================================================================
  // 서버 메시지
  // =====================================================================

  private onMessage(m: ServerMessage): void {
    const p = m.payload;
    switch (p.case) {
      case 'queueJoined':
        this.lobby.set({ kind: 'queued' });
        break;
      case 'roomCreated':
        this.lobby.set({ kind: 'hosting', code: p.value.roomCode });
        break;
      case 'queueCancelled':
        this.lobby.set({ kind: 'idle' });
        break;
      case 'matchFound':
        this.token = p.value.sessionToken;
        this.graceSec = p.value.reconnectGraceSeconds || 30;
        saveToken(this.token);
        this.store.startMatch(p.value);
        this.showScreen('game');
        break;
      case 'reconnected':
        this.token = p.value.sessionToken;
        saveToken(this.token);
        this.store.roomId = p.value.roomId;
        this.store.opponentNickname = p.value.opponentNickname;
        this.showScreen('game');
        this.hud.toast('다시 연결됐어요');
        break;
      case 'snapshot': {
        const fresh = !this.store.hasSnapshot;
        this.store.applySnapshot(p.value);
        if (fresh || this.screen !== 'game') {
          this.showScreen('game');
          this.scene.start();
          this.scene.refreshProjection();
          this.hud.mount();
        }
        break;
      }
      case 'tick':
        this.store.applyTick(p.value);
        if (this.store.phase === Phase.ENDED) clearToken();
        break;
      case 'opponentConnection':
        this.store.setOpponentConnection(p.value.connected, p.value.graceMs);
        break;
      case 'rematchUpdate':
        this.store.setRematch(p.value.state, p.value.expiresMs);
        break;
      case 'error': {
        const text = ERROR_TEXT[p.value.code] ?? p.value.message;
        if (p.value.code === 'BAD_TOKEN') {
          clearToken();
          this.token = '';
          this.backToLobby();
        }
        if (this.screen === 'lobby') {
          this.lobby.set({ kind: 'idle' });
          this.lobby.showError(text);
        } else {
          this.hud.toast(text);
        }
        break;
      }
      case 'pong':
      case undefined:
        break;
    }
  }

  private backToLobby(): void {
    const st = this.store.rematchState;
    if (st === RematchState.AVAILABLE || st === RematchState.OFFERED || st === RematchState.WAITING) {
      this.send({ case: 'declineRematch', value: {} });
    }
    this.store.setRematch(RematchState.UNSPECIFIED, 0);
    this.store.hasSnapshot = false;
    this.store.result = null;
    this.showScreen('lobby');
    this.lobby.set({ kind: 'idle' });
  }

  private showScreen(s: 'lobby' | 'game'): void {
    this.screen = s;
    this.lobby.el.style.display = s === 'lobby' ? '' : 'none';
    this.gameEl.style.display = s === 'game' ? '' : 'none';
    if (s === 'lobby' && document.pointerLockElement) document.exitPointerLock();
  }

  private readonly loop = () => {
    if (this.screen === 'game' && this.store.hasSnapshot) {
      this.scene.frame();
      this.hud.update();
    }
    requestAnimationFrame(this.loop);
  };
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * 새로고침해도 진행 중이던 판으로 돌아가도록 탭 단위로 토큰 보관.
 * 유효한지는 서버가 판단 (끝났거나 유예가 지났으면 BAD_TOKEN → 지우고 로비로)
 */
function saveToken(token: string): void {
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
  } catch {
    // 무시
  }
}

function readToken(): string | null {
  try {
    return sessionStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

function clearToken(): void {
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch {
    // 무시
  }
}

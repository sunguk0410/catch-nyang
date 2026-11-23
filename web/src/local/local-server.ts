import { create, fromJson, toJsonString, type JsonValue } from '@bufbuild/protobuf';
import configJson from '../../../protocol/game-config.json?raw';
import { ClientMessageSchema, Role, ServerMessageSchema, type ServerMessage } from '../gen/catchnyang/v1/game_pb';
import * as rules from '../gen/rules/rules.js';
import { CFG } from '../config';
import type { ClientPayload } from '../net/connection';

/** 게임 화면이 메시지를 주고받는 통로. 멀티 = 서버 웹소켓(Connection), 싱글 = 브라우저 안 가짜 서버(LocalServer) */
export interface Link {
  send(payload: ClientPayload): number;
  close(): void;
}

const TICK_MS = 1000 / CFG.server.tickHz;

/**
 * 싱글 모드: 서버 없이 브라우저 안에서 한 판을 돌린다 (오프라인 OK, 네트워크 지연 0).
 * 규칙·AI 는 서버와 같은 Java 코드를 JS 로 바꾼 rules.js. 내보내는 메시지도 서버와 같은 모양이라
 * 화면(GameStore·HUD·3D)은 싱글인지 멀티인지 모른다.
 */
export class LocalServer implements Link {
  private id = 0;
  private seq = 0;
  private role: Role = Role.CAT;
  private timer: ReturnType<typeof setInterval> | null = null;
  private lastAt = 0;
  private acc = 0;
  private ended = false;

  constructor(private readonly onMessage: (m: ServerMessage) => void) {}

  /** 새 판 (상대는 AI). 재대결이면 rematch=true 로 알림 */
  start(role: Role, rematch = false): void {
    this.stop();
    this.role = role;
    this.ended = false;
    const seed = (Math.random() * 0x7fffffff) | 0;
    this.id = rules.createMatch(configJson, seed, role === Role.CAT ? 'CAT' : 'PROCTOR');
    this.emit({
      matchFound: {
        roomId: `local-${this.id}`,
        myRole: role === Role.CAT ? 'ROLE_CAT' : 'ROLE_PROCTOR',
        opponentNickname: role === Role.CAT ? 'AI 감독관' : 'AI 냥이',
        prepareSeconds: CFG.match.prepareSec,
        rematch,
      },
    });
    this.lastAt = performance.now();
    this.acc = 0;
    this.timer = setInterval(this.loop, 10);
  }

  /** 고정 50ms 간격으로 tick (setInterval 오차는 누적해서 보정, 탭이 멈췄다 오면 최대 5 tick 만 따라잡음) */
  private readonly loop = () => {
    const now = performance.now();
    this.acc = Math.min(this.acc + (now - this.lastAt), TICK_MS * 5);
    this.lastAt = now;
    while (this.acc >= TICK_MS) {
      this.acc -= TICK_MS;
      this.step();
    }
  };

  private step(): void {
    const json = rules.tick(this.id);
    if (json !== '[]') for (const m of JSON.parse(json) as JsonValue[]) this.emit(m);
    if (!this.ended && rules.isFinished(this.id)) {
      this.ended = true;
      this.stop();
      // 싱글은 언제든 역할 바꿔 다시 할 수 있음
      this.emit({ rematchUpdate: { state: 'REMATCH_STATE_AVAILABLE', expiresMs: 10 * 60_000 } });
    }
  }

  send(payload: ClientPayload): number {
    const seq = ++this.seq;
    switch (payload.case) {
      case 'requestRematch':
        rules.dispose(this.id);
        this.start(this.role === Role.CAT ? Role.PROCTOR : Role.CAT, true);
        break;
      case 'possess':
      case 'useTool':
      case 'remark':
      case 'proctorMove':
      case 'swat':
      case 'detect':
      case 'leaveMatch':
        rules.submit(this.id, seq, toJsonString(ClientMessageSchema, create(ClientMessageSchema, { seq, payload })));
        break;
      default:
        // 로비·재접속·핑 메시지는 싱글에선 할 일 없음
        break;
    }
    return seq;
  }

  close(): void {
    this.stop();
    if (this.id) rules.dispose(this.id);
    this.id = 0;
  }

  private stop(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** 서버 메시지와 같은 proto3 JSON → ServerMessage */
  private emit(json: JsonValue): void {
    this.onMessage(fromJson(ServerMessageSchema, json));
  }
}

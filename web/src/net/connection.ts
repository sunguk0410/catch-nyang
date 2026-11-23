import { create, fromBinary, toBinary, type MessageInitShape } from '@bufbuild/protobuf';
import { ClientMessageSchema, ServerMessageSchema, type ServerMessage } from '../gen/catchnyang/v1/game_pb';

/** ClientMessage.payload 하나 (예: { case: 'possess', value: { seat: 3 } }) */
export type ClientPayload = NonNullable<MessageInitShape<typeof ClientMessageSchema>['payload']>;

export interface ConnectionHandlers {
  onOpen?: () => void;
  onMessage: (msg: ServerMessage) => void;
  /** 의도치 않게 끊겼을 때만 (close() 로 닫은 건 안 부름) */
  onClose?: (ev: { code: number; reason: string }) => void;
}

/**
 * 서버 웹소켓 하나. 바이너리 protobuf 송수신 + 모든 메시지에 seq 자동 부여.
 * 하트비트는 브라우저가 서버 ping 에 자동 pong 하므로 따로 안 함.
 */
export class Connection {
  private ws?: WebSocket;
  private seq = 0;
  private closedByUs = false;

  constructor(private readonly url: string, private readonly handlers: ConnectionHandlers) {}

  /** 연결될 때까지 기다림 */
  open(timeoutMs = 5000): Promise<void> {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(this.url);
      ws.binaryType = 'arraybuffer';
      this.ws = ws;
      let opened = false;
      const timer = setTimeout(() => {
        reject(new Error('connect timeout'));
        ws.close();
      }, timeoutMs);

      ws.onopen = () => {
        opened = true;
        clearTimeout(timer);
        this.handlers.onOpen?.();
        resolve();
      };
      ws.onerror = () => {
        clearTimeout(timer);
        reject(new Error('connect failed'));
      };
      ws.onmessage = (ev) => {
        if (!(ev.data instanceof ArrayBuffer)) return;
        let msg: ServerMessage;
        try {
          msg = fromBinary(ServerMessageSchema, new Uint8Array(ev.data));
        } catch (e) {
          console.warn('bad server message', e);
          return;
        }
        this.handlers.onMessage(msg);
      };
      ws.onclose = (ev) => {
        clearTimeout(timer);
        // 열리기도 전에 닫힘 = 연결 실패 (끊김 처리로 보내면 재시도 루프가 겹겹이 생김)
        if (!opened) {
          reject(new Error(`closed before open (${ev.code})`));
          return;
        }
        if (!this.closedByUs) this.handlers.onClose?.({ code: ev.code, reason: ev.reason });
      };
    });
  }

  get isOpen(): boolean {
    return this.ws?.readyState === WebSocket.OPEN;
  }

  /** 보낸 메시지의 seq 를 돌려줌 (게임 입력이면 GameTick.ackSeq / rejected 로 돌아옴). 연결이 없으면 0 */
  send(payload: ClientPayload): number {
    if (!this.isOpen) return 0;
    const seq = ++this.seq;
    const msg = create(ClientMessageSchema, { seq, payload });
    this.ws!.send(toBinary(ClientMessageSchema, msg));
    return seq;
  }

  close(): void {
    this.closedByUs = true;
    this.ws?.close();
  }
}

/**
 * 시도할 서버 주소들 (앞에서부터). 기본은 페이지와 같은 주소의 /ws (개발: Vite 프록시, 운영: 리버스 프록시).
 * 개발 모드에선 Vite 프록시가 죽어 있을 때(설정 파일이 바뀌어 Vite 가 재시작된 뒤 등)를 대비해 서버(:8080)에 직접도 붙어 본다.
 */
export function serverUrls(loc: Location = location): string[] {
  const primary = defaultServerUrl(loc);
  const overridden = new URLSearchParams(loc.search).has('server');
  if (!import.meta.env.DEV || overridden) return [primary];
  return [primary, `ws://${loc.hostname}:8080/ws`];
}

/** 기본은 페이지와 같은 주소의 /ws (개발: Vite 프록시, 운영: 리버스 프록시). ?server=ws://… 로 바꿀 수 있음 */
export function defaultServerUrl(loc: Location = location): string {
  const override = new URLSearchParams(loc.search).get('server');
  if (override) return override;
  const scheme = loc.protocol === 'https:' ? 'wss' : 'ws';
  return `${scheme}://${loc.host}/ws`;
}

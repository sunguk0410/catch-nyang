// 규칙 수치는 서버와 같은 파일을 그대로 읽는다 (복사 금지)
import raw from '../../protocol/game-config.json';
import { ToolType } from './gen/catchnyang/v1/game_pb';

export const CFG = raw;

export interface ToolSpec {
  totalChecks: number;
  perUse: number;
  lockSec: number;
  detect: string;
  visibleRangeM: number | null;
}

const TOOL_KEYS: Partial<Record<ToolType, keyof typeof raw.tools>> = {
  [ToolType.AI_GLASSES]: 'AI_GLASSES',
  [ToolType.SMART_WATCH]: 'SMART_WATCH',
  [ToolType.ERASER_CAMERA]: 'ERASER_CAMERA',
  [ToolType.WIRELESS_EARPHONE]: 'WIRELESS_EARPHONE',
};

export function toolSpec(t: ToolType): ToolSpec | undefined {
  const key = TOOL_KEYS[t];
  return key ? (raw.tools[key] as ToolSpec) : undefined;
}

export const TOOL_NAMES: Record<ToolType, string> = {
  [ToolType.UNSPECIFIED]: '없음',
  [ToolType.AI_GLASSES]: 'AI 안경',
  [ToolType.SMART_WATCH]: '스마트워치',
  [ToolType.ERASER_CAMERA]: '지우개 카메라',
  [ToolType.WIRELESS_EARPHONE]: '무선 이어폰',
};

/** 살피기 모드별 고개 돌릴 수 있는 범위 (도). 음수 = 왼쪽 */
export type LookMode = 'center' | 'left' | 'right';
export function lookRange(mode: LookMode): [number, number] {
  const r = raw.cat.lookRangeDeg[mode];
  return [r[0], r[1]];
}

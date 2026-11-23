import { CFG } from '../config';
import type { Vec2 } from '../state/game-store';

/**
 * 좌표 약속 (서버와 같음): 바닥 평면 x = 좌우, z = 앞뒤. 칠판·교탁은 +z 쪽.
 * three.js 는 y 가 위 → 서버 (x, z) 를 그대로 (x, ·, z) 로 쓴다.
 *
 * 학생은 칠판(+z)을 보고 앉아 있다. 학생 기준 오른쪽은 -x.
 *
 * 서버 자리 좌표는 "책상 앞 감독관이 서서 검사하는 지점" 바로 뒤.
 * 책상 블록(책상+의자+학생)은 자리 좌표에서 앞으로 deskFrontM, 뒤로 deskBackM 만큼 차지하고 감독관은 못 들어간다.
 */

const DESK_DEPTH = 0.58;
/** 책상 중심 (자리 좌표 기준 z). 책상 앞 모서리가 deskFrontM 에 오도록 */
export const DESK_CENTER_OFFSET = CFG.classroom.deskFrontM - DESK_DEPTH / 2;
/** 학생 몸 위치 (자리 좌표보다 뒤 = 책상 뒤 의자) — 양수, 뒤로 이만큼 */
export const STUDENT_BACK_OFFSET = -(DESK_CENTER_OFFSET - 0.45);
export const SEATED_EYE_HEIGHT = 1.12;
export const STANDING_EYE_HEIGHT = 1.62;

/**
 * 화면에 보이는 자리 번호 1~9. "교실 뒤에서 칠판을 보는 방향" 기준으로
 * 앞줄 왼쪽이 1, 뒷줄 오른쪽이 9. (서버 자리 번호 0~8 과는 다름 → 9 - seat)
 */
export function seatLabel(seat: number): number {
  return 9 - seat;
}

/**
 * 미니맵 좌표 (0~1). 위쪽 = 칠판(+z), 왼쪽 = 학생 기준 왼쪽(+x).
 * halfW/halfD 는 교실 반폭·반깊이.
 */
export function toMinimap(p: Vec2, halfW: number, halfD: number): { left: number; top: number } {
  return {
    left: (halfW - p.x) / (2 * halfW),
    top: (halfD - p.z) / (2 * halfD),
  };
}

export const deg = (d: number) => (d * Math.PI) / 180;

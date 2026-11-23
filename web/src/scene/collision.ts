import { CFG } from '../config';
import type { Vec2 } from '../state/game-store';

/**
 * 감독관이 책상 블록(책상+의자+학생) 안에 들어가면 가장 가까운 면 밖으로 밀어냄.
 * 서버 Match.pushOutOfDesks 와 같은 계산 — 클라 예측이 서버와 어긋나지 않게 (바꾸면 같이 바꿀 것).
 */
export function pushOutOfDesks(seats: Vec2[], x: number, z: number): Vec2 {
  const c = CFG.classroom;
  const r = c.proctorRadiusM;
  for (const s of seats) {
    const minX = s.x - c.deskHalfWidthM - r;
    const maxX = s.x + c.deskHalfWidthM + r;
    const minZ = s.z - c.deskBackM - r;
    const maxZ = s.z + c.deskFrontM + r;
    if (x <= minX || x >= maxX || z <= minZ || z >= maxZ) continue;
    const toMinX = x - minX;
    const toMaxX = maxX - x;
    const toMinZ = z - minZ;
    const toMaxZ = maxZ - z;
    const m = Math.min(toMinX, toMaxX, toMinZ, toMaxZ);
    if (m === toMinX) x = minX;
    else if (m === toMaxX) x = maxX;
    else if (m === toMinZ) z = minZ;
    else z = maxZ;
  }
  return { x, z };
}

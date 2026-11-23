import { describe, expect, it } from 'vitest';
import { CFG } from '../config';
import { pushOutOfDesks } from './collision';

const seats = Array.from({ length: 9 }, (_, i) => ({ x: ((i % 3) - 1) * 2.2, z: (Math.floor(i / 3) - 1) * 2.2 }));
const c = CFG.classroom;

describe('pushOutOfDesks (서버 Match.pushOutOfDesks 와 같은 결과여야 함)', () => {
  it('책상 앞에서 들어가면 앞면으로 밀려남', () => {
    const p = pushOutOfDesks(seats, 0, 2.2 + c.deskFrontM + c.proctorRadiusM - 0.05);
    expect(p).toEqual({ x: 0, z: 2.2 + c.deskFrontM + c.proctorRadiusM });
  });

  it('옆에서 들어가면 옆면으로', () => {
    const edge = c.deskHalfWidthM + c.proctorRadiusM;
    const p = pushOutOfDesks(seats, edge - 0.03, 0);
    expect(p.x).toBeCloseTo(edge);
    expect(p.z).toBe(0);
  });

  it('통로는 그대로', () => {
    expect(pushOutOfDesks(seats, 1.1, 0.3)).toEqual({ x: 1.1, z: 0.3 });
    expect(pushOutOfDesks(seats, 0, 4)).toEqual({ x: 0, z: 4 });
  });
});

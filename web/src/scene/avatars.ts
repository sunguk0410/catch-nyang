import * as THREE from 'three';
import { DESK_CENTER_OFFSET, STUDENT_BACK_OFFSET } from './layout';
import { examPaper, omrCard, swatterMesh } from './textures';

/**
 * 학생·감독관 캐릭터. 머리가 큰 귀여운 비율(교복·단발)을 도형으로 만들고 동작은 코드로 계산한다.
 * 모델로 바꿀 때도 play()/update() 모양만 지키면 나머지 코드는 그대로.
 */

export type StudentAction =
  | 'twitch'   // 빙의 순간 움찔 (진짜 단서)
  | 'remark'   // 지우고 다시 마킹 (진짜·가짜 둘 다)
  | 'glasses'  // 안경 고쳐 쓰기 (가짜 단서)
  | 'watch'    // 시계 보기 (가짜 단서)
  | 'yawn'     // 하품 (가짜 단서)
  | 'stretch'  // 기지개 (가짜 단서)
  | 'hit';     // 파리채 맞음

interface Playing {
  action: StudentAction;
  start: number;
  dur: number;
}

const SKIN = 0xffdcc2;
const SHIRT = 0xf8fafc;
const DESK_TOP = 0.74;
const HAIR_COLORS = [0x1f1b18, 0x2b2420, 0x3b2a20, 0x161616];

/** 같은 자리는 늘 같은 모습이 되도록 자리 번호로 만든 난수 */
function seeded(seed: number) {
  let s = (seed * 9301 + 49297) % 233280;
  return () => {
    s = (s * 9301 + 49297) % 233280;
    return s / 233280;
  };
}

/** 0→1→0 부드럽게 (동작 시작·끝을 자연스럽게) */
function envelope(p: number): number {
  if (p <= 0 || p >= 1) return 0;
  const a = Math.min(1, p / 0.2, (1 - p) / 0.25);
  return 0.5 - 0.5 * Math.cos(Math.PI * a);
}

const matCache = new Map<string, THREE.MeshStandardMaterial>();
function mat(color: number, opts: THREE.MeshStandardMaterialParameters = {}): THREE.MeshStandardMaterial {
  const key = `${color}-${JSON.stringify(opts)}`;
  let m = matCache.get(key);
  if (!m) {
    m = new THREE.MeshStandardMaterial({ color, roughness: 0.75, metalness: 0, ...opts });
    matCache.set(key, m);
  }
  return m;
}

function mesh(geo: THREE.BufferGeometry, material: THREE.Material, shadow = true): THREE.Mesh {
  const m = new THREE.Mesh(geo, material);
  m.castShadow = shadow;
  m.receiveShadow = true;
  return m;
}

/** 팔: 어깨에 축(pivot)을 두고 그 아래로 늘어진 소매 + 손 */
function arm(sleeve: number, length = 0.3): { pivot: THREE.Group; hand: THREE.Object3D } {
  const pivot = new THREE.Group();
  const upper = mesh(new THREE.CapsuleGeometry(0.055, length - 0.1, 4, 8), mat(sleeve));
  upper.position.y = -length / 2;
  const hand = mesh(new THREE.SphereGeometry(0.055, 12, 10), mat(SKIN));
  hand.position.y = -length;
  pivot.add(upper, hand);
  return { pivot, hand };
}

/** 눈·볼 (얼굴은 +z 쪽) */
function face(head: THREE.Object3D, r: number) {
  const eye = new THREE.SphereGeometry(r * 0.11, 10, 8);
  for (const x of [-0.33, 0.33]) {
    const e = mesh(eye, mat(0x111111, { roughness: 0.3 }), false);
    e.position.set(x * r, 0.02 * r, 0.93 * r);
    e.scale.set(1, 1.35, 0.6);
    head.add(e);
    const cheek = mesh(new THREE.CircleGeometry(r * 0.12, 12), mat(0xffa8b8, { transparent: true, opacity: 0.6 }), false);
    cheek.position.set(x * 1.45 * r, -0.22 * r, 0.9 * r);
    cheek.lookAt(cheek.position.clone().multiplyScalar(3));
    head.add(cheek);
  }
}

function glasses(r: number, color = 0x1f2937): THREE.Group {
  const g = new THREE.Group();
  const ring = new THREE.TorusGeometry(r * 0.2, r * 0.03, 6, 18);
  for (const x of [-0.33, 0.33]) {
    const lens = mesh(ring, mat(color), false);
    lens.position.set(x * r, 0.02 * r, 0.97 * r);
    g.add(lens);
  }
  const bridge = mesh(new THREE.BoxGeometry(r * 0.22, r * 0.03, r * 0.03), mat(color), false);
  bridge.position.set(0, 0.04 * r, 0.99 * r);
  g.add(bridge);
  return g;
}

export class StudentAvatar {
  readonly root = new THREE.Group();
  /** 몸 (머리·팔 포함) — 빙의한 내 자리에선 숨김 */
  readonly body = new THREE.Group();
  readonly head = new THREE.Group();
  /** 이펙트 붙일 자리 */
  readonly eyes = new THREE.Object3D();
  readonly wrist: THREE.Object3D;
  readonly deskTop = new THREE.Object3D();

  private readonly torso: THREE.Mesh;
  private readonly armL: THREE.Group;
  private readonly armR: THREE.Group;
  private readonly torsoMat: THREE.MeshStandardMaterial;
  private playing: Playing | null = null;
  private readonly phase: number;
  private nextFake: number;

  /** @param accent 학생마다 다른 리본·머리핀 색 (구분용) */
  constructor(readonly seat: number, accent: number) {
    const rnd = seeded(seat + 7);
    this.phase = rnd() * 10;
    this.root.add(this.buildDesk(rnd), this.buildChair());

    // ---- 몸 (책상 뒤 -z 에 앉아 칠판 +z 를 봄) ----
    const sweater = rnd() < 0.35;
    this.torsoMat = new THREE.MeshStandardMaterial({ color: sweater ? 0xbcd4ee : SHIRT, roughness: 0.8 });
    this.torso = mesh(new THREE.CapsuleGeometry(0.17, 0.2, 4, 14), this.torsoMat);
    this.torso.position.y = 0.72;
    const ribbon = mesh(new THREE.BoxGeometry(0.09, 0.05, 0.02), mat(accent));
    ribbon.position.set(0, 0.17, 0.165);
    this.torso.add(ribbon);
    // 치마·바지 + 다리 (책상 밑)
    const lap = mesh(new THREE.BoxGeometry(0.32, 0.12, 0.34), mat(rnd() < 0.5 ? 0x1e3a5f : 0x374151));
    lap.position.set(0, 0.5, 0.12);
    const shin = new THREE.CapsuleGeometry(0.05, 0.3, 4, 8);
    for (const x of [-0.08, 0.08]) {
      const leg = mesh(shin, mat(0x1e293b));
      leg.position.set(x, 0.24, 0.3);
      this.body.add(leg);
    }

    // 머리 (크게)
    const r = 0.21;
    const skull = mesh(new THREE.SphereGeometry(r, 24, 18), mat(SKIN));
    this.head.add(skull);
    face(this.head, r);
    const hairColor = HAIR_COLORS[Math.floor(rnd() * HAIR_COLORS.length)];
    const hair = mesh(new THREE.SphereGeometry(r * 1.07, 24, 14, 0, Math.PI * 2, 0, Math.PI * 0.62), mat(hairColor, { roughness: 0.6 }));
    hair.rotation.x = -0.35;
    hair.position.z = -0.01;
    this.head.add(hair);
    if (rnd() < 0.6) {
      // 단발 옆·뒷머리
      // 원기둥 각도 0 = 얼굴(+z) → 0.3π~1.7π 만 감싸서 얼굴은 비움
      const bob = mesh(new THREE.CylinderGeometry(r * 1.06, r * 1.12, r * 0.95, 20, 1, true, Math.PI * 0.3, Math.PI * 1.4), mat(hairColor, { side: THREE.DoubleSide, roughness: 0.6 }));
      bob.position.y = -r * 0.32;
      this.head.add(bob);
      const clip = mesh(new THREE.SphereGeometry(0.03, 8, 6), mat(accent), false);
      clip.position.set(r * 0.7, r * 0.55, r * 0.45);
      this.head.add(clip);
    }
    if (rnd() < 0.3) this.head.add(glasses(r));
    this.eyes.position.set(0, 0.01, r * 0.98);
    this.head.add(this.eyes);
    this.head.position.y = 1.08;

    const l = arm(SHIRT);
    const rr = arm(SHIRT);
    this.armL = l.pivot;
    this.armR = rr.pivot;
    // 학생 기준 왼쪽 = +x
    this.armL.position.set(0.2, 0.88, 0.02);
    this.armR.position.set(-0.2, 0.88, 0.02);
    this.wrist = l.hand;
    // 오른손엔 연필
    const pencil = mesh(new THREE.CylinderGeometry(0.008, 0.008, 0.16, 6), mat(0xfacc15), false);
    pencil.position.set(0, -0.02, 0.06);
    pencil.rotation.x = 1.1;
    rr.hand.add(pencil);

    this.body.add(this.torso, lap, this.head, this.armL, this.armR);
    this.body.position.z = -STUDENT_BACK_OFFSET;
    this.root.add(this.body);
    this.nextFake = 4 + rnd() * 10;
  }

  private buildDesk(rnd: () => number): THREE.Group {
    const desk = new THREE.Group();
    const top = mesh(new THREE.BoxGeometry(0.9, 0.04, 0.58), mat(0xdcb489, { roughness: 0.6 }));
    top.position.y = DESK_TOP;
    const metal = mat(0x64748b, { metalness: 0.4, roughness: 0.5 });
    const leg = new THREE.BoxGeometry(0.035, DESK_TOP, 0.035);
    for (const [lx, lz] of [[-0.41, -0.25], [0.41, -0.25], [-0.41, 0.25], [0.41, 0.25]]) {
      const m = mesh(leg, metal);
      m.position.set(lx, DESK_TOP / 2, lz);
      desk.add(m);
    }
    const shelf = mesh(new THREE.BoxGeometry(0.82, 0.02, 0.4), mat(0xb8956b));
    shelf.position.set(0, DESK_TOP - 0.14, 0.04);
    const front = mesh(new THREE.BoxGeometry(0.86, 0.14, 0.015), metal);
    front.position.set(0, DESK_TOP - 0.08, 0.27);

    const paper = mesh(new THREE.PlaneGeometry(0.3, 0.4), mat(0xffffff, { map: examPaper(), roughness: 1 }), false);
    paper.rotation.set(-Math.PI / 2, 0, Math.PI + 0.06);
    paper.position.set(-0.1, DESK_TOP + 0.022, -0.03);
    const omr = mesh(new THREE.PlaneGeometry(0.18, 0.34), mat(0xffffff, { map: omrCard(), roughness: 1 }), false);
    omr.rotation.set(-Math.PI / 2, 0, Math.PI - 0.08);
    omr.position.set(0.22, DESK_TOP + 0.022, -0.01);
    desk.add(top, shelf, front, paper, omr);

    // 소품 (자리마다 다르게)
    if (rnd() < 0.65) {
      const bottle = new THREE.Group();
      const body = mesh(new THREE.CylinderGeometry(0.035, 0.035, 0.2, 14), mat(0x93c5fd, { transparent: true, opacity: 0.75, roughness: 0.2 }));
      body.position.y = 0.1;
      const cap = mesh(new THREE.CylinderGeometry(0.03, 0.03, 0.04, 12), mat(rnd() < 0.5 ? 0xffffff : 0x1e293b));
      cap.position.y = 0.22;
      bottle.add(body, cap);
      bottle.position.set(rnd() < 0.5 ? 0.37 : -0.37, DESK_TOP + 0.02, 0.18);
      desk.add(bottle);
    }
    if (rnd() < 0.55) {
      const pcase = mesh(new THREE.BoxGeometry(0.2, 0.05, 0.07), mat([0xfda4af, 0xfde68a, 0xa7f3d0, 0xc4b5fd][Math.floor(rnd() * 4)]));
      pcase.position.set(0.05, DESK_TOP + 0.045, 0.22);
      pcase.rotation.y = (rnd() - 0.5) * 0.4;
      desk.add(pcase);
    }
    if (rnd() < 0.5) {
      const marker = mesh(new THREE.CylinderGeometry(0.012, 0.012, 0.13, 8), mat(0x111827));
      marker.rotation.z = Math.PI / 2;
      marker.rotation.y = rnd() * 0.8;
      marker.position.set(-0.3, DESK_TOP + 0.034, 0.2);
      desk.add(marker);
    }
    desk.position.z = DESK_CENTER_OFFSET;
    this.deskTop.position.set(0.22, DESK_TOP + 0.05, DESK_CENTER_OFFSET);
    this.root.add(this.deskTop);
    return desk;
  }

  private buildChair(): THREE.Group {
    const chair = new THREE.Group();
    const plastic = mat(0x3b82c4, { roughness: 0.5 });
    const seat = mesh(new THREE.BoxGeometry(0.42, 0.04, 0.4), plastic);
    seat.position.y = 0.44;
    const back = mesh(new THREE.BoxGeometry(0.42, 0.32, 0.03), plastic);
    back.position.set(0, 0.66, -0.2);
    const metal = mat(0x94a3b8, { metalness: 0.5, roughness: 0.4 });
    const leg = new THREE.CylinderGeometry(0.015, 0.015, 0.44, 6);
    for (const [x, z] of [[-0.18, -0.17], [0.18, -0.17], [-0.18, 0.17], [0.18, 0.17]]) {
      const m = mesh(leg, metal);
      m.position.set(x, 0.22, z);
      chair.add(m);
    }
    chair.add(seat, back);
    chair.position.z = -STUDENT_BACK_OFFSET - 0.02;
    return chair;
  }

  play(action: StudentAction, dur: number, now: number): void {
    this.playing = { action, start: now, dur };
  }

  /** 가짜 단서 동작을 가끔 혼자 함 (서버와 무관 — 감독관 헷갈리게 하는 연출) */
  ambient(now: number, enabled: boolean): void {
    if (!enabled || this.playing || now < this.nextFake) return;
    const picks: [StudentAction, number][] = [['glasses', 1.4], ['watch', 1.6], ['yawn', 1.8], ['stretch', 2.0]];
    const [a, d] = picks[Math.floor(Math.random() * picks.length)];
    this.play(a, d, now);
    this.nextFake = now + d + 6 + Math.random() * 14;
  }

  update(now: number): void {
    // 기본 자세: 책상에 팔을 올리고 옮겨 적음 (오른손이 조금씩 움직임)
    const t = now + this.phase;
    let armLx = -1.25;
    let armLz = 0.1;
    let armRx = -1.3 + Math.sin(t * 7) * 0.05;
    let armRz = -0.1 + Math.sin(t * 3.1) * 0.08;
    let headX = 0.16 + Math.sin(t * 0.7) * 0.04;
    let headY = Math.sin(t * 0.3) * 0.08;
    let headZ = 0;
    let torsoX = 0.1;
    let shakeX = 0;
    let scaleY = 1;
    let flash = 0;

    const p = this.playing;
    if (p) {
      const k = (now - p.start) / p.dur;
      if (k >= 1) {
        this.playing = null;
      } else {
        const e = envelope(k);
        switch (p.action) {
          case 'twitch':
            shakeX = Math.sin(k * 60) * 0.05 * (1 - k);
            headX -= 0.45 * e;
            headZ = Math.sin(k * 45) * 0.15 * (1 - k);
            torsoX -= 0.15 * e;
            break;
          case 'remark':
            torsoX += 0.2 * e;
            headX += 0.2 * e;
            armRx = -1.15 + Math.sin(now * 22) * 0.12 * e;
            armRz = -0.1 + Math.sin(now * 18) * 0.25 * e;
            break;
          case 'glasses':
            armRx = THREE.MathUtils.lerp(armRx, -2.7, e);
            armRz = -0.45 * e;
            headX -= 0.25 * e;
            break;
          case 'watch':
            armLx = THREE.MathUtils.lerp(armLx, -1.6, e);
            armLz = -0.5 * e;
            headX += 0.2 * e;
            headY += 0.35 * e;
            break;
          case 'yawn':
            headX -= 0.6 * e;
            armRx = THREE.MathUtils.lerp(armRx, -2.6, e);
            armRz = -0.3 * e;
            break;
          case 'stretch':
            armLx = THREE.MathUtils.lerp(armLx, -3.0, e);
            armRx = THREE.MathUtils.lerp(armRx, -3.0, e);
            headX -= 0.35 * e;
            scaleY = 1 + 0.06 * e;
            torsoX -= 0.15 * e;
            break;
          case 'hit':
            shakeX = Math.sin(k * 40) * 0.06 * (1 - k);
            headX += 0.5 * e;
            flash = 1 - k;
            break;
        }
      }
    }

    this.armL.rotation.set(armLx, 0, armLz);
    this.armR.rotation.set(armRx, 0, armRz);
    this.head.rotation.set(headX, headY, headZ);
    this.torso.rotation.x = torsoX;
    this.body.position.x = shakeX;
    this.body.scale.y = scaleY;
    this.torsoMat.emissive.setRGB(flash * 0.8, flash * 0.1, flash * 0.1);
  }

  setPosition(x: number, z: number): void {
    this.root.position.set(x, 0, z);
  }
}

/** 감독관 (냥이 화면에 보이는 선생님) */
export class ProctorAvatar {
  readonly root = new THREE.Group();
  private readonly armR: THREE.Group;
  private readonly head = new THREE.Group();
  private swingStart = -10;
  private stunUntil = 0;

  constructor() {
    const pants = mat(0x1f2937);
    for (const x of [-0.1, 0.1]) {
      const leg = mesh(new THREE.CapsuleGeometry(0.07, 0.62, 4, 8), pants);
      leg.position.set(x, 0.38, 0);
      this.root.add(leg);
    }
    const torso = mesh(new THREE.CapsuleGeometry(0.22, 0.42, 4, 14), mat(0xe2e8f0));
    torso.position.y = 1.05;
    const vest = mesh(new THREE.CapsuleGeometry(0.225, 0.3, 4, 14), mat(0x475569));
    vest.position.y = 1.02;
    vest.scale.set(1.02, 1, 1.02);
    const tie = mesh(new THREE.BoxGeometry(0.05, 0.22, 0.02), mat(0xb91c1c));
    tie.position.set(0, 1.18, 0.225);

    const r = 0.17;
    this.head.add(mesh(new THREE.SphereGeometry(r, 22, 16), mat(SKIN)));
    face(this.head, r);
    const hair = mesh(new THREE.SphereGeometry(r * 1.05, 22, 12, 0, Math.PI * 2, 0, Math.PI * 0.45), mat(0x27211d));
    hair.rotation.x = -0.3;
    this.head.add(hair, glasses(r, 0x0f172a));
    this.head.position.y = 1.6;

    const r1 = arm(0xe2e8f0, 0.5);
    this.armR = r1.pivot;
    this.armR.position.set(-0.28, 1.38, 0);
    const sw = flyswatter();
    sw.position.y = -0.5;
    sw.rotation.x = Math.PI;
    this.armR.add(sw);
    const l = arm(0xe2e8f0, 0.5);
    l.pivot.position.set(0.28, 1.38, 0);
    l.pivot.rotation.x = -0.15;

    this.root.add(torso, vest, tie, this.head, this.armR, l.pivot);
  }

  swing(now: number): void {
    this.swingStart = now;
  }

  stun(until: number): void {
    this.stunUntil = until;
  }

  update(now: number, x: number, z: number, fx: number, fz: number): void {
    this.root.position.set(x, 0, z);
    this.root.rotation.y = Math.atan2(fx, fz);
    const k = (now - this.swingStart) / 0.4;
    const swing = k >= 0 && k < 1 ? Math.sin(k * Math.PI) : 0;
    this.armR.rotation.x = -0.35 - swing * 2.2;
    this.head.rotation.z = now < this.stunUntil ? Math.sin(now * 8) * 0.25 : 0;
  }
}

/** 초록 그물 파리채 (손잡이 + 격자 망). 손잡이 끝이 원점, +y 로 뻗음 */
export function flyswatter(scale = 1): THREE.Group {
  const g = new THREE.Group();
  const green = mat(0x22c55e, { roughness: 0.45 });
  const handle = mesh(new THREE.CylinderGeometry(0.012, 0.014, 0.5, 8), green, false);
  handle.position.y = 0.25;
  const net = new THREE.Mesh(new THREE.PlaneGeometry(0.2, 0.22),
    new THREE.MeshStandardMaterial({ map: swatterMesh(), transparent: true, side: THREE.DoubleSide, roughness: 0.6, depthWrite: false }));
  net.position.y = 0.62;
  const rim = mesh(new THREE.TorusGeometry(0.11, 0.008, 6, 4), green, false);
  rim.rotation.z = Math.PI / 4;
  rim.scale.set(1.28, 1.42, 1);
  rim.position.y = 0.62;
  g.add(handle, net, rim);
  g.scale.setScalar(scale);
  return g;
}

/** 감독관 1인칭: 파리채를 쥔 손 (카메라에 붙임) */
export function firstPersonHand(): THREE.Group {
  const g = new THREE.Group();
  const sleeve = mesh(new THREE.CylinderGeometry(0.05, 0.06, 0.3, 12), mat(0xe2e8f0), false);
  sleeve.rotation.x = Math.PI / 2;
  sleeve.position.set(0.04, -0.05, 0.16);
  const hand = mesh(new THREE.SphereGeometry(0.055, 14, 12), mat(SKIN), false);
  hand.scale.set(1, 1.25, 1.1);
  const thumb = mesh(new THREE.CapsuleGeometry(0.016, 0.05, 4, 6), mat(SKIN), false);
  thumb.position.set(-0.04, 0.03, -0.02);
  thumb.rotation.z = 0.6;
  const sw = flyswatter(1.0);
  sw.position.set(0, -0.06, 0);
  g.add(sleeve, hand, thumb, sw);
  return g;
}

import * as THREE from 'three';
import { Phase, Role, type GameEvent } from '../gen/catchnyang/v1/game_pb';
import { CFG, lookRange, toolSpec, type LookMode } from '../config';
import type { GameStore, Vec2 } from '../state/game-store';
import { ProctorAvatar, StudentAvatar, firstPersonHand } from './avatars';
import { buildRoom, seatTag } from './classroom';
import { pushOutOfDesks } from './collision';
import { detectorPulse, dizzyStars, sparks, toolEffect, type Effect } from './effects';
import { SEATED_EYE_HEIGHT, STANDING_EYE_HEIGHT, STUDENT_BACK_OFFSET, deg } from './layout';

/** 3D 화면이 밖(앱)에 알리는 행동. 실제 전송은 앱이 함 */
export interface SceneActions {
  possess(seat: number): void;
  proctorMove(dx: number, dz: number, fx: number, fz: number): void;
  swat(): void;
  detect(): void;
}

/** 학생마다 다른 리본·머리핀 색 (구분용) */
/** 마우스 감도 1.0 일 때 1픽셀당 회전량. 실제 = 이 값 × sensitivity (설정에서 조절, 기본 1.0) */
const PROCTOR_LOOK_RAD_PER_PX = 0.0011;
const CAT_LOOK_DEG_PER_PX = 0.12;
const SENSITIVITY_KEY = 'catchnyang.sensitivity';

const HAND_POS = { x: 0.36, y: -0.34, z: -0.52 };
const HAND_ROT = { x: -0.6, y: 0.1, z: 0.28 };

const STUDENT_COLORS = [0xe11d48, 0x2563eb, 0x16a34a, 0xf59e0b, 0x9333ea, 0x0891b2, 0xea580c, 0xdb2777, 0x4f46e5];

/**
 * 게임 3D 화면. 상태는 {@link GameStore} 에서 읽기만 하고, 조작은 {@link SceneActions} 로 내보낸다.
 * <ul>
 *   <li>냥이: 빙의한 학생 눈높이 1인칭. 살피기 모드(좌·기본·우) 범위 안에서 고개만 돌림. 다른 학생 클릭 = 빙의</li>
 *   <li>감독관: 서서 걷는 1인칭 (클릭하면 마우스 잠금, WASD 이동). 이동은 클라에서 먼저 움직이고(예측) 서버 위치로 보정</li>
 * </ul>
 */
export class GameScene {
  readonly renderer: THREE.WebGLRenderer;
  private readonly scene = new THREE.Scene();
  private readonly camera = new THREE.PerspectiveCamera(70, 1, 0.05, 60);
  private readonly students: StudentAvatar[] = [];
  private readonly proctorAvatar = new ProctorAvatar();
  private readonly hand = firstPersonHand();
  private effects: Effect[] = [];
  private readonly clock = new THREE.Clock();
  private readonly raycaster = new THREE.Raycaster();
  private readonly unsubs: (() => void)[] = [];

  private role: Role = Role.UNSPECIFIED;

  /** 마우스 감도 배율 (0.2 ~ 2.0). 브라우저에 저장 */
  sensitivity = readSensitivity();

  // 냥이 시선
  lookMode: LookMode = 'center';
  private lookYaw = 0;     // 도, 음수 = 왼쪽
  private lookPitch = -12; // 도, 음수 = 아래
  private dragging = false;

  // 감독관
  private yaw = 0;          // 라디안, 0 = -z (학생 쪽)
  private pitch = 0;
  private readonly keys = new Set<string>();
  private local: Vec2 = { x: 0, z: 0 };
  private lastServerSync = 0;
  private sentMove = { dx: 0, dz: 0 };
  private sentFacing = { x: 0, z: -1 };
  private lastFacingSentAt = 0;
  private swatAnimStart = -10;
  /** 지금 바로 앞에 있는 자리와 그 앞에 선 시각 (검사 진행 표시용) */
  near: { seat: number; since: number } = { seat: -1, since: 0 };

  constructor(private readonly container: HTMLElement, private readonly store: GameStore, private readonly actions: SceneActions) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' });
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
    this.renderer.shadowMap.enabled = true;
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping;
    this.renderer.toneMappingExposure = 1.05;
    container.appendChild(this.renderer.domElement);
    this.camera.rotation.order = 'YXZ';

    buildRoom(this.scene);
    this.scene.add(this.proctorAvatar.root);

    // 감독관 1인칭: 오른쪽 아래에서 파리채를 쥔 손
    this.hand.position.set(HAND_POS.x, HAND_POS.y, HAND_POS.z);
    this.hand.rotation.set(HAND_ROT.x, HAND_ROT.y, HAND_ROT.z);
    this.camera.add(this.hand);
    this.scene.add(this.camera);

    this.resize();
    window.addEventListener('resize', this.onResize);
    this.bindInput();
    this.unsubs.push(store.onEvent((e) => this.onGameEvent(e)));
  }

  // =====================================================================
  // 판 시작 / 종료
  // =====================================================================

  /** 스냅샷을 받은 뒤 부름 (매칭·재접속·재대결) */
  start(): void {
    this.role = this.store.role;
    this.ensureStudents();
    this.effects.forEach((e) => e.object.removeFromParent());
    this.effects = [];
    this.lookMode = 'center';
    this.lookYaw = 0;
    this.local = { ...this.store.proctorPos };
    this.yaw = Math.atan2(-this.store.proctorFacing.x, -this.store.proctorFacing.z);
    this.pitch = 0;
    this.sentMove = { dx: 0, dz: 0 };
    this.keys.clear();
    this.hand.visible = this.role === Role.PROCTOR;
    this.proctorAvatar.root.visible = this.role !== Role.PROCTOR;
    if (document.pointerLockElement) document.exitPointerLock();
  }

  private ensureStudents(): void {
    const seats = this.store.seats;
    if (this.students.length === seats.length) return;
    this.students.forEach((s) => s.root.removeFromParent());
    this.students.length = 0;
    seats.forEach((p, i) => {
      const s = new StudentAvatar(i, STUDENT_COLORS[i % STUDENT_COLORS.length]);
      s.setPosition(p.x, p.z);
      s.root.add(seatTag(i));
      this.scene.add(s.root);
      this.students.push(s);
    });
  }

  dispose(): void {
    window.removeEventListener('resize', this.onResize);
    this.unsubs.forEach((u) => u());
    this.renderer.dispose();
    this.renderer.domElement.remove();
  }

  // =====================================================================
  // 매 프레임
  // =====================================================================

  frame(): void {
    const dt = Math.min(0.05, this.clock.getDelta());
    const now = this.clock.elapsedTime;
    const s = this.store;

    if (this.role === Role.PROCTOR) this.updateProctor(dt, now);
    else this.updateCatCamera(dt);

    // 감독관 아바타 (냥이 화면에 보임): 서버 위치로 부드럽게
    const pp = s.proctorPos;
    const cur = this.proctorAvatar.root.position;
    const k = 1 - Math.pow(0.0005, dt);
    this.proctorAvatar.update(now, cur.x + (pp.x - cur.x) * k, cur.z + (pp.z - cur.z) * k, s.proctorFacing.x, s.proctorFacing.z);

    const mySeat = s.cat?.seat ?? -1;
    const playing = s.phase === Phase.PLAYING || s.phase === Phase.PREPARE;
    for (const st of this.students) {
      // 내가 빙의한 학생은 몸을 숨김 (1인칭)
      st.body.visible = !(this.role === Role.CAT && st.seat === mySeat);
      st.ambient(now, playing);
      st.update(now);
    }

    this.effects = this.effects.filter((e) => {
      const alive = e.update(now);
      if (!alive) e.object.removeFromParent();
      return alive;
    });

    this.renderer.render(this.scene, this.camera);
  }

  // ---------- 냥이 ----------

  private updateCatCamera(dt: number): void {
    const s = this.store;
    const seat = s.cat?.seat ?? -1;
    const p = seat >= 0 ? s.seats[seat] : undefined;
    if (!p) {
      // 시작 전·기절 중: 교실 뒤에서 내려다봄
      this.camera.position.lerp(new THREE.Vector3(0, 3.1, -6.2), 1 - Math.pow(0.01, dt));
      this.camera.rotation.set(-0.35, Math.PI, 0);
      return;
    }
    const [lo, hi] = lookRange(this.lookMode);
    // 모드를 바꾸면 범위 안으로 부드럽게 들어감
    const target = THREE.MathUtils.clamp(this.lookYaw, lo, hi);
    this.lookYaw += (target - this.lookYaw) * (1 - Math.pow(0.001, dt));
    this.camera.position.set(p.x, SEATED_EYE_HEIGHT, p.z - STUDENT_BACK_OFFSET + 0.05);
    // 학생은 +z 를 봄. 고개를 오른쪽(+)으로 돌리면 -x 쪽
    this.camera.rotation.set(deg(this.lookPitch), Math.PI - deg(this.lookYaw), 0);
  }

  /** 미니맵용: 감독관이 지금 냥이 시야(110°) 안에 있는지 */
  proctorInCatView(): boolean {
    const s = this.store;
    if (!s.cat || s.cat.seat < 0) return false;
    const dir = new THREE.Vector3();
    this.camera.getWorldDirection(dir);
    const to = new THREE.Vector3(s.proctorPos.x - this.camera.position.x, 0, s.proctorPos.z - this.camera.position.z);
    dir.y = 0;
    if (to.lengthSq() < 0.01) return true;
    return dir.normalize().angleTo(to.normalize()) <= deg(CFG.cat.fovDeg / 2);
  }

  setSensitivity(v: number): void {
    this.sensitivity = Math.min(2, Math.max(0.2, v));
    try {
      localStorage.setItem(SENSITIVITY_KEY, String(this.sensitivity));
    } catch {
      // 저장 못 해도 이번 판엔 적용
    }
  }

  setLookMode(m: LookMode): void {
    this.lookMode = m;
    const [lo, hi] = lookRange(m);
    this.lookYaw = THREE.MathUtils.clamp(this.lookYaw, lo, hi);
  }

  // ---------- 감독관 ----------

  private updateProctor(dt: number, now: number): void {
    const s = this.store;
    const stunned = s.proctorStunned();
    const canMove = s.phase === Phase.PLAYING && !stunned;

    // 입력 → 이동 방향 (시선 기준)
    let fwd = 0;
    let strafe = 0;
    if (canMove) {
      if (this.keys.has('KeyW') || this.keys.has('ArrowUp')) fwd += 1;
      if (this.keys.has('KeyS') || this.keys.has('ArrowDown')) fwd -= 1;
      if (this.keys.has('KeyD') || this.keys.has('ArrowRight')) strafe += 1;
      if (this.keys.has('KeyA') || this.keys.has('ArrowLeft')) strafe -= 1;
    }
    const f = { x: -Math.sin(this.yaw), z: -Math.cos(this.yaw) };
    const r = { x: Math.cos(this.yaw), z: -Math.sin(this.yaw) };
    let dx = f.x * fwd + r.x * strafe;
    let dz = f.z * fwd + r.z * strafe;
    const len = Math.hypot(dx, dz);
    if (len > 0) {
      dx /= len;
      dz /= len;
    }

    // 예측: 서버와 같은 속도로 먼저 움직임
    const speed = CFG.proctor.moveSpeedMps;
    this.local.x = THREE.MathUtils.clamp(this.local.x + dx * speed * dt, -CFG.classroom.halfWidthM, CFG.classroom.halfWidthM);
    this.local.z = THREE.MathUtils.clamp(this.local.z + dz * speed * dt, -CFG.classroom.halfDepthM, CFG.classroom.halfDepthM);
    this.local = pushOutOfDesks(s.seats, this.local.x, this.local.z);  // 서버와 같은 충돌 계산

    // 보정: 서버 위치와 크게 어긋나면 바로 맞추고, 멈춰 있을 땐 천천히 따라감
    const sp = s.proctorPos;
    const ex = sp.x - this.local.x;
    const ez = sp.z - this.local.z;
    const err = Math.hypot(ex, ez);
    const moving = len > 0;
    if (err > 1.0) {
      this.local = { ...sp };
    } else if (!moving) {
      const k = 1 - Math.pow(0.02, dt);
      this.local.x += ex * k;
      this.local.z += ez * k;
    } else if (s.proctorSyncedAt !== this.lastServerSync) {
      this.local.x += ex * 0.05;
      this.local.z += ez * 0.05;
    }
    this.lastServerSync = s.proctorSyncedAt;

    this.sendProctorInput(dx, dz, now);
    s.updateInspectRange(this.local);
    this.updateNear(now);

    const bob = moving ? Math.sin(now * 9) * 0.025 : 0;
    this.camera.position.set(this.local.x, STANDING_EYE_HEIGHT + bob, this.local.z);
    const wobble = stunned ? Math.sin(now * 7) * 0.08 : 0;
    this.camera.rotation.set(this.pitch, this.yaw + wobble, wobble * 0.5);

    // 손에 든 파리채 휘두르기 (앞으로 내려침)
    const k = (now - this.swatAnimStart) / 0.35;
    const swing = k >= 0 && k < 1 ? Math.sin(k * Math.PI) : 0;
    this.hand.rotation.set(HAND_ROT.x - swing * 1.2, HAND_ROT.y + swing * 0.3, HAND_ROT.z - swing * 0.2);
    this.hand.position.set(HAND_POS.x - swing * 0.08, HAND_POS.y + bob * 0.5 + swing * 0.05, HAND_POS.z - swing * 0.1);
  }

  /** 바뀐 입력만 보냄. 시선은 3° 이상 바뀌었을 때 최대 10번/초 */
  private sendProctorInput(dx: number, dz: number, now: number): void {
    const fx = -Math.sin(this.yaw);
    const fz = -Math.cos(this.yaw);
    const moveChanged = Math.abs(dx - this.sentMove.dx) > 0.01 || Math.abs(dz - this.sentMove.dz) > 0.01;
    const facingDelta = Math.acos(THREE.MathUtils.clamp(fx * this.sentFacing.x + fz * this.sentFacing.z, -1, 1));
    const facingChanged = facingDelta > deg(3) && now - this.lastFacingSentAt > 0.1;
    if (!moveChanged && !facingChanged) return;
    this.actions.proctorMove(dx, dz, fx, fz);
    this.sentMove = { dx, dz };
    this.sentFacing = { x: fx, z: fz };
    this.lastFacingSentAt = now;
  }

  private updateNear(now: number): void {
    let best = -1;
    let bestD = CFG.classroom.interactRangeM;
    this.store.seats.forEach((p, i) => {
      const d = Math.hypot(p.x - this.local.x, p.z - this.local.z);
      if (d <= bestD) {
        best = i;
        bestD = d;
      }
    });
    if (best !== this.near.seat) this.near = { seat: best, since: now };
  }

  /** 검사 진행 0~1 (서버가 InspectReady 를 보내면 1) */
  inspectProgress(): number {
    if (this.role !== Role.PROCTOR || this.near.seat < 0) return 0;
    if (this.store.proctor?.inspectingSeat === this.near.seat) return 1;
    return Math.min(0.99, (this.clock.elapsedTime - this.near.since) / CFG.proctor.inspectHoldSec);
  }

  /** 미니맵용: 내(감독관) 위치·시선 */
  proctorLocal(): { pos: Vec2; facing: Vec2 } {
    return { pos: { ...this.local }, facing: { x: -Math.sin(this.yaw), z: -Math.cos(this.yaw) } };
  }

  // =====================================================================
  // 서버 사건 → 연출
  // =====================================================================

  private onGameEvent(e: GameEvent): void {
    const now = this.clock.elapsedTime;
    const k = e.kind;
    const st = (seat: number) => this.students[seat];
    switch (k.case) {
      case 'possessed':
        st(k.value.seat)?.play('twitch', 0.6, now);
        break;
      case 'remarkStarted':
        st(k.value.seat)?.play('remark', CFG.cat.remarkSec, now);
        break;
      case 'toolStarted': {
        const s = st(k.value.seat);
        if (!s) break;
        const dur = toolSpec(k.value.tool)?.lockSec ?? 3;
        this.addEffect(toolEffect(k.value.tool, { eyes: s.eyes, wrist: s.wrist, desk: s.deskTop }, now, dur));
        break;
      }
      case 'toolDestroyed': {
        const s = st(k.value.seat);
        if (!s) break;
        const at = new THREE.Vector3();
        s.head.getWorldPosition(at);
        this.addEffect(sparks(at, now), this.scene);
        break;
      }
      case 'catchResult': {
        this.proctorAvatar.swing(now);
        const s = st(k.value.seat);
        if (!s) break;
        s.play('hit', 0.6, now);
        if (k.value.success) this.addEffect(dizzyStars(s.head, now, CFG.cat.stunSec));
        else this.proctorAvatar.stun(now + CFG.proctor.swatter.failStunSec);
        break;
      }
      case 'detectorResult': {
        const p = this.role === Role.PROCTOR ? this.local : this.store.proctorPos;
        this.addEffect(detectorPulse(p.x, p.z, now, k.value.hit), this.scene);
        break;
      }
      default:
        break;
    }
  }

  private addEffect(e: Effect, parent?: THREE.Object3D): void {
    if (parent && !e.object.parent) parent.add(e.object);
    this.effects.push(e);
  }

  // =====================================================================
  // 입력
  // =====================================================================

  private bindInput(): void {
    const el = this.renderer.domElement;

    el.addEventListener('pointerdown', (ev) => {
      if (this.role === Role.PROCTOR) {
        if (document.pointerLockElement !== el) {
          void el.requestPointerLock();
          return;
        }
        if (ev.button === 0) this.doSwat();
        return;
      }
      if (this.role === Role.CAT) {
        this.dragging = true;
        el.setPointerCapture(ev.pointerId);
        this.dragMoved = 0;
      }
    });
    el.addEventListener('pointermove', (ev) => {
      if (this.role === Role.PROCTOR && document.pointerLockElement === el) {
        const k = PROCTOR_LOOK_RAD_PER_PX * this.sensitivity;
        this.yaw -= ev.movementX * k;
        this.pitch = THREE.MathUtils.clamp(this.pitch - ev.movementY * k, -1.2, 1.0);
      } else if (this.role === Role.CAT && this.dragging) {
        this.dragMoved += Math.abs(ev.movementX) + Math.abs(ev.movementY);
        const [lo, hi] = lookRange(this.lookMode);
        this.lookYaw = THREE.MathUtils.clamp(this.lookYaw - ev.movementX * CAT_LOOK_DEG_PER_PX * this.sensitivity, lo, hi);
        this.lookPitch = THREE.MathUtils.clamp(this.lookPitch - ev.movementY * CAT_LOOK_DEG_PER_PX * 0.8 * this.sensitivity, -40, 15);
      }
    });
    el.addEventListener('pointerup', (ev) => {
      if (this.role !== Role.CAT || !this.dragging) return;
      this.dragging = false;
      // 거의 안 움직이고 뗐으면 클릭 → 그 학생에게 빙의
      if (this.dragMoved < 6) this.clickStudent(ev);
    });

    window.addEventListener('keydown', (ev) => {
      if (isTyping(ev)) return;
      this.keys.add(ev.code);
      if (this.role === Role.PROCTOR) {
        if (ev.code === 'Space') {
          ev.preventDefault();
          this.doSwat();
        } else if (ev.code === 'KeyE') this.actions.detect();
      } else if (this.role === Role.CAT) {
        if (ev.code === 'KeyQ') this.setLookMode('left');
        else if (ev.code === 'KeyW') this.setLookMode('center');
        else if (ev.code === 'KeyE') this.setLookMode('right');
      }
    });
    window.addEventListener('keyup', (ev) => this.keys.delete(ev.code));
    window.addEventListener('blur', () => this.keys.clear());
  }

  private dragMoved = 0;

  private doSwat(): void {
    this.swatAnimStart = this.clock.elapsedTime;
    this.actions.swat();
  }

  private clickStudent(ev: PointerEvent): void {
    const rect = this.renderer.domElement.getBoundingClientRect();
    const ndc = new THREE.Vector2(((ev.clientX - rect.left) / rect.width) * 2 - 1, -((ev.clientY - rect.top) / rect.height) * 2 + 1);
    this.raycaster.setFromCamera(ndc, this.camera);
    const hits = this.raycaster.intersectObjects(this.students.map((s) => s.root), true);
    for (const h of hits) {
      const seat = this.students.findIndex((s) => isDescendant(h.object, s.root));
      if (seat >= 0 && seat !== this.store.cat?.seat) {
        this.actions.possess(seat);
        return;
      }
    }
  }

  private readonly onResize = () => this.resize();

  private resize(): void {
    const w = this.container.clientWidth || window.innerWidth;
    const h = this.container.clientHeight || window.innerHeight;
    this.renderer.setSize(w, h);
    this.camera.aspect = w / h;
    // 냥이 시야는 가로 110° 고정 (규칙) → 화면 비율에 맞춰 세로 시야를 계산
    const hfov = this.role === Role.CAT ? CFG.cat.fovDeg : 90;
    this.camera.fov = THREE.MathUtils.radToDeg(2 * Math.atan(Math.tan(deg(hfov) / 2) / this.camera.aspect));
    this.camera.updateProjectionMatrix();
  }

  /** 역할이 정해진 뒤 시야각을 다시 계산하려고 앱이 부름 */
  refreshProjection(): void {
    this.resize();
  }
}

function readSensitivity(): number {
  try {
    const v = Number(localStorage.getItem(SENSITIVITY_KEY));
    return v >= 0.2 && v <= 2 ? v : 1;
  } catch {
    return 1;
  }
}

function isDescendant(o: THREE.Object3D, root: THREE.Object3D): boolean {
  for (let p: THREE.Object3D | null = o; p; p = p.parent) if (p === root) return true;
  return false;
}

function isTyping(ev: KeyboardEvent): boolean {
  const t = ev.target as HTMLElement | null;
  return !!t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA');
}

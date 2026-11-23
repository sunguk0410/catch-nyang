import * as THREE from 'three';
import { CFG } from '../config';
import { STUDENT_BACK_OFFSET, seatLabel } from './layout';
import { chalkboard, corkBoard, floorTiles } from './textures';

const WALL_H = 3.2;

function box(w: number, h: number, d: number, color: number, opts: THREE.MeshStandardMaterialParameters = {}): THREE.Mesh {
  const m = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), new THREE.MeshStandardMaterial({ color, roughness: 0.8, ...opts }));
  m.castShadow = m.receiveShadow = true;
  return m;
}

/**
 * 밝은 교실: 타일 바닥, 흰 벽 + 아래 걸레받이, 학생 기준 왼쪽(+x) 벽은 큰 창문, 천장 조명,
 * 앞쪽 칠판·교탁·시계, 뒤쪽 게시판·사물함. 학생·책상은 avatars.ts
 */
export function buildRoom(scene: THREE.Scene): void {
  const halfW = CFG.classroom.halfWidthM + 1.4;
  const halfD = CFG.classroom.halfDepthM + 1.0;

  scene.background = new THREE.Color(0xeef4f8);

  // 바닥
  const floor = new THREE.Mesh(new THREE.PlaneGeometry(halfW * 2, halfD * 2),
    new THREE.MeshStandardMaterial({ map: floorTiles(halfW * 2 / 1.2, halfD * 2 / 1.2), roughness: 0.55 }));
  floor.rotation.x = -Math.PI / 2;
  floor.receiveShadow = true;
  scene.add(floor);

  // 천장 + 조명 패널
  const ceiling = new THREE.Mesh(new THREE.PlaneGeometry(halfW * 2, halfD * 2), new THREE.MeshStandardMaterial({ color: 0xf8fafc, roughness: 1 }));
  ceiling.rotation.x = Math.PI / 2;
  ceiling.position.y = WALL_H;
  scene.add(ceiling);
  const panelMat = new THREE.MeshBasicMaterial({ color: 0xffffff });
  for (const x of [-2.4, 0, 2.4]) {
    for (const z of [-3.2, 0, 3.2]) {
      const p = new THREE.Mesh(new THREE.PlaneGeometry(1.2, 0.3), panelMat);
      p.rotation.x = Math.PI / 2;
      p.position.set(x, WALL_H - 0.01, z);
      scene.add(p);
    }
  }

  // 벽: 위는 흰색, 아래 1m 는 연한 회녹색 판
  const wallMat = new THREE.MeshStandardMaterial({ color: 0xf8fafc, roughness: 0.95 });
  const lowerMat = new THREE.MeshStandardMaterial({ color: 0xd5e2de, roughness: 0.9 });
  const wall = (w: number, x: number, z: number, ry: number) => {
    const m = new THREE.Mesh(new THREE.PlaneGeometry(w, WALL_H), wallMat);
    m.position.set(x, WALL_H / 2, z);
    m.rotation.y = ry;
    m.receiveShadow = true;
    scene.add(m);
    const lower = new THREE.Mesh(new THREE.PlaneGeometry(w, 1.0), lowerMat);
    lower.position.set(x, 0.5, z);
    lower.rotation.y = ry;
    lower.translateZ(0.005);
    scene.add(lower);
  };
  wall(halfW * 2, 0, halfD, Math.PI);        // 앞 (칠판)
  wall(halfW * 2, 0, -halfD, 0);             // 뒤
  wall(halfD * 2, halfW, 0, -Math.PI / 2);   // 학생 기준 왼쪽 (창문)
  wall(halfD * 2, -halfW, 0, Math.PI / 2);   // 학생 기준 오른쪽 (복도)

  // 창문 (왼쪽 벽 거의 전체) — 밝은 하늘 + 흰 창틀
  const sky = new THREE.MeshBasicMaterial({ color: 0xd6ecff });
  const frameMat = new THREE.MeshStandardMaterial({ color: 0xffffff, roughness: 0.4 });
  const winW = halfD * 2 - 1.6;
  const glass = new THREE.Mesh(new THREE.PlaneGeometry(winW, 1.5), sky);
  glass.position.set(halfW - 0.02, 1.85, 0);
  glass.rotation.y = -Math.PI / 2;
  scene.add(glass);
  for (let i = 0; i <= 6; i++) {
    const z = -winW / 2 + (winW / 6) * i;
    const mullion = new THREE.Mesh(new THREE.BoxGeometry(0.06, 1.55, 0.06), frameMat);
    mullion.position.set(halfW - 0.04, 1.85, z);
    scene.add(mullion);
  }
  for (const y of [1.1, 2.6, 1.85]) {
    const rail = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.06, winW), frameMat);
    rail.position.set(halfW - 0.04, y, 0);
    scene.add(rail);
  }

  // 칠판 + 교탁 + 시계 + 스피커
  const board = new THREE.Mesh(new THREE.PlaneGeometry(5, 1.25), new THREE.MeshStandardMaterial({ map: chalkboard('모의고사 · OMR 작성 시간'), roughness: 0.9 }));
  board.position.set(0, 1.7, halfD - 0.05);  // 틀 앞면(halfD-0.03)보다 앞 — 같은 면이면 깜빡임
  board.rotation.y = Math.PI;
  scene.add(board);
  const frame = box(5.2, 1.42, 0.03, 0xa98463);
  frame.position.set(0, 1.7, halfD - 0.015);
  scene.add(frame);
  const tray = box(5.0, 0.04, 0.12, 0xa98463);
  tray.position.set(0, 1.06, halfD - 0.08);
  scene.add(tray);
  const lectern = box(1.3, 1.0, 0.55, 0x9a6b45);
  lectern.position.set(0, 0.5, CFG.classroom.proctorSpawnZ + 0.75);
  scene.add(lectern);
  const clock = new THREE.Mesh(new THREE.CylinderGeometry(0.24, 0.24, 0.04, 32), new THREE.MeshStandardMaterial({ color: 0xffffff }));
  clock.rotation.x = Math.PI / 2;
  clock.position.set(-3.2, 2.6, halfD - 0.03);
  scene.add(clock);
  const rim = new THREE.Mesh(new THREE.TorusGeometry(0.24, 0.02, 8, 32), new THREE.MeshStandardMaterial({ color: 0x1f2937 }));
  rim.position.copy(clock.position);
  scene.add(rim);
  // 시계 바늘 (교실 안쪽에서 보이게 앞면에)
  const handMat = new THREE.MeshBasicMaterial({ color: 0x111827 });
  for (const [len, angle] of [[0.15, 0.9], [0.2, -1.8]] as const) {
    const hand = new THREE.Mesh(new THREE.PlaneGeometry(0.018, len), handMat);
    hand.geometry.translate(0, len / 2, 0);
    hand.position.set(clock.position.x, clock.position.y, clock.position.z - 0.025);
    hand.rotation.set(0, Math.PI, angle);
    scene.add(hand);
  }
  const speaker = box(0.4, 0.3, 0.15, 0xe5e7eb);
  speaker.position.set(3.2, 2.7, halfD - 0.08);
  scene.add(speaker);

  // 뒤쪽 게시판 + 사물함
  const cork = new THREE.Mesh(new THREE.PlaneGeometry(3.2, 1.2), new THREE.MeshStandardMaterial({ map: corkBoard(), roughness: 1 }));
  cork.position.set(-1.2, 1.9, -halfD + 0.02);
  scene.add(cork);
  const lockerMat = [0xcbd5e1, 0xb7c3d0];
  for (let i = 0; i < 8; i++) {
    const l = box(0.48, 1.0, 0.45, lockerMat[i % 2], { roughness: 0.5, metalness: 0.2 });
    l.position.set(halfW - 0.6 - i * 0.5, 0.5, -halfD + 0.25);
    scene.add(l);
  }

  // 복도 쪽 문
  const door = box(0.05, 2.1, 1.0, 0x9a6b45);
  door.position.set(-halfW + 0.03, 1.05, halfD - 1.6);
  scene.add(door);

  // 조명: 창문 쪽에서 들어오는 햇빛 + 밝은 환경광
  scene.add(new THREE.AmbientLight(0xffffff, 0.55));
  scene.add(new THREE.HemisphereLight(0xffffff, 0xcbd5e1, 1.25));
  const sun = new THREE.DirectionalLight(0xfff4e0, 1.6);
  sun.position.set(halfW + 4, 7, 1.5);
  sun.target.position.set(0, 0, 0);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.02;
  const sc = sun.shadow.camera;
  sc.left = -8; sc.right = 8; sc.top = 8; sc.bottom = -8; sc.near = 1; sc.far = 25;
  scene.add(sun, sun.target);
}

/** 학생 머리 위 작은 자리 번호 (화면에서 늘 같은 크기) */
export function seatTag(seat: number): THREE.Sprite {
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d')!;
  g.fillStyle = 'rgba(15,23,42,0.75)';
  g.beginPath();
  g.arc(32, 32, 26, 0, Math.PI * 2);
  g.fill();
  g.strokeStyle = 'rgba(255,255,255,0.9)';
  g.lineWidth = 3;
  g.stroke();
  g.fillStyle = '#fff';
  g.font = 'bold 32px system-ui, sans-serif';
  g.textAlign = 'center';
  g.textBaseline = 'middle';
  g.fillText(String(seatLabel(seat)), 32, 34);
  const tex = new THREE.CanvasTexture(c);
  tex.colorSpace = THREE.SRGBColorSpace;
  const s = new THREE.Sprite(new THREE.SpriteMaterial({ map: tex, depthWrite: false, sizeAttenuation: false, transparent: true }));
  s.scale.setScalar(0.032);
  s.position.set(0, 1.55, -STUDENT_BACK_OFFSET);
  s.renderOrder = 10;
  return s;
}

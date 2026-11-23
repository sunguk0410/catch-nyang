import * as THREE from 'three';
import { ToolType } from '../gen/catchnyang/v1/game_pb';

/** 짧게 떴다 사라지는 연출들. update() 가 false 를 돌려주면 장면에서 뺌 */
export interface Effect {
  readonly object: THREE.Object3D;
  update(now: number): boolean;
}

let glowTexture: THREE.Texture | null = null;

/** 가운데가 밝은 동그란 빛 (스프라이트용) */
function glow(): THREE.Texture {
  if (glowTexture) return glowTexture;
  const c = document.createElement('canvas');
  c.width = c.height = 64;
  const g = c.getContext('2d')!;
  const grd = g.createRadialGradient(32, 32, 0, 32, 32, 32);
  grd.addColorStop(0, 'rgba(255,255,255,1)');
  grd.addColorStop(0.25, 'rgba(255,255,255,0.8)');
  grd.addColorStop(1, 'rgba(255,255,255,0)');
  g.fillStyle = grd;
  g.fillRect(0, 0, 64, 64);
  glowTexture = new THREE.CanvasTexture(c);
  return glowTexture;
}

function glowSprite(color: number, size: number): THREE.Sprite {
  const s = new THREE.Sprite(new THREE.SpriteMaterial({
    map: glow(), color, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending,
  }));
  s.scale.setScalar(size);
  return s;
}

/**
 * 도구 사용 이펙트 (감독관에게 보이는 진짜 단서).
 * AI 안경 = 렌즈 반사광, 스마트워치 = 손목 빛, 지우개 카메라 = 책상 위 작은 불빛.
 */
export function toolEffect(tool: ToolType, anchors: { eyes: THREE.Object3D; wrist: THREE.Object3D; desk: THREE.Object3D },
                           now: number, durSec: number): Effect {
  const spec = {
    [ToolType.AI_GLASSES]: { anchor: anchors.eyes, color: 0x9be7ff, size: 0.35, rate: 9 },
    [ToolType.SMART_WATCH]: { anchor: anchors.wrist, color: 0x7cff9b, size: 0.22, rate: 4 },
    [ToolType.ERASER_CAMERA]: { anchor: anchors.desk, color: 0xff5050, size: 0.09, rate: 2.5 },
    [ToolType.WIRELESS_EARPHONE]: { anchor: anchors.eyes, color: 0xffffff, size: 0.12, rate: 6 },
    [ToolType.UNSPECIFIED]: { anchor: anchors.eyes, color: 0xffffff, size: 0.1, rate: 1 },
  }[tool];
  const sprite = glowSprite(spec.color, spec.size);
  spec.anchor.add(sprite);
  const end = now + durSec;
  return {
    object: sprite,
    update(t) {
      if (t >= end) {
        sprite.removeFromParent();
        return false;
      }
      const flicker = 0.55 + 0.45 * Math.abs(Math.sin(t * spec.rate * Math.PI));
      (sprite.material as THREE.SpriteMaterial).opacity = flicker;
      return true;
    },
  };
}

/** 도구 파괴: 불꽃 튀기 */
export function sparks(at: THREE.Vector3, now: number): Effect {
  const n = 40;
  const pos = new Float32Array(n * 3);
  const vel: THREE.Vector3[] = [];
  for (let i = 0; i < n; i++) {
    pos.set([at.x, at.y, at.z], i * 3);
    vel.push(new THREE.Vector3((Math.random() - 0.5) * 2.4, Math.random() * 2.2 + 0.6, (Math.random() - 0.5) * 2.4));
  }
  const geo = new THREE.BufferGeometry();
  geo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
  const m = new THREE.PointsMaterial({ color: 0xffd166, size: 0.06, transparent: true, depthWrite: false, blending: THREE.AdditiveBlending });
  const points = new THREE.Points(geo, m);
  let last = now;
  const start = now;
  return {
    object: points,
    update(t) {
      const dt = Math.min(0.05, t - last);
      last = t;
      const k = (t - start) / 0.8;
      if (k >= 1) {
        geo.dispose();
        m.dispose();
        return false;
      }
      for (let i = 0; i < n; i++) {
        vel[i].y -= 6 * dt;
        pos[i * 3] += vel[i].x * dt;
        pos[i * 3 + 1] += vel[i].y * dt;
        pos[i * 3 + 2] += vel[i].z * dt;
      }
      geo.attributes.position.needsUpdate = true;
      m.opacity = 1 - k;
      return true;
    },
  };
}

/** 기절: 머리 위에서 도는 별 */
export function dizzyStars(head: THREE.Object3D, now: number, durSec: number): Effect {
  const g = new THREE.Group();
  for (let i = 0; i < 3; i++) {
    const s = glowSprite(0xffe066, 0.12);
    s.userData.k = (i / 3) * Math.PI * 2;
    g.add(s);
  }
  g.position.y = 0.26;
  head.add(g);
  const end = now + durSec;
  return {
    object: g,
    update(t) {
      if (t >= end) {
        g.removeFromParent();
        return false;
      }
      g.children.forEach((c) => {
        const a = c.userData.k + t * 4;
        c.position.set(Math.cos(a) * 0.18, Math.sin(t * 6 + c.userData.k) * 0.03, Math.sin(a) * 0.18);
      });
      return true;
    },
  };
}

/** 전파 탐지기: 감독관 발밑에서 퍼지는 고리 */
export function detectorPulse(x: number, z: number, now: number, hit: boolean): Effect {
  const m = new THREE.MeshBasicMaterial({ color: hit ? 0xff4d6d : 0x63b3ed, transparent: true, side: THREE.DoubleSide, depthWrite: false });
  const ring = new THREE.Mesh(new THREE.RingGeometry(0.9, 1.0, 48), m);
  ring.rotation.x = -Math.PI / 2;
  ring.position.set(x, 0.02, z);
  const start = now;
  return {
    object: ring,
    update(t) {
      const k = (t - start) / 0.9;
      if (k >= 1) {
        ring.geometry.dispose();
        m.dispose();
        return false;
      }
      ring.scale.setScalar(0.3 + k * 6);
      m.opacity = 0.8 * (1 - k);
      return true;
    },
  };
}

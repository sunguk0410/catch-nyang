import * as THREE from 'three';

/**
 * 이미지 파일 없이 캔버스로 그린 텍스처들 (밝은 교실 톤).
 * 나중에 실제 이미지·모델로 바꿔도 이 함수 이름만 맞추면 됨.
 */

const cache = new Map<string, THREE.Texture>();

function canvasTexture(key: string, w: number, hgt: number, draw: (g: CanvasRenderingContext2D) => void,
                       repeat?: [number, number]): THREE.Texture {
  const hit = cache.get(key);
  if (hit) return hit;
  const c = document.createElement('canvas');
  c.width = w;
  c.height = hgt;
  draw(c.getContext('2d')!);
  const t = new THREE.CanvasTexture(c);
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  if (repeat) {
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(repeat[0], repeat[1]);
  }
  cache.set(key, t);
  return t;
}

/** 밝은 회색·흰 바둑판 바닥 타일 */
export function floorTiles(repeatX: number, repeatY: number): THREE.Texture {
  return canvasTexture(`floor-${repeatX}-${repeatY}`, 256, 256, (g) => {
    const s = 128;
    for (let y = 0; y < 2; y++) {
      for (let x = 0; x < 2; x++) {
        g.fillStyle = (x + y) % 2 === 0 ? '#e8edf0' : '#cfd8de';
        g.fillRect(x * s, y * s, s, s);
      }
    }
    g.strokeStyle = 'rgba(120,135,145,0.35)';
    g.lineWidth = 2;
    for (let i = 0; i <= 2; i++) {
      g.beginPath(); g.moveTo(i * s, 0); g.lineTo(i * s, 256); g.stroke();
      g.beginPath(); g.moveTo(0, i * s); g.lineTo(256, i * s); g.stroke();
    }
  }, [repeatX, repeatY]);
}

/** 시험지: 흰 종이에 문항 줄 */
export function examPaper(): THREE.Texture {
  return canvasTexture('paper', 256, 340, (g) => {
    g.fillStyle = '#fdfdfb';
    g.fillRect(0, 0, 256, 340);
    g.fillStyle = '#1f2937';
    g.fillRect(24, 18, 120, 10);
    g.fillStyle = '#9ca3af';
    for (let y = 50; y < 330; y += 14) {
      g.fillRect(24, y, 150 + ((y * 37) % 50), 3);
    }
    g.strokeStyle = '#374151';
    g.lineWidth = 2;
    for (let y = 50; y < 330; y += 42) {
      g.beginPath();
      g.arc(214, y + 4, 8, 0, Math.PI * 2);
      g.stroke();
    }
  });
}

/** OMR 카드: 분홍 바탕에 동그라미 칸 */
export function omrCard(): THREE.Texture {
  return canvasTexture('omr', 160, 300, (g) => {
    g.fillStyle = '#ffe4ea';
    g.fillRect(0, 0, 160, 300);
    g.fillStyle = '#e11d48';
    g.fillRect(0, 0, 160, 22);
    g.strokeStyle = '#f43f5e';
    g.lineWidth = 1.5;
    for (let row = 0; row < 20; row++) {
      for (let col = 0; col < 2; col++) {
        g.beginPath();
        g.ellipse(60 + col * 36, 36 + row * 13, 9, 4.5, 0, 0, Math.PI * 2);
        g.stroke();
      }
    }
  });
}

/** 코르크 게시판 + 색종이 공지 */
export function corkBoard(): THREE.Texture {
  return canvasTexture('cork', 512, 256, (g) => {
    g.fillStyle = '#c8a273';
    g.fillRect(0, 0, 512, 256);
    for (let i = 0; i < 900; i++) {
      g.fillStyle = `rgba(120,80,40,${Math.random() * 0.25})`;
      g.fillRect(Math.random() * 512, Math.random() * 256, 2, 2);
    }
    const notes = ['#ffffff', '#fff3b0', '#bde0fe', '#ffc8dd', '#caffbf', '#ffffff'];
    notes.forEach((c, i) => {
      const x = 24 + i * 80 + (i % 2) * 6;
      const y = 30 + (i % 3) * 26;
      g.save();
      g.translate(x, y);
      g.rotate(((i % 3) - 1) * 0.05);
      g.fillStyle = c;
      g.fillRect(0, 0, 66, 90);
      g.fillStyle = 'rgba(55,65,81,0.6)';
      for (let l = 0; l < 6; l++) g.fillRect(8, 14 + l * 11, 40 + ((l * 13) % 12), 3);
      g.fillStyle = '#ef4444';
      g.beginPath();
      g.arc(33, 6, 4, 0, Math.PI * 2);
      g.fill();
      g.restore();
    });
  });
}

/** 파리채 그물: 초록 격자 (투명 배경) */
export function swatterMesh(): THREE.Texture {
  return canvasTexture('swatter', 128, 128, (g) => {
    g.clearRect(0, 0, 128, 128);
    g.fillStyle = 'rgba(74, 222, 128, 0.35)';
    g.fillRect(0, 0, 128, 128);
    g.strokeStyle = '#22c55e';
    g.lineWidth = 3;
    for (let i = 0; i <= 128; i += 12) {
      g.beginPath(); g.moveTo(i, 0); g.lineTo(i, 128); g.stroke();
      g.beginPath(); g.moveTo(0, i); g.lineTo(128, i); g.stroke();
    }
    g.strokeStyle = '#15803d';
    g.lineWidth = 8;
    g.strokeRect(2, 2, 124, 124);
  });
}

/** 칠판 분필 글씨 */
export function chalkboard(text: string): THREE.Texture {
  return canvasTexture(`chalk-${text}`, 1024, 256, (g) => {
    g.fillStyle = '#2f5d50';
    g.fillRect(0, 0, 1024, 256);
    for (let i = 0; i < 400; i++) {
      g.fillStyle = `rgba(255,255,255,${Math.random() * 0.05})`;
      g.fillRect(Math.random() * 1024, Math.random() * 256, 30, 2);
    }
    g.fillStyle = 'rgba(255,255,255,0.92)';
    g.font = 'bold 64px "Malgun Gothic", "Apple SD Gothic Neo", system-ui, sans-serif';
    g.textAlign = 'center';
    g.textBaseline = 'middle';
    g.fillText(text, 512, 110);
    g.font = '40px "Malgun Gothic", "Apple SD Gothic Neo", system-ui, sans-serif';
    g.fillStyle = 'rgba(255,255,255,0.7)';
    g.fillText('시험지 → OMR 옮겨 적기 · 잡담 금지', 512, 190);
  });
}

# web — 프론트엔드 (TypeScript · Vite · Three.js)

프레임워크 없이 순수 TS. 3D 는 Three.js, 그 위 UI 는 DOM.

- **싱글**(AI 상대): 서버 없이 브라우저 안에서 돌아감 — 게임 규칙·AI 는 `../rules` (Java) 를 JS 로 바꾼 `src/gen/rules/rules.js`
- **멀티**: 서버 웹소켓

## 실행

```bash
# 서버 먼저 (저장소 루트에서)
docker compose up -d mysql
./gradlew bootRun

# 프론트 (web/ 에서)
npm install
npm run dev          # http://localhost:5173  (/ws 는 Vite 가 서버 8080 으로 넘김)
```

브라우저 창 두 개(하나는 시크릿 창)로 열어서 방 만들기 → 코드로 입장하면 혼자서도 한 판을 돌려볼 수 있다.

| 명령 | 하는 일 |
|---|---|
| `npm run gen` | proto → TS + `../rules` → `rules.js` (dev·build·test 전에 자동, Java 21 필요) |
| `npm test` | 상태 저장소·충돌·싱글 모드·**JVM≡JS 규칙 일치** 테스트 |
| `npm run e2e` | 실제 서버에 붙어 한 판 + 재대결 (서버 켜 둘 것) |
| `npm run shot` | 브라우저 두 개로 멀티 한 판 돌리며 `screenshots/` 에 화면 저장 (서버·dev 켜 둘 것) |
| `node scripts/screenshot-single.mjs` | 서버를 막아 둔 채 싱글 모드 두 역할을 돌리며 화면 저장 (dev 만) |
| `npm run build` | 타입 검사 + 배포용 빌드 (`dist/`) |

## 구조

```
src/
├─ net/connection.ts     # 웹소켓 + protobuf, 모든 메시지에 seq (멀티)
├─ local/local-server.ts # 싱글: 브라우저 안 가짜 서버 (rules.js + AI). 서버와 같은 메시지를 만듦
├─ state/game-store.ts   # 클라 게임 상태. Snapshot = 통째 교체, GameTick = 바뀐 것만
├─ scene/                # 3D
│  ├─ game-scene.ts      #   렌더러·역할별 카메라·조작·사건 → 연출
│  ├─ avatars.ts         #   학생(교복·단발, 가짜 단서 동작)·감독관·1인칭 파리채
│  ├─ classroom.ts       #   교실 (타일 바닥·창문·칠판·게시판·사물함·조명)
│  ├─ effects.ts         #   도구 빛·불꽃·기절 별·탐지기 고리
│  ├─ collision.ts       #   책상 블록 충돌 (서버 Match.pushOutOfDesks 와 같은 계산)
│  └─ textures.ts        #   캔버스로 그린 텍스처 (타일·시험지·OMR·게시판·그물)
├─ ui/                   # 로비 · HUD · 시험지/OMR 패널
└─ app.ts                # 서버 메시지 → 상태 → 화면, 끊기면 토큰으로 자동 재접속
```

## 서버와 맞춰야 하는 것

- 메시지: `../protocol/catchnyang/v1/game.proto` (생성 코드는 커밋하지 않음)
- 규칙 수치: `../protocol/game-config.json` 을 직접 import (복사 금지)
- 감독관 이동 예측은 서버와 같은 속도·벽·책상 충돌로 계산 → 서버 이동 규칙을 바꾸면 `scene/collision.ts` 도 같이

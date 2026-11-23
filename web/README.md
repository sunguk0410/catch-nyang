# web — 프론트엔드 (TypeScript · Vite · Three.js)

아직 비어 있음. 프론트 프로젝트는 여기에 만든다.

## 서버와 맞춰야 하는 것

- 메시지 정의: `../protocol/catchnyang/v1/game.proto` → 여기서 TS 코드 생성
  (추천: `@bufbuild/protobuf` + `buf generate`. 생성된 코드는 커밋하지 말고 빌드할 때 만들 것)
- 규칙 수치: `../protocol/game-config.json` (서버와 같은 파일을 읽을 것 — 복사하지 말 것)
- 흐름·규칙: 노션 「백엔드 → 프론트 전달사항」

proto 를 바꿔야 하면 `../protocol` 을 고치고, 같은 PR 에서 서버(`../server`)도 같이 맞춘다.

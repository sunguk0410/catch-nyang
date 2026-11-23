# 🐱 냥이를 잡아라

시험 끝나고 시험지 → OMR 옮겨 적는 시간, 학생 몸에 빙의한 "리트냥이"가 AI 도구로 정답을 알아내 반 평균을 올리고,
감독관은 이상한 마킹을 하는 학생을 찾아 적발하는 1:1 비대칭 게임.

규칙·기술 스택 원문은 노션 「냥이를 잡아라」 페이지 참고.

## 구조

```
catch-nyang/                      # Java 21 · Spring Boot · WebSocket(바이너리) · JPA · Flyway
├─ docker-compose.yml             # 로컬 MySQL 8
└─ src/main/
   ├─ java/com/catchnyang/
   │  ├─ game/      # 규칙 엔진 (Match). 스레드·네트워크 모름, tick(dt) 로만 시간이 흐름
   │  ├─ room/      # 방 = 판 + 플레이어 둘. 20Hz 루프, 역할별 필터(EventFilter·RoleSnapshot), 통계
   │  ├─ ws/        # 웹소켓 ↔ proto, 로비(FIFO 매칭 스레드·방 코드·재접속), 방 → 클라 송신
   │  └─ history/   # 판 결과 비동기 DB 저장 (game_match / match_player)
   ├─ proto/game.proto            # 메시지 정의 (Java·TS 공용)
   └─ resources/
      ├─ application.yml
      ├─ game-config.json         # 점수·쿨타임·도구 수치 (서버·싱글 공용)
      └─ db/migration/            # Flyway
```

프론트(TypeScript · Vite · Three.js)는 별도 담당. `src/main/proto/game.proto` 와 `src/main/resources/game-config.json` 은 프론트와 공유하는 계약 파일이라 바꿀 때 같이 알려야 함.

## 로컬 실행

```bash
# 1) DB
docker compose up -d mysql

# 2) 서버 (http://localhost:8080, WebSocket /ws)
./gradlew bootRun

# 테스트 (로컬 MySQL 이 떠 있어야 함 — 통합 테스트가 실제 DB 에 썼다가 지움)
./gradlew test
```

| 환경 변수 | 기본값 | 설명 |
|---|---|---|
| `GAME_RECONNECT_GRACE_SEC` | 30 | 끊긴 뒤 이 안에 `Reconnect` 안 하면 기권 |
| `GAME_ALLOWED_ORIGINS` | `*` | 웹소켓 허용 Origin (쉼표 구분). **배포 시 프론트 도메인으로** |
| `GAME_CONFIG_PATH` | `classpath:game-config.json` | 규칙 수치 파일 |

## 프로토콜 흐름 (`game.proto`)

모든 프레임은 바이너리 `ClientMessage` / `ServerMessage`.

1. **로비**
   - 랜덤: `JoinQueue{nickname}` → `QueueJoined` → (상대 오면) `MatchFound`
   - 친구: `CreateRoom` → `RoomCreated{room_code}` / 친구가 `JoinRoom{room_code}` → 둘 다 `MatchFound`
   - `CancelQueue` 로 대기열·만든 방 코드 취소 → `QueueCancelled`
   - 역할은 랜덤. 닉네임 1~20자
2. **판 시작**: `MatchFound{my_role, session_token, …}` 바로 뒤에 `Snapshot` (내 역할에 보이는 전체 상태)
3. **게임 중**
   - 클라 → `Possess / UseTool / Remark` (냥이), `ProctorMove{dx,dz,face_x,face_z} / Swat / Detect` (감독관), `LeaveMatch`
   - 서버 → `GameTick` (변경분이 있는 tick 마다, 없어도 1초에 한 번): 시간, `ack_seq`, 사건, 거절(`seq` + 이유), 감독관 위치·시선, 냥이 실시간 평균, 새로 보게 된 학생의 시험지·OMR
   - 모든 입력에 `seq` 를 1부터 올려 붙이면 처리 뒤 `ack_seq` 로 돌아옴 (예측 보정용)
4. **끊김**: 상대에게 `OpponentConnection{connected=false, grace_ms}`. 유예 안에 새 연결로 `Reconnect{session_token}` → `Reconnected{새 토큰}` + `Snapshot`. 토큰은 일회용
5. **종료**: `GameEvent.match_ended` → 둘 다 로비 상태로 돌아감 (다시 `JoinQueue` 가능). 결과는 DB 에 비동기 저장

정보 차단은 서버에서 함: 감독관은 냥이 자리·확인한 정답·도구를 절대 못 받고, OMR 내용은 검사 중인 학생 것만 받음. 도구 이펙트(`ToolStarted`)는 감독관 시야각(`proctor.fovDeg`)·거리 안일 때만 감.

## 진행 상황

- [x] 매칭: FIFO 대기열 + 매칭 스레드, 방 코드 생성/입장, 재접속(일회용 토큰·유예 시간)
- [x] 방 단위 게임 스레드 + 20Hz tick (입력 수집 → 규칙 적용 → 델타 전송)
- [x] 게임 상태 모델 (학생 9명, 시험지/OMR 20문항, 평균·목표 점수)
- [x] 냥이 조작: 빙의 이동, 도구 사용, OMR 수정
- [x] 감독관 조작: 1인칭 이동·시선, 검사, 파리채, 전파 탐지기 (도구 이펙트 시야각 판정)
- [x] 판 종료 시 `game_match` / `match_player` 비동기 저장 (역할별 통계 JSON)
- [ ] 역할 바꿔 재대결 (`game_match.rematch_of` 컬럼만 있음)

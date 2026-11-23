# 🐱 냥이를 잡아라

시험 끝나고 시험지 → OMR 옮겨 적는 시간, 학생 몸에 빙의한 "리트냥이"가 AI 도구로 정답을 알아내 반 평균을 올리고,
감독관은 이상한 마킹을 하는 학생을 찾아 적발하는 1:1 비대칭 게임.

규칙·기술 스택 원문은 노션 「냥이를 잡아라」 페이지 참고.

## 구조

프론트·서버가 한 저장소에 있는 모노레포. 둘이 같이 쓰는 계약 파일은 `protocol/` 에만 있다.

```
catch-nyang/
├─ protocol/                      # ★ 프론트·서버 공용 계약 (복사하지 말고 여기 것을 읽을 것)
│  ├─ catchnyang/v1/game.proto    # 메시지 정의 → Java·TS 코드 생성
│  └─ game-config.json            # 점수·쿨타임·도구 수치
├─ server/                        # Java 21 · Spring Boot · WebSocket(바이너리) · JPA · Flyway (Gradle 하위 프로젝트)
│  └─ src/main/java/com/catchnyang/
│     ├─ game/      # 규칙 엔진 (Match). 스레드·네트워크 모름, tick(dt) 로만 시간이 흐름
│     ├─ room/      # 방 = 판 + 플레이어 둘. 20Hz 루프, 역할별 필터(EventFilter·RoleSnapshot), 통계
│     ├─ ws/        # 웹소켓 ↔ proto, 로비(매칭·방 코드·재접속·재대결), 송신·하트비트
│     └─ history/   # 판 결과 비동기 DB 저장 (game_match / match_player)
├─ web/                           # 프론트 (TypeScript · Vite · Three.js) — 아직 비어 있음
├─ settings.gradle · gradlew      # Gradle 진입점은 루트 (server 를 포함). IntelliJ 는 루트를 열면 됨
├─ buf.yaml                       # proto 문법 검사 · 깨지는 변경 감지
└─ docker-compose.yml             # 로컬 MySQL 8
```

## 프로토콜 바꿀 때

1. `protocol/catchnyang/v1/game.proto` 수정
2. 검사 (Node 만 있으면 됨):
   ```bash
   npx @bufbuild/buf@1.75.0 lint
   npx @bufbuild/buf@1.75.0 breaking --against '.git#branch=main'
   ```
3. **같은 PR 에서** 서버(`server/`)와 프론트(`web/`)를 같이 맞춤

규칙: 필드 번호는 절대 재사용하지 않음. 필드를 지울 땐 `reserved` 로 번호를 막아 둠. enum 값은 enum 이름을 접두사로 (`ROLE_CAT`).
서버는 도메인 enum 과 proto enum 을 이름으로 맞추므로(`CAT` ↔ `ROLE_CAT`) 한쪽에만 값을 추가하면 `ProtoMapperTest` 가 실패한다.

## 로컬 실행

```bash
# 전부 저장소 루트에서
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
| `GAME_REMATCH_WINDOW_SEC` | 30 | 판이 끝난 뒤 재대결을 신청·수락할 수 있는 시간 |
| `GAME_WS_PING_INTERVAL_SEC` | 5 | 클라에서 이만큼 아무것도 안 오면 프로토콜 ping (브라우저가 자동 pong) |
| `GAME_WS_IDLE_TIMEOUT_SEC` | 15 | 이만큼 아무것도 안 오면 죽은 연결로 보고 끊음 → 재접속 유예 시작 |
| `GAME_ALLOWED_ORIGINS` | `*` | 웹소켓 허용 Origin (쉼표 구분). **배포 시 프론트 도메인으로** |
| `GAME_CONFIG_PATH` | `classpath:game-config.json` | 규칙 수치 파일 |

## 프로토콜 흐름 (`protocol/catchnyang/v1/game.proto`)

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
6. **재대결** (둘 다 연결된 채 끝난 판만): 종료 직후 `RematchUpdate{AVAILABLE, expires_ms}`
   - 한쪽이 `RequestRematch` → 본인 `WAITING`, 상대 `OFFERED` → 상대도 `RequestRematch` 하면 역할을 바꿔 `MatchFound{rematch=true}`
   - `DeclineRematch`·대기열/방 코드로 이동·끊김 → 상대 `DECLINED`, 시간 초과 → 둘 다 `EXPIRED`
   - DB 에는 새 판의 `game_match.rematch_of` = 이전 판 id

정보 차단은 서버에서 함: 감독관은 냥이 자리·확인한 정답·도구를 절대 못 받고, OMR 내용은 검사 중인 학생 것만 받음. 도구 이펙트(`ToolStarted`)는 감독관 시야각(`proctor.fovDeg`)·거리 안일 때만 감.

## 네트워크·성능

- **송신은 절대 안 막힘**: 연결마다 송신 큐 + Tomcat 비동기 송신(`RemoteEndpoint.Async`). 보통은 방 tick 스레드가 NIO 소켓에 바로 non-blocking 으로 쓰고, 소켓 버퍼가 차면 Tomcat 콜백이 이어서 보냄. 느린 클라가 있어도 방 tick·로비 락은 안 막힘 (`ws/Connection.java`)
- **느린 클라**: 못 보낸 게 256KB 를 넘으면 그 연결만 끊음 → 재접속 유예 → 돌아오면 Snapshot 으로 따라잡음
- **죽은 연결 감지**: 5초 조용하면 ping, 15초 조용하면 끊음 (반쯤 끊긴 TCP·탭 멈춤). 프론트 작업 불필요
- **압축(permessage-deflate) 끔**: 40B 짜리 프레임엔 CPU·메모리만 듦. Tomcat 은 빈 확장 목록을 "기본값 전부" 로 해석해서, 요청 헤더를 필터에서 지움
- **예열**: 서버 시작 시 가짜 판을 돌려 JIT·클래스 로딩을 끝냄 (안 하면 첫 판이 약 0.3초 밀림)
- 메트릭 (`/actuator/prometheus`): `game.tick.duration`, `game.tick.overrun`, `ws.send.delay`, `ws.rtt`, `ws.connections`, `ws.queued.bytes`, `ws.terminated{reason}`

### 부하 측정

```bash
./gradlew loadTest -Dload.rooms=1000 -Dload.seconds=15            # 기본 200방
./gradlew loadTest -Dload.rooms=1000 -Dload.jfr=build/load.jfr     # JFR 녹화 같이
```

개발 PC (12코어) 결과. 봇 클라도 같은 JVM 에서 돌아서 서버 단독보다 보수적인 수치. 감독관은 계속 움직이고(매 tick 갱신), 냥이는 3초마다 빙의.

| 방 / 연결 | CPU | tick 건너뜀 | 서버 송신 큐 대기 p99 | tick 수신 간격 p99 | 입력→ack p99 | 연결당 하향 |
|---|---|---|---|---|---|---|
| 1000 / 2000 | 3.4 코어 | 0 / 30만 | 0.00 ms | 73 ms | 63 ms | 0.8 KB/s |
| 2000 / 4000 | 6.9 코어 | 0.65% | 0.01 ms | 88 ms | 72 ms | 0.8 KB/s |

## 진행 상황

- [x] 매칭: FIFO 대기열 + 매칭 스레드, 방 코드 생성/입장, 재접속(일회용 토큰·유예 시간)
- [x] 방 단위 게임 스레드 + 20Hz tick (입력 수집 → 규칙 적용 → 델타 전송)
- [x] 게임 상태 모델 (학생 9명, 시험지/OMR 20문항, 평균·목표 점수)
- [x] 냥이 조작: 빙의 이동, 도구 사용, OMR 수정
- [x] 감독관 조작: 1인칭 이동·시선, 검사, 파리채, 전파 탐지기 (도구 이펙트 시야각 판정)
- [x] 판 종료 시 `game_match` / `match_player` 비동기 저장 (역할별 통계 JSON)
- [x] 역할 바꿔 재대결 (`rematch_of` 로 이전 판과 연결)

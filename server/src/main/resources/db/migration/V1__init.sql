CREATE TABLE game_match (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  match_type    VARCHAR(10)  NOT NULL,  -- RANDOM / ROOM_CODE
  room_code     CHAR(6)      NULL,      -- 방 코드 매치일 때만
  rematch_of    BIGINT       NULL,      -- 역할 바꿔 재대결 시 이전 판
  started_at    DATETIME(3)  NOT NULL,
  ended_at      DATETIME(3)  NOT NULL,
  winner_role   VARCHAR(10)  NOT NULL,  -- CAT / PROCTOR
  end_reason    VARCHAR(20)  NOT NULL,  -- AVG_REACHED / AVG_FAILED / CATCH_3 / DISCONNECT
  final_avg     DECIMAL(4,1) NOT NULL,
  final_target  SMALLINT     NOT NULL,
  rule_version  VARCHAR(16)  NOT NULL,  -- game-config 버전
  INDEX idx_started (started_at),
  FOREIGN KEY (rematch_of) REFERENCES game_match(id)
);

CREATE TABLE match_player (
  match_id   BIGINT      NOT NULL,
  role       VARCHAR(10) NOT NULL,
  nickname   VARCHAR(20) NOT NULL,
  stats      JSON        NOT NULL,      -- 역할별 통계
  PRIMARY KEY (match_id, role),
  FOREIGN KEY (match_id) REFERENCES game_match(id)
);

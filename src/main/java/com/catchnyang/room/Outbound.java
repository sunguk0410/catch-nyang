package com.catchnyang.room;

import com.catchnyang.game.ActionResult;
import com.catchnyang.game.GameEvent;
import com.catchnyang.game.Phase;
import com.catchnyang.game.Vec2;

/**
 * 방이 한 플레이어에게 보내는 것 (이미 역할별로 걸러진 것만 들어감).
 * 네트워크 계층이 한 tick 묶음을 proto 메시지로 바꿔 보낸다 ({@code ws.WsPlayerChannel}).
 */
public sealed interface Outbound {

    /** 묶음 맨 앞. 시간 동기화 + 내 입력 처리 확인 (클라 예측 보정용) */
    record Clock(Phase phase, long matchTimeMs, long remainingMs, int ackSeq) implements Outbound {}

    /** 이미 역할별로 걸러진 사건 */
    record Event(GameEvent event) implements Outbound {}

    /** 거절된 입력 (보낸 사람에게만) */
    record Rejected(int seq, GameCommand command, ActionResult reason) implements Outbound {}

    /** 감독관 위치·시선이 바뀜 (둘 다 받음) */
    record ProctorState(Vec2 position, Vec2 facing) implements Outbound {}

    /** 실시간 반 평균 (냥이만) */
    record LiveAverage(double average) implements Outbound {}

    /** 학생 한 명의 시험지·OMR 전체. 냥이: 빙의할 때, 감독관: 검사 시작할 때 */
    record StudentDetail(StudentInfo info) implements Outbound {}

    /** 역할별 전체 상태 (매칭 직후·재접속). 받으면 클라 상태를 통째로 갈아끼움 */
    record Snapshot(RoleSnapshot snapshot) implements Outbound {}
}

package com.catchnyang.ai;

import com.catchnyang.room.GameCommand;

import java.util.List;

/** AI 플레이어 하나. 매 tick 자기 역할 화면(BotView)을 보고 보낼 명령을 정한다 */
public interface Bot {

    /** 이번 tick 에 보낼 명령 (없으면 빈 목록) */
    List<GameCommand> decide(BotView view);
}

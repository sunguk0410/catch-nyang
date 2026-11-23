package com.catchnyang.ws;

import com.catchnyang.game.config.GameConfig;
import com.catchnyang.proto.ClientMessage;
import com.catchnyang.proto.ProctorMove;
import com.catchnyang.proto.ServerMessage;
import com.catchnyang.room.PlayerChannel;
import com.catchnyang.room.RoomWarmup;
import com.google.protobuf.InvalidProtocolBufferException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 서버 시작 시 가짜 판을 돌려서 클래스 로딩·protobuf 초기화·JIT 를 미리 끝낸다.
 * <p>
 * 안 하면 서버 켜고 첫 판의 첫 tick 이 수백 ms 걸리고, 루프는 밀린 tick 을 건너뛰므로
 * 첫 판만 게임 시간이 실제보다 뒤처진다.
 */
@Component
class Warmup implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Warmup.class);

    private final GameConfig cfg;

    Warmup(GameConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void run(ApplicationArguments args) {
        long t0 = System.nanoTime();
        long bytes = run(cfg);
        log.info("warm-up done in {} ms ({} bytes serialized)", (System.nanoTime() - t0) / 1_000_000, bytes);
    }

    /** 방 스레드 일(tick → proto 직렬화) + 네트워크 스레드 일(파싱 → 명령)을 그대로 흉내 */
    static long run(GameConfig cfg) {
        long[] bytes = {0};
        PlayerChannel serialize = batch -> {
            for (ServerMessage m : ProtoMapper.toMessages(batch)) bytes[0] += m.toByteArray().length;
        };
        RoomWarmup.run(cfg, serialize, serialize);

        byte[] in = ClientMessage.newBuilder().setSeq(1)
                .setProctorMove(ProctorMove.newBuilder().setDx(1).setFaceZ(-1)).build().toByteArray();
        try {
            for (int i = 0; i < 10_000; i++) bytes[0] += ProtoMapper.toCommands(ClientMessage.parseFrom(in)).size();
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
        return bytes[0];
    }
}

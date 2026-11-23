package com.catchnyang.ws;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class MatchmakerTest {

    final Set<String> cancelled = ConcurrentHashMap.newKeySet();
    final LinkedBlockingQueue<List<String>> pairs = new LinkedBlockingQueue<>();
    final Matchmaker<String> mm = new Matchmaker<>((a, b) -> {
        boolean va = !cancelled.contains(a);
        boolean vb = !cancelled.contains(b);
        if (va && vb) {
            pairs.add(List.of(a, b));
            return List.of();
        }
        return va ? List.of(a) : vb ? List.of(b) : List.of();
    });

    @AfterEach
    void tearDown() {
        mm.close();
    }

    @Test
    @DisplayName("먼저 온 순서대로 둘씩 짝지음")
    void fifo() throws Exception {
        mm.start();
        for (String p : List.of("a", "b", "c", "d")) mm.enqueue(p);
        assertThat(pairs.poll(1, TimeUnit.SECONDS)).containsExactly("a", "b");
        assertThat(pairs.poll(1, TimeUnit.SECONDS)).containsExactly("c", "d");
    }

    @Test
    @DisplayName("대기열에서 취소한 사람은 건너뜀")
    void removeFromQueue() throws Exception {
        for (String p : List.of("a", "b", "c")) mm.enqueue(p);
        assertThat(mm.remove("b")).isTrue();
        mm.start();
        assertThat(pairs.poll(1, TimeUnit.SECONDS)).containsExactly("a", "c");
    }

    @Test
    @DisplayName("매칭 스레드가 꺼낸 뒤 취소된 표: 남은 사람은 맨 앞으로 돌아가 순서 유지")
    void cancelledAfterTake() throws Exception {
        mm.start();
        mm.enqueue("a");
        Thread.sleep(100);           // 스레드가 a 를 꺼내 b 를 기다리는 중
        cancelled.add("a");
        assertThat(mm.remove("a")).isFalse();  // 이미 꺼내짐
        mm.enqueue("b");             // (a 무효, b 유효) → b 를 앞으로 되돌림
        Thread.sleep(100);
        mm.enqueue("c");
        assertThat(pairs.poll(1, TimeUnit.SECONDS)).containsExactly("b", "c");
    }
}

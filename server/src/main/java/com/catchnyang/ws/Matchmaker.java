package com.catchnyang.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * FIFO 대기열 + 매칭 전용 스레드. 먼저 온 둘을 짝지어 {@link Pairer} 에 넘긴다.
 * <p>
 * 취소는 대기열에서 빼기만 하고, 스레드가 이미 꺼낸 표는 {@link Pairer} 가 유효성을 다시 확인한다
 * (꺼낸 뒤 취소·끊김 경쟁). Pairer 가 돌려준 표는 대기열 맨 앞으로 되돌려 순서를 지킨다.
 */
final class Matchmaker<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Matchmaker.class);

    @FunctionalInterface
    interface Pairer<T> {
        /** a 가 먼저 온 표. 짝을 못 지었으면 아직 유효한 표를 돌려줌 (대기열 앞으로 되돌림) */
        List<T> pair(T a, T b);
    }

    private final LinkedBlockingDeque<T> queue = new LinkedBlockingDeque<>();
    private final Pairer<T> pairer;
    private final Thread thread;
    private volatile boolean running = true;

    Matchmaker(Pairer<T> pairer) {
        this.pairer = pairer;
        this.thread = Thread.ofPlatform().name("matchmaker").daemon().unstarted(this::run);
    }

    void start() {
        thread.start();
    }

    void enqueue(T ticket) {
        queue.addLast(ticket);
    }

    /** 대기열에 있으면 뺌. 이미 매칭 스레드가 꺼냈으면 false (Pairer 가 무효 처리) */
    boolean remove(T ticket) {
        return queue.remove(ticket);
    }

    int size() {
        return queue.size();
    }

    private void run() {
        while (running) {
            try {
                T a = queue.takeFirst();
                T b = queue.takeFirst();
                List<T> back;
                try {
                    back = pairer.pair(a, b);
                } catch (RuntimeException e) {
                    log.error("pairing failed", e);
                    continue;
                }
                // 뒤에 온 것부터 앞으로 넣어야 원래 순서가 유지됨
                for (int i = back.size() - 1; i >= 0; i--) queue.addFirst(back.get(i));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}

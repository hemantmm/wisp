package com.tcpchat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

final class MessageGuard {
    static final int MAX_TEXT = 2000;
    static final int MAX_FRAME = 8192;
    static final int HISTORY_LIMIT = 200;
    private static final int RATE_COUNT = 8;
    private static final long RATE_WINDOW_MS = 5_000;

    private final ConcurrentHashMap<String, Deque<Long>> sends = new ConcurrentHashMap<>();

    boolean tooLargeFrame(String raw) {
        return raw != null && raw.length() > MAX_FRAME;
    }

    boolean tooLong(String text) {
        return text.length() > MAX_TEXT;
    }

    boolean tooFast(String username) {
        long now = System.currentTimeMillis();
        String key = Username.key(username);
        Deque<Long> times = sends.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > RATE_WINDOW_MS) {
                times.removeFirst();
            }
            if (times.size() >= RATE_COUNT) {
                return true;
            }
            times.addLast(now);
            return false;
        }
    }
}

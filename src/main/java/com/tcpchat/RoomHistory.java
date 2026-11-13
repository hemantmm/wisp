package com.tcpchat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RoomHistory {
    private final Deque<Map<String, Object>> messages = new ArrayDeque<>();

    synchronized void add(Map<String, Object> message) {
        Object text = message.get("text");
        if (text instanceof String value && value.length() > MessageGuard.MAX_TEXT) {
            return;
        }
        messages.addLast(new LinkedHashMap<>(message));
        while (messages.size() > MessageGuard.HISTORY_LIMIT) {
            messages.removeFirst();
        }
    }

    synchronized List<Map<String, Object>> forUser(String username) {
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> message : messages) {
            if ("pm".equals(message.get("type"))) {
                if (Username.same(username, stringify(message.get("from")))
                        || Username.same(username, stringify(message.get("to")))) {
                    visible.add(message);
                }
            } else {
                visible.add(message);
            }
        }
        return visible;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

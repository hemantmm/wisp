package com.tcpchat;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RoomHistory {
    private final LinkedHashMap<String, Map<String, Object>> messages = new LinkedHashMap<>();

    synchronized void clear() {
        messages.clear();
    }

    synchronized void add(Map<String, Object> message) {
        Object text = message.get("text");
        if (text instanceof String value && value.length() > MessageGuard.MAX_TEXT) {
            return;
        }
        Object id = message.get("id");
        if (!(id instanceof String messageId) || messageId.isBlank()) {
            return;
        }
        messages.put(messageId, copy(message));
        while (messages.size() > MessageGuard.HISTORY_LIMIT) {
            Iterator<String> keys = messages.keySet().iterator();
            if (!keys.hasNext()) {
                break;
            }
            keys.next();
            keys.remove();
        }
    }

    synchronized Map<String, Object> replySnapshot(String id, String viewer) {
        Map<String, Object> message = messages.get(id);
        if (message == null || !visible(message, viewer) || Boolean.TRUE.equals(message.get("deleted"))) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", id);
        snapshot.put("from", message.get("from"));
        String text = stringify(message.get("text"));
        if (text.length() > 140) {
            text = text.substring(0, 140);
        }
        snapshot.put("text", text);
        return snapshot;
    }

    synchronized Map<String, Object> edit(String id, String username, String text, Map<String, Object> preview) {
        Map<String, Object> message = owned(id, username);
        if (message == null) {
            return null;
        }
        message.put("text", text);
        message.put("edited", true);
        if (preview == null) {
            message.remove("preview");
        } else {
            message.put("preview", preview);
        }
        return copy(message);
    }

    synchronized Map<String, Object> delete(String id, String username) {
        Map<String, Object> message = owned(id, username);
        if (message == null) {
            return null;
        }
        message.put("deleted", true);
        message.put("text", "");
        message.remove("preview");
        message.remove("reactions");
        return copy(message);
    }

    synchronized Map<String, Object> react(String id, String username, String emoji) {
        Map<String, Object> message = messages.get(id);
        if (message == null || !visible(message, username) || Boolean.TRUE.equals(message.get("deleted"))) {
            return null;
        }
        if (!isEmoji(emoji)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<String, List<String>> reactions = (Map<String, List<String>>) message.computeIfAbsent(
                "reactions", ignored -> new LinkedHashMap<String, List<String>>());
        List<String> people = reactions.computeIfAbsent(emoji, ignored -> new ArrayList<>());
        if (people.stream().anyMatch(name -> Username.same(name, username))) {
            people.removeIf(name -> Username.same(name, username));
        } else {
            people.add(username);
        }
        if (people.isEmpty()) {
            reactions.remove(emoji);
        }
        if (reactions.isEmpty()) {
            message.remove("reactions");
        }
        return copy(message);
    }

    synchronized Map<String, Object> seen(String id, String username) {
        Map<String, Object> message = messages.get(id);
        if (message == null || !visible(message, username) || Username.same(stringify(message.get("from")), username)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<String> seen = (List<String>) message.computeIfAbsent("seen", ignored -> new ArrayList<String>());
        if (seen.stream().anyMatch(name -> Username.same(name, username))) {
            return null;
        }
        seen.add(username);
        return copy(message);
    }

    synchronized List<Map<String, Object>> forUser(String username) {
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> message : messages.values()) {
            if (visible(message, username)) {
                visible.add(copy(message));
            }
        }
        return visible;
    }

    private Map<String, Object> owned(String id, String username) {
        Map<String, Object> message = messages.get(id);
        if (message == null || Boolean.TRUE.equals(message.get("deleted"))) {
            return null;
        }
        if (!Username.same(stringify(message.get("from")), username)) {
            return null;
        }
        return message;
    }

    private static boolean visible(Map<String, Object> message, String username) {
        if (!"pm".equals(message.get("type"))) {
            return true;
        }
        return Username.same(username, stringify(message.get("from")))
                || Username.same(username, stringify(message.get("to")));
    }

    private static boolean isEmoji(String emoji) {
        if (emoji == null || emoji.isBlank() || emoji.length() > 16) {
            return false;
        }
        return emoji.codePoints().allMatch(code -> !Character.isISOControl(code) && code > 127);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            copy.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return copy;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                copy.put(String.valueOf(entry.getKey()), copyValue(entry.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(copyValue(item));
            }
            return copy;
        }
        return value;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

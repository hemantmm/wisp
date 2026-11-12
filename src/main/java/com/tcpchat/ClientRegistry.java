package com.tcpchat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ClientRegistry {
    private final ConcurrentHashMap<String, ChatConnection> clients = new ConcurrentHashMap<>();

    boolean register(String username, ChatConnection connection) {
        return clients.putIfAbsent(username, connection) == null;
    }

    void replace(String username, ChatConnection connection) {
        clients.put(username, connection);
    }

    boolean unregister(String username, ChatConnection connection) {
        return clients.remove(username, connection);
    }

    void broadcast(Map<String, Object> payload) {
        for (ChatConnection connection : clients.values()) {
            connection.send(payload);
        }
    }

    boolean sendTo(String username, Map<String, Object> payload) {
        ChatConnection connection = clients.get(username);
        if (connection == null) {
            return false;
        }
        connection.send(payload);
        return true;
    }

    List<String> usernames() {
        return clients.keySet().stream().sorted().toList();
    }

    Map<String, Object> usersPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "users");
        payload.put("users", new ArrayList<>(usernames()));
        return payload;
    }
}

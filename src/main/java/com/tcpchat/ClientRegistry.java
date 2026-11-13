package com.tcpchat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ClientRegistry {
    enum Claim {
        NEW,
        RECLAIM,
        TAKEN,
        INVALID
    }

    private static final class Identity {
        private final String token;
        private final String displayName;
        private volatile ChatConnection connection;

        private Identity(String token, String displayName, ChatConnection connection) {
            this.token = token;
            this.displayName = displayName;
            this.connection = connection;
        }
    }

    private final ConcurrentHashMap<String, Identity> identities = new ConcurrentHashMap<>();

    Claim claim(String username, String token, ChatConnection connection) {
        if (!Username.isValid(username) || !Username.isValidToken(token)) {
            return Claim.INVALID;
        }
        String key = Username.key(username);
        Identity created = new Identity(token, username, connection);
        Identity existing = identities.putIfAbsent(key, created);
        if (existing == null) {
            return Claim.NEW;
        }
        if (!existing.token.equals(token)) {
            return Claim.TAKEN;
        }
        existing.connection = connection;
        return Claim.RECLAIM;
    }

    String displayName(String username) {
        if (!Username.isValid(username)) {
            return username;
        }
        Identity identity = identities.get(Username.key(username));
        return identity == null ? username : identity.displayName;
    }

    boolean disconnect(String username, ChatConnection connection) {
        Identity identity = identities.get(Username.key(username));
        if (identity == null || identity.connection != connection) {
            return false;
        }
        identity.connection = null;
        return true;
    }

    void broadcast(Map<String, Object> payload) {
        for (Identity identity : identities.values()) {
            ChatConnection connection = identity.connection;
            if (connection != null) {
                connection.send(payload);
            }
        }
    }

    boolean sendTo(String username, Map<String, Object> payload) {
        if (!Username.isValid(username)) {
            return false;
        }
        Identity identity = identities.get(Username.key(username));
        if (identity == null || identity.connection == null) {
            return false;
        }
        identity.connection.send(payload);
        return true;
    }

    List<String> usernames() {
        return identities.values().stream()
                .filter(identity -> identity.connection != null)
                .map(identity -> identity.displayName)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    Map<String, Object> usersPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "users");
        payload.put("users", new ArrayList<>(usernames()));
        return payload;
    }
}

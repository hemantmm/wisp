package com.tcpchat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WebChatServer {
    public static final int DEFAULT_PORT = 8080;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ClientRegistry registry = new ClientRegistry();
    private final RoomHistory history = new RoomHistory();
    private final MessageGuard guard = new MessageGuard();
    private final LinkPreviews linkPreviews = new LinkPreviews();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<WsContext, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<WsContext, Object> writeLocks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService departures = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "departures");
        thread.setDaemon(true);
        return thread;
    });

    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            port = Integer.parseInt(envPort);
        } else if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new WebChatServer().start(port);
    }

    void start(int port) {
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        });

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                writeLocks.putIfAbsent(ctx, new Object());
                ctx.enableAutomaticPings(8, java.util.concurrent.TimeUnit.SECONDS);
            });
            ws.onMessage(ctx -> handleMessage(ctx, ctx.message()));
            ws.onClose(ctx -> leave(ctx));
            ws.onError(ctx -> leave(ctx));
        });

        app.start(port);
        System.out.println("Chat app listening on http://localhost:" + port);
    }

    private void handleMessage(WsContext ctx, String raw) {
        if (guard.tooLargeFrame(raw)) {
            send(ctx, error("Message is too large."));
            return;
        }
        Map<String, Object> incoming;
        try {
            incoming = mapper.readValue(raw, MAP_TYPE);
        } catch (Exception e) {
            send(ctx, error("Invalid message."));
            return;
        }

        String type = String.valueOf(incoming.getOrDefault("type", ""));
        Session session = sessions.get(ctx);
        String username = session == null ? null : session.username;

        if ("ping".equals(type)) {
            send(ctx, Map.of("type", "pong"));
            return;
        }

        if (username == null) {
            if ("join".equals(type)) {
                if (guard.tooFast(ctx)) {
                    send(ctx, error("You're sending too fast. Wait a moment."));
                    return;
                }
                join(ctx, stringify(incoming.get("username")), stringify(incoming.get("token")));
            } else {
                send(ctx, error("Join with a username first."));
            }
            return;
        }

        switch (type) {
            case "chat" -> broadcastChat(ctx, username, stringify(incoming.get("text")), stringify(incoming.get("replyTo")));
            case "pm" -> sendPrivate(ctx, username, stringify(incoming.get("to")), stringify(incoming.get("text")), stringify(incoming.get("replyTo")));
            case "typing" -> showTyping(username, stringify(incoming.get("to")));
            case "seen" -> markSeen(username, incoming.get("ids"));
            case "edit" -> editMessage(ctx, username, stringify(incoming.get("id")), stringify(incoming.get("text")));
            case "delete" -> deleteMessage(ctx, username, stringify(incoming.get("id")));
            case "react" -> react(ctx, username, stringify(incoming.get("id")), stringify(incoming.get("emoji")));
            case "users" -> send(ctx, registry.usersPayload());
            case "leave" -> departNow(ctx);
            default -> send(ctx, error("Unknown message type."));
        }
    }

    private void join(WsContext ctx, String name, String token) {
        String username = name == null ? "" : name.trim();
        if (!Username.isValid(username)) {
            send(ctx, error("Use 1–20 letters, digits, or underscores."));
            return;
        }
        if (!Username.isValidToken(token)) {
            send(ctx, error("Missing device token."));
            return;
        }

        ChatConnection connection = payload -> send(ctx, payload);
        ClientRegistry.Claim claim = registry.claim(username, token, connection);
        if (claim == ClientRegistry.Claim.INVALID) {
            send(ctx, error("Use 1–20 letters, digits, or underscores."));
            return;
        }
        if (claim == ClientRegistry.Claim.TAKEN) {
            send(ctx, error("That username belongs to another device."));
            return;
        }

        boolean reconnect = claim == ClientRegistry.Claim.RECLAIM;
        String displayName = registry.displayName(username);
        sessions.entrySet().removeIf(entry ->
                Username.same(entry.getValue().username(), displayName) && entry.getKey() != ctx);
        sessions.put(ctx, new Session(displayName, connection));

        Map<String, Object> welcome = new LinkedHashMap<>();
        welcome.put("type", "welcome");
        welcome.put("username", displayName);
        welcome.put("users", registry.usernames());
        welcome.put("reconnected", reconnect);
        welcome.put("history", history.forUser(displayName));
        send(ctx, welcome);

        if (!reconnect) {
            registry.broadcast(system(displayName + " joined"));
        }
        registry.broadcast(registry.usersPayload());
    }

    private void broadcastChat(WsContext ctx, String from, String text, String replyTo) {
        String message = checkedText(ctx, from, text);
        if (message == null) {
            return;
        }
        Map<String, Object> payload = baseMessage("chat", from, message, replyTo);
        history.add(payload);
        registry.broadcast(payload);
    }

    private void sendPrivate(WsContext ctx, String from, String to, String text, String replyTo) {
        String target = to == null ? "" : to.trim();
        if (!Username.isValid(target)) {
            send(ctx, error("Private messages need a user and some text."));
            return;
        }
        String message = checkedText(ctx, from, text);
        if (message == null) {
            return;
        }
        if (Username.same(target, from)) {
            send(ctx, error("You cannot message yourself."));
            return;
        }
        String canonicalTo = registry.displayName(target);
        boolean online = registry.usernames().stream().anyMatch(name -> Username.same(name, target));
        if (!online) {
            send(ctx, error("User not found: " + target));
            return;
        }
        Map<String, Object> payload = baseMessage("pm", from, message, replyTo);
        payload.put("to", canonicalTo);
        history.add(payload);
        deliver(payload, false);
    }

    private String checkedText(WsContext ctx, String from, String text) {
        String message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return null;
        }
        if (guard.tooLong(message)) {
            send(ctx, error("Messages can be at most " + MessageGuard.MAX_TEXT + " characters."));
            return null;
        }
        if (guard.tooFast(Username.key(from))) {
            send(ctx, error("You're sending too fast. Wait a moment."));
            return null;
        }
        return message;
    }

    private Map<String, Object> baseMessage(String type, String from, String text, String replyTo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", type);
        payload.put("id", UUID.randomUUID().toString());
        payload.put("from", from);
        payload.put("text", text);
        payload.put("time", Instant.now().toString());
        Map<String, Object> reply = history.replySnapshot(replyTo, from);
        if (reply != null) {
            payload.put("reply", reply);
        }
        Map<String, Object> preview = linkPreviews.forText(text);
        if (preview != null) {
            payload.put("preview", preview);
        }
        return payload;
    }

    private void showTyping(String username, String to) {
        if (guard.typingTooSoon(Username.key(username) + ":typing")) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "typing");
        payload.put("from", username);
        if (Username.isValid(to)) {
            payload.put("to", registry.displayName(to));
            registry.sendTo(to, payload);
            return;
        }
        registry.broadcastExcept(username, payload);
    }

    private void markSeen(String username, Object rawIds) {
        if (!(rawIds instanceof List<?> ids)) {
            return;
        }
        int counted = 0;
        for (Object rawId : ids) {
            if (counted >= 30) {
                break;
            }
            counted++;
            Map<String, Object> updated = history.seen(stringify(rawId), username);
            if (updated != null) {
                deliver(updated, true);
            }
        }
    }

    private void editMessage(WsContext ctx, String username, String id, String text) {
        String message = checkedText(ctx, username, text);
        if (message == null) {
            return;
        }
        Map<String, Object> updated = history.edit(id, username, message, linkPreviews.forText(message));
        if (updated == null) {
            send(ctx, error("You can only edit your own messages."));
            return;
        }
        deliver(updated, true);
    }

    private void deleteMessage(WsContext ctx, String username, String id) {
        if (guard.tooFast(Username.key(username))) {
            send(ctx, error("You're sending too fast. Wait a moment."));
            return;
        }
        Map<String, Object> updated = history.delete(id, username);
        if (updated == null) {
            send(ctx, error("You can only delete your own messages."));
            return;
        }
        deliver(updated, true);
    }

    private void react(WsContext ctx, String username, String id, String emoji) {
        if (guard.tooFast(Username.key(username))) {
            send(ctx, error("You're sending too fast. Wait a moment."));
            return;
        }
        Map<String, Object> updated = history.react(id, username, emoji);
        if (updated == null) {
            send(ctx, error("Could not add that reaction."));
            return;
        }
        deliver(updated, true);
    }

    private void deliver(Map<String, Object> message, boolean mutation) {
        Map<String, Object> payload = new LinkedHashMap<>(message);
        payload.put("messageType", message.get("type"));
        if (mutation) {
            payload.put("type", "update");
        }
        if ("pm".equals(message.get("type"))) {
            registry.sendTo(stringify(message.get("from")), payload);
            registry.sendTo(stringify(message.get("to")), payload);
            return;
        }
        registry.broadcast(payload);
    }

    private void leave(WsContext ctx) {
        Session session = sessions.remove(ctx);
        if (session != null) {
            departures.schedule(() -> departIfStill(session), 8, TimeUnit.SECONDS);
        }
        writeLocks.remove(ctx);
        guard.forget(ctx);
    }

    private void departNow(WsContext ctx) {
        Session session = sessions.remove(ctx);
        writeLocks.remove(ctx);
        guard.forget(ctx);
        if (session != null) {
            departIfStill(session);
        }
    }

    private void departIfStill(Session session) {
        if (registry.disconnect(session.username, session.connection)) {
            registry.broadcast(system(session.username + " left"));
            registry.broadcast(registry.usersPayload());
        }
    }

    private record Session(String username, ChatConnection connection) {}

    private void send(WsContext ctx, Map<String, Object> payload) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return;
        }
        Object lock = writeLocks.computeIfAbsent(ctx, ignored -> new Object());
        synchronized (lock) {
            try {
                if (ctx.session.isOpen()) {
                    ctx.send(json);
                }
            } catch (Exception ignored) {
                // client already gone
            }
        }
    }

    private static Map<String, Object> error(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "error");
        payload.put("text", text);
        return payload;
    }

    private static Map<String, Object> system(String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "system");
        payload.put("text", text);
        return payload;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}

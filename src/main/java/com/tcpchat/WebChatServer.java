package com.tcpchat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;
import io.javalin.websocket.WsContext;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class WebChatServer {
    public static final int DEFAULT_PORT = 8080;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ClientRegistry registry = new ClientRegistry();
    private final RoomHistory history = new RoomHistory();
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
                join(ctx, stringify(incoming.get("username")), stringify(incoming.get("token")));
            } else {
                send(ctx, error("Join with a username first."));
            }
            return;
        }

        switch (type) {
            case "chat" -> broadcastChat(username, stringify(incoming.get("text")));
            case "pm" -> sendPrivate(ctx, username, stringify(incoming.get("to")), stringify(incoming.get("text")));
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

    private void broadcastChat(String from, String text) {
        String message = text == null ? "" : text.trim();
        if (message.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "chat");
        payload.put("from", from);
        payload.put("text", message);
        payload.put("time", Instant.now().toString());
        history.add(payload);
        registry.broadcast(payload);
    }

    private void sendPrivate(WsContext ctx, String from, String to, String text) {
        String target = to == null ? "" : to.trim();
        String message = text == null ? "" : text.trim();
        if (!Username.isValid(target) || message.isEmpty()) {
            send(ctx, error("Private messages need a user and some text."));
            return;
        }
        if (Username.same(target, from)) {
            send(ctx, error("You cannot message yourself."));
            return;
        }

        String canonicalTo = registry.displayName(target);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "pm");
        payload.put("from", from);
        payload.put("to", canonicalTo);
        payload.put("text", message);
        payload.put("time", Instant.now().toString());

        if (!registry.sendTo(target, payload)) {
            send(ctx, error("User not found: " + target));
            return;
        }
        history.add(payload);
        send(ctx, payload);
    }

    private void leave(WsContext ctx) {
        Session session = sessions.remove(ctx);
        if (session != null) {
            departures.schedule(() -> departIfStill(session), 8, TimeUnit.SECONDS);
        }
        writeLocks.remove(ctx);
    }

    private void departNow(WsContext ctx) {
        Session session = sessions.remove(ctx);
        writeLocks.remove(ctx);
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

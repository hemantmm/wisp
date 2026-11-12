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
import java.util.regex.Pattern;

public final class WebChatServer {
    public static final int DEFAULT_PORT = 8080;
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,20}$");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ClientRegistry registry = new ClientRegistry();
    private final RoomHistory history = new RoomHistory();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<WsContext, Session> sessions = new ConcurrentHashMap<>();
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
            ws.onConnect(ctx -> ctx.enableAutomaticPings(8, java.util.concurrent.TimeUnit.SECONDS));
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
                boolean reclaim = Boolean.TRUE.equals(incoming.get("reclaim"));
                join(ctx, stringify(incoming.get("username")), reclaim);
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

    private void join(WsContext ctx, String name, boolean reclaim) {
        String username = name == null ? "" : name.trim();
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            send(ctx, error("Use 1–20 letters, digits, or underscores."));
            return;
        }

        ChatConnection connection = payload -> send(ctx, payload);
        boolean reconnect = false;
        if (!registry.register(username, connection)) {
            if (!reclaim) {
                send(ctx, error("That username is already taken."));
                return;
            }
            registry.replace(username, connection);
            reconnect = true;
            sessions.entrySet().removeIf(entry ->
                    username.equals(entry.getValue().username()) && entry.getKey() != ctx);
        }

        sessions.put(ctx, new Session(username, connection));

        Map<String, Object> welcome = new LinkedHashMap<>();
        welcome.put("type", "welcome");
        welcome.put("username", username);
        welcome.put("users", registry.usernames());
        welcome.put("reconnected", reconnect);
        welcome.put("history", history.forUser(username));
        send(ctx, welcome);

        if (!reconnect) {
            registry.broadcast(system(username + " joined"));
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
        if (target.isEmpty() || message.isEmpty()) {
            send(ctx, error("Private messages need a user and some text."));
            return;
        }
        if (target.equals(from)) {
            send(ctx, error("You cannot message yourself."));
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "pm");
        payload.put("from", from);
        payload.put("to", target);
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
        if (session == null) {
            return;
        }
        departures.schedule(() -> departIfStill(session), 8, TimeUnit.SECONDS);
    }

    private void departNow(WsContext ctx) {
        Session session = sessions.remove(ctx);
        if (session != null) {
            departIfStill(session);
        }
    }

    private void departIfStill(Session session) {
        if (registry.unregister(session.username, session.connection)) {
            registry.broadcast(system(session.username + " left"));
            registry.broadcast(registry.usersPayload());
        }
    }

    private record Session(String username, ChatConnection connection) {}

    private void send(WsContext ctx, Map<String, Object> payload) {
        try {
            if (ctx.session.isOpen()) {
                ctx.send(mapper.writeValueAsString(payload));
            }
        } catch (Exception ignored) {
            // client already gone
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

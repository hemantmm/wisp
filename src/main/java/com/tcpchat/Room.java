package com.tcpchat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class Room {
    public static final String DEFAULT_ROOM = "general";
    private static final Pattern ROOM_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,30}$");

    private final String name;
    private final ClientRegistry registry = new ClientRegistry();
    private final RoomHistory history = new RoomHistory();
    private final ScheduledExecutorService scheduler;
    private final Consumer<Room> onClosed;
    private final Object lock = new Object();

    private String lifetime;
    private Instant expiresAt;
    private String creator;
    private ScheduledFuture<?> expiryTask;

    public Room(String name, ScheduledExecutorService scheduler, Consumer<Room> onClosed) {
        this.name = name;
        this.scheduler = scheduler;
        this.onClosed = onClosed;
    }

    public static boolean isValid(String name) {
        return name != null && ROOM_PATTERN.matcher(name.trim()).matches();
    }

    public static String normalize(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT_ROOM;
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1).trim();
        }
        if (trimmed.isEmpty()) {
            return DEFAULT_ROOM;
        }
        return isValid(trimmed) ? trimmed.toLowerCase(Locale.ROOT) : null;
    }

    public String name() {
        return name;
    }

    public ClientRegistry registry() {
        return registry;
    }

    public RoomHistory history() {
        return history;
    }

    public boolean isOpen() {
        synchronized (lock) {
            return lifetime != null;
        }
    }

    public boolean open(String displayName, String requestedLifetime) {
        synchronized (lock) {
            if (lifetime != null) {
                return true;
            }
            String choice = normalizeLifetime(requestedLifetime);
            if (choice == null) {
                return false;
            }
            lifetime = choice;
            creator = displayName;
            expiresAt = null;
            if (expiryTask != null) {
                expiryTask.cancel(false);
                expiryTask = null;
            }
            if (!"last".equals(choice)) {
                long millis = "1h".equals(choice) ? TimeUnit.HOURS.toMillis(1) : TimeUnit.HOURS.toMillis(24);
                expiresAt = Instant.now().plusMillis(millis);
                expiryTask = scheduler.schedule(() -> endRoom(true), millis, TimeUnit.MILLISECONDS);
            }
            return true;
        }
    }

    public boolean remember(Map<String, Object> payload) {
        synchronized (lock) {
            if (lifetime == null) {
                return false;
            }
            history.add(payload);
            return true;
        }
    }

    public Map<String, Object> roomStatus() {
        synchronized (lock) {
            return roomFields();
        }
    }

    public Map<String, Object> roomFields() {
        synchronized (lock) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("room", name);
            body.put("open", lifetime != null);
            if (lifetime == null) {
                return body;
            }
            body.put("lifetime", lifetime);
            body.put("creator", creator);
            if (expiresAt != null) {
                body.put("expiresAt", expiresAt.toString());
            }
            return body;
        }
    }

    public Map<String, Object> summary() {
        synchronized (lock) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("room", name);
            map.put("open", lifetime != null);
            map.put("users", registry.usernames().size());
            if (lifetime != null) {
                map.put("lifetime", lifetime);
                if (expiresAt != null) {
                    map.put("expiresAt", expiresAt.toString());
                }
            }
            return map;
        }
    }

    public void departIfStill(String username, ChatConnection connection) {
        if (registry.disconnect(username, connection)) {
            boolean wipe = false;
            synchronized (lock) {
                wipe = "last".equals(lifetime) && registry.usernames().isEmpty();
            }
            if (wipe) {
                endRoom(false);
                return;
            }
            registry.broadcast(WebChatServer.system(username + " left #" + name));
            registry.broadcast(registry.usersPayload());
        }
    }

    public void endRoom(boolean tellPeople) {
        synchronized (lock) {
            if (lifetime == null && expiryTask == null) {
                return;
            }
            lifetime = null;
            expiresAt = null;
            creator = null;
            if (expiryTask != null) {
                expiryTask.cancel(false);
                expiryTask = null;
            }
        }

        if (tellPeople) {
            Map<String, Object> ended = new LinkedHashMap<>();
            ended.put("type", "ended");
            ended.put("room", name);
            ended.put("text", "This room ended. Every message is gone.");
            registry.broadcast(ended);
        }

        history.clear();
        registry.clear();

        if (onClosed != null) {
            onClosed.accept(this);
        }
    }

    private static String normalizeLifetime(String raw) {
        if (raw == null || raw.isBlank()) {
            return "last";
        }
        if ("1h".equals(raw) || "24h".equals(raw) || "last".equals(raw)) {
            return raw;
        }
        return null;
    }
}

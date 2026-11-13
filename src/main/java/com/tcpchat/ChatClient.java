package com.tcpchat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

public final class ChatClient implements WebSocket.Listener {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String HELP_TEXT = """
            Commands:
              <message>           broadcast to everyone
              /msg <user> <text>  private message
              /users              list online users
              /help               show this help
              /quit               disconnect""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final StringBuilder fragments = new StringBuilder();
    private final CountDownLatch closed = new CountDownLatch(1);
    private volatile WebSocket webSocket;
    private volatile boolean joined;
    private volatile String me = "";

    public static void main(String[] args) throws Exception {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        URI uri = parseUri(args);
        System.out.println("Connecting to " + uri);
        System.out.println(HELP_TEXT);
        System.out.println();

        ChatClient client = new ChatClient();
        String origin = ("wss".equals(uri.getScheme()) ? "https" : "http")
                + "://" + uri.getHost()
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .newWebSocketBuilder()
                .header("Origin", origin)
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(uri, client)
                .join();

        client.inputLoop();
        client.closed.await();
    }

    private static URI parseUri(String[] args) {
        if (args.length == 0) {
            return URI.create("ws://localhost:" + WebChatServer.DEFAULT_PORT + "/ws");
        }
        if (args[0].startsWith("ws://") || args[0].startsWith("wss://")) {
            return URI.create(args[0]);
        }
        String host = args[0];
        int port = args.length > 1 ? Integer.parseInt(args[1]) : WebChatServer.DEFAULT_PORT;
        String scheme = port == 443 ? "wss" : "ws";
        return URI.create(scheme + "://" + host + ":" + port + "/ws");
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        this.webSocket = webSocket;
        webSocket.request(1);
        System.out.println("Enter username:");
        System.out.flush();
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        fragments.append(data);
        if (last) {
            handle(fragments.toString());
            fragments.setLength(0);
        }
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("Disconnected.");
        closed.countDown();
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        System.err.println("Connection error: " + error.getMessage());
        closed.countDown();
    }

    private void handle(String raw) {
        try {
            Map<String, Object> data = mapper.readValue(raw, MAP_TYPE);
            String type = String.valueOf(data.getOrDefault("type", ""));
            switch (type) {
                case "welcome" -> {
                    joined = true;
                    me = String.valueOf(data.get("username"));
                    System.out.println("Welcome, " + me + ". Type /help for commands.");
                    printUsers(data.get("users"));
                }
                case "users" -> printUsers(data.get("users"));
                case "system" -> System.out.println("* " + data.get("text"));
                case "chat" -> System.out.println(
                        "[" + data.get("from") + "] " + data.get("text") + formatTime(data.get("time")));
                case "pm" -> {
                    String from = String.valueOf(data.get("from"));
                    String to = String.valueOf(data.get("to"));
                    String text = String.valueOf(data.get("text"));
                    String when = formatTime(data.get("time"));
                    if (from.equals(me)) {
                        System.out.println("[PM to " + to + "] " + text + when);
                    } else {
                        System.out.println("[PM from " + from + "] " + text + when);
                    }
                }
                case "error" -> {
                    System.out.println(data.get("text"));
                    if (!joined) {
                        System.out.println("Enter username:");
                        System.out.flush();
                    }
                }
                default -> System.out.println(raw);
            }
        } catch (Exception e) {
            System.out.println(raw);
        }
    }

    private static String formatTime(Object iso) {
        if (iso == null) {
            return "";
        }
        try {
            String clock = DateTimeFormatter.ofPattern("h:mm a")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.parse(String.valueOf(iso)));
            return "  (" + clock + ")";
        } catch (Exception e) {
            return "";
        }
    }

    private void printUsers(Object users) {
        if (users instanceof List<?> list) {
            String names = String.join(", ", list.stream().map(String::valueOf).toList());
            System.out.println("Online: " + (names.isEmpty() ? "(none)" : names));
        }
    }

    private void inputLoop() throws Exception {
        BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = stdin.readLine()) != null) {
            WebSocket ws = webSocket;
            if (ws == null || ws.isOutputClosed()) {
                break;
            }
            if (!joined) {
                String username = line.trim();
                if (!Username.isValid(username)) {
                    System.out.println("Use 1–20 letters, digits, or underscores.");
                    System.out.println("Enter username:");
                    continue;
                }
                Map<String, Object> join = new LinkedHashMap<>();
                join.put("type", "join");
                join.put("username", username);
                join.put("token", deviceToken());
                send(join);
                continue;
            }
            if (line.isEmpty()) {
                continue;
            }
            if (handleCommand(line)) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "quit");
                break;
            }
        }
        closed.countDown();
    }

    /** @return true if the client should disconnect */
    private boolean handleCommand(String line) throws Exception {
        if (!line.startsWith("/")) {
            send(Map.of("type", "chat", "text", line));
            return false;
        }
        String[] parts = line.split("\\s+", 3);
        switch (parts[0].toLowerCase()) {
            case "/quit" -> {
                return true;
            }
            case "/help" -> System.out.println(HELP_TEXT);
            case "/users" -> send(Map.of("type", "users"));
            case "/msg" -> {
                if (parts.length < 3 || parts[2].isBlank()) {
                    System.out.println("Usage: /msg <user> <text>");
                } else {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("type", "pm");
                    payload.put("to", parts[1]);
                    payload.put("text", parts[2]);
                    send(payload);
                }
            }
            default -> System.out.println("Unknown command. Type /help.");
        }
        return false;
    }

    private void send(Map<String, Object> payload) throws Exception {
        webSocket.sendText(mapper.writeValueAsString(payload), true);
    }

    private static String deviceToken() {
        try {
            Path path = Path.of(System.getProperty("user.home"), ".wisp-device-token");
            if (Files.isRegularFile(path)) {
                String stored = Files.readString(path, StandardCharsets.UTF_8).trim();
                if (Username.isValidToken(stored)) {
                    return stored;
                }
            }
            String token = UUID.randomUUID().toString();
            Files.writeString(path, token, StandardCharsets.UTF_8);
            return token;
        } catch (Exception e) {
            return UUID.randomUUID().toString();
        }
    }
}

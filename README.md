# Wisp

Lightweight, real-time web & terminal chat over WebSockets. Create multi-channel rooms, broadcast messages, quote replies, react with emojis, and send private whispers. Disconnects are gracefully buffered so transient refreshes don't wipe active sessions.

## Features

- **Multi-Room / Channels**: Each channel maintains its own `RoomHistory`, user presence, and independent expiration timer.
- **URL Routing**: Direct links via URL hashes (`http://localhost:8080/#engineering`) or friendly paths (`http://localhost:8080/r/engineering`).
- **Dual Interface**: Full feature parity across modern browsers and terminal CLI clients.
- **Whispers & Reactions**: Direct private messages (`pm`), quoted reply snapshots, and live emoji reactions.
- **Configurable Lifetimes**: Choose 1 hour, 24 hours, or automatic teardown when the last person leaves.

## Run locally

Java 17+ and Maven:

```bash
mvn -q package
java -jar target/tcpchat-1.0.0.jar
```

Open [http://localhost:8080](http://localhost:8080) (defaults to `#general`), or visit [http://localhost:8080/#engineering](http://localhost:8080/#engineering) (or [http://localhost:8080/r/engineering](http://localhost:8080/r/engineering)) to join or create a custom room.

### CLI client

In another terminal:

```bash
# Connect to default #general room:
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient

# Connect directly to a specific room (e.g. #engineering):
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient engineering

# Connect to custom host, port, and room:
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient localhost 8080 engineering
```

CLI commands:
- `/join <room>` — switch or create another channel
- `/room` — view current room
- `/msg <user> <text>` — send private whisper
- `/users` — list users in current room
- `/help` — display commands
- `/quit` — disconnect

Use another port if 8080 is taken:

```bash
java -jar target/tcpchat-1.0.0.jar 8081
```

## Deploy / host

The app listens on `PORT` when that environment variable is set (Render, Railway, Fly, and most PaaS hosts set this for you).

### Docker

```bash
docker build -t wisp .
docker run --rm -p 8080:8080 wisp
```

### Render / Railway / Fly

1. Push this folder to GitHub.
2. Create a new **Web Service** from that repo.
3. Use the Dockerfile (or a native Java build that runs `java -jar target/tcpchat-1.0.0.jar`).
4. After it goes live, open the public URL in two browsers or devices to chat.

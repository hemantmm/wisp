# Wisp

Browser chat over WebSockets: several people can join one room, broadcast messages, and send private whispers. Disconnects are cleaned up so the online list stays accurate.

## Run locally

Java 17+ and Maven:

```bash
mvn -q package
java -jar target/tcpchat-1.0.0.jar
```

Open [http://localhost:8080](http://localhost:8080) in a browser, and/or join from the terminal. Browser and CLI users share the same room.

### CLI client

In another terminal:

```bash
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient
```

Same host/port as the server if it isn’t localhost:8080:

```bash
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient localhost 8081
```

Hosted site:

```bash
java -cp target/tcpchat-1.0.0.jar com.tcpchat.ChatClient wss://your-app.example.com/ws
```

CLI commands: `/msg bob hello`, `/users`, `/help`, `/quit`.

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
4. After it goes live, open the public URL in two browsers to chat.

HTTPS hosts should just work: the page uses `wss://` automatically.

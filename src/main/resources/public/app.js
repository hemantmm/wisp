const joinScreen = document.getElementById("join-screen");
const chatScreen = document.getElementById("chat-screen");
const joinForm = document.getElementById("join-form");
const usernameInput = document.getElementById("username");
const joinError = document.getElementById("join-error");
const userList = document.getElementById("user-list");
const messages = document.getElementById("messages");
const composer = document.getElementById("composer");
const textInput = document.getElementById("text");
const meLabel = document.getElementById("me");
const roomTitle = document.getElementById("room-title");
const roomHint = document.getElementById("room-hint");
const peopleToggle = document.getElementById("people-toggle");
const sidebarClose = document.getElementById("sidebar-close");
const scrim = document.getElementById("scrim");
const onlineCount = document.getElementById("online-count");
const onlinePill = document.getElementById("online-pill");
const leaveButton = document.getElementById("leave");
const nameFields = document.getElementById("name-fields");
const resumeCopy = document.getElementById("resume-copy");
const resumeName = document.getElementById("resume-name");
const joinSubmit = document.getElementById("join-submit");

const HEARTBEAT_MS = 2000;
const NAME_KEY = "wisp.username";
const USERNAME_PATTERN = /^[A-Za-z0-9_]{1,20}$/;

let socket;
let me = "";
let whisperTo = null;
let pendingUsername = "";
let onlineUsers = [];
let stayInRoom = false;
let joined = false;
let heartbeatTimer;
let reconnectTimer;
let reconnectAttempt = 0;
let lastSystem = "";

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws`;
}

function startHeartbeat() {
  stopHeartbeat();
  heartbeatTimer = setInterval(() => {
    if (socket && socket.readyState === WebSocket.OPEN) {
      send({ type: "ping" });
    }
  }, HEARTBEAT_MS);
}

function stopHeartbeat() {
  clearInterval(heartbeatTimer);
  heartbeatTimer = undefined;
}

function connect() {
  if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
    return;
  }
  socket = new WebSocket(wsUrl());
  socket.addEventListener("open", () => {
    reconnectAttempt = 0;
    startHeartbeat();
    const name = lockedName() || me || pendingUsername;
    if (name) {
      send({ type: "join", username: name, reclaim: Boolean(lockedName()) });
    }
  });
  socket.addEventListener("message", (event) => {
    handle(JSON.parse(event.data));
  });
  socket.addEventListener("close", () => {
    stopHeartbeat();
    if (stayInRoom) {
      scheduleReconnect();
    }
  });
}

function scheduleReconnect() {
  if (reconnectTimer) return;
  reconnectAttempt += 1;
  const delay = Math.min(400 * reconnectAttempt, 4000);
  addSystem("Connection lost. Reconnecting…");
  reconnectTimer = setTimeout(() => {
    reconnectTimer = null;
    connect();
  }, delay);
}

function send(payload) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}

function showJoinError(text) {
  joinError.hidden = false;
  joinError.textContent = text;
}

function openPeople() {
  chatScreen.classList.add("people-open");
  scrim.hidden = false;
}

function closePeople() {
  chatScreen.classList.remove("people-open");
  scrim.hidden = true;
}

function handle(data) {
  switch (data.type) {
    case "pong":
      break;
    case "error":
      if (!joined) {
        stayInRoom = false;
        showJoinScreen();
        showJoinError(data.text);
      } else {
        addSystem(data.text);
      }
      break;
    case "welcome":
      joined = true;
      me = data.username;
      stayInRoom = true;
      pendingUsername = data.username;
      localStorage.setItem(NAME_KEY, me);
      joinScreen.hidden = true;
      chatScreen.hidden = false;
      meLabel.textContent = me;
      renderUsers(data.users || []);
      messages.replaceChildren();
      if (!data.reconnected) {
        addSystem(`Welcome, ${me}.`);
      }
      for (const item of data.history || []) {
        if (item.type === "pm") {
          addChat(item.from, item.text, true, item.to, item.time);
        } else {
          addChat(item.from, item.text, false, undefined, item.time);
        }
      }
      textInput.focus();
      break;
    case "users":
      renderUsers(data.users || []);
      break;
    case "system":
      addSystem(data.text);
      break;
    case "chat":
      addChat(data.from, data.text, false, undefined, data.time);
      break;
    case "pm":
      addChat(data.from, data.text, true, data.to, data.time);
      break;
    default:
      break;
  }
}

function initial(name) {
  const letter = [...String(name || "?")][0] || "?";
  return letter.toUpperCase();
}

function addPerson(target, avatarText, label, className) {
  const item = document.createElement("li");
  item.dataset.target = target;
  if (className) {
    item.className = className;
  }
  const avatar = document.createElement("span");
  avatar.className = target === "" ? "avatar room" : "avatar";
  avatar.textContent = avatarText;
  const caption = document.createElement("span");
  caption.textContent = label;
  item.append(avatar, caption);
  userList.append(item);
  return item;
}

function renderUsers(users) {
  const safeUsers = (users || []).filter((name) => USERNAME_PATTERN.test(name));
  onlineUsers = safeUsers;
  onlineCount.textContent = String(safeUsers.length);
  userList.replaceChildren();

  const everyone = addPerson("", "All", "Everyone", whisperTo ? "" : "active");
  everyone.addEventListener("click", () => setTarget(null));

  for (const name of safeUsers) {
    const classes = [];
    if (name === me) classes.push("you");
    if (whisperTo === name) classes.push("active");
    const item = addPerson(name, initial(name), name === me ? `${name} (you)` : name, classes.join(" "));
    if (name !== me) {
      item.addEventListener("click", () => setTarget(name));
    }
  }
}

function setTarget(name) {
  whisperTo = name;
  if (name) {
    roomTitle.textContent = name;
    roomHint.textContent = `Private · only ${name} will see this.`;
    textInput.placeholder = `Message ${name}`;
  } else {
    roomTitle.textContent = "Everyone";
    roomHint.textContent = "Messages go to the whole room.";
    textInput.placeholder = "Message everyone";
  }
  for (const item of userList.children) {
    const target = item.dataset.target || "";
    item.classList.toggle("active", name ? target === name : target === "");
  }
  closePeople();
  textInput.focus();
}

function addSystem(text) {
  if (text === lastSystem) return;
  lastSystem = text;
  const item = document.createElement("li");
  item.className = "system";
  item.textContent = text;
  messages.append(item);
  item.scrollIntoView({ block: "end" });
}

function formatTime(iso) {
  const date = iso ? new Date(iso) : new Date();
  if (Number.isNaN(date.getTime())) {
    return new Date().toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
  }
  return date.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function addChat(from, text, isPm, to, isoTime) {
  lastSystem = "";
  const item = document.createElement("li");
  item.className = from === me ? "mine" : "theirs";
  if (isPm) item.classList.add("pm");
  const meta = document.createElement("span");
  meta.className = "meta";
  meta.textContent = isPm
    ? from === me
      ? `whisper to ${to}`
      : `whisper from ${from}`
    : from;
  const body = document.createElement("span");
  body.textContent = text;
  const time = document.createElement("time");
  time.className = "time";
  time.dateTime = isoTime || new Date().toISOString();
  time.textContent = formatTime(isoTime);
  item.append(meta, body, time);
  messages.append(item);
  item.scrollIntoView({ block: "end" });
}

function lockedName() {
  return localStorage.getItem(NAME_KEY) || "";
}

function showJoinScreen() {
  const name = lockedName();
  joinError.hidden = true;
  joinScreen.hidden = false;
  chatScreen.hidden = true;
  if (name) {
    usernameInput.removeAttribute("required");
    nameFields.hidden = true;
    resumeCopy.hidden = false;
    resumeName.textContent = name;
    joinSubmit.textContent = "Continue as " + name;
  } else {
    usernameInput.required = true;
    nameFields.hidden = false;
    resumeCopy.hidden = true;
    joinSubmit.textContent = "Join the room";
  }
}

function exitRoom() {
  joined = false;
  stayInRoom = false;
  clearTimeout(reconnectTimer);
  reconnectTimer = null;
  stopHeartbeat();
  if (socket && socket.readyState === WebSocket.OPEN) {
    send({ type: "leave" });
    socket.close();
  }
  whisperTo = null;
  messages.replaceChildren();
  closePeople();
  showJoinScreen();
}

peopleToggle.addEventListener("click", openPeople);
onlinePill.addEventListener("click", openPeople);
sidebarClose.addEventListener("click", closePeople);
scrim.addEventListener("click", closePeople);

joinForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const saved = lockedName();
  pendingUsername = saved || usernameInput.value.trim();
  if (!USERNAME_PATTERN.test(pendingUsername)) {
    showJoinError("Use 1–20 letters, digits, or underscores.");
    return;
  }
  if (saved && pendingUsername !== saved) {
    pendingUsername = saved;
  }
  me = pendingUsername;
  joinError.hidden = true;
  if (!socket || socket.readyState === WebSocket.CLOSED) {
    connect();
  } else if (socket.readyState === WebSocket.OPEN) {
    send({ type: "join", username: pendingUsername, reclaim: Boolean(saved) });
  }
});

composer.addEventListener("submit", (event) => {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!text || !socket || socket.readyState !== WebSocket.OPEN) return;
  if (whisperTo) send({ type: "pm", to: whisperTo, text });
  else send({ type: "chat", text });
  textInput.value = "";
});

document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "visible" && stayInRoom) {
    if (!socket || socket.readyState === WebSocket.CLOSED) {
      connect();
    }
  }
});

leaveButton.addEventListener("click", exitRoom);

window.addEventListener("storage", (event) => {
  if (event.key !== NAME_KEY || !event.newValue) {
    return;
  }
  me = event.newValue;
  pendingUsername = event.newValue;
});

const savedName = lockedName();
if (savedName) {
  me = savedName;
  pendingUsername = savedName;
  stayInRoom = true;
  meLabel.textContent = savedName;
  joinScreen.hidden = true;
  chatScreen.hidden = false;
}

connect();

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
const roomClock = document.getElementById("room-clock");
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
const lifetimePicker = document.getElementById("lifetime");
const roomStatus = document.getElementById("room-status");
const typingLine = document.getElementById("typing");
const composeContext = document.getElementById("compose-context");
const composeLabel = document.getElementById("compose-label");
const composeCancel = document.getElementById("compose-cancel");
const sendButton = document.querySelector("#composer button[type=submit]");

const HEARTBEAT_MS = 2000;
const NAME_KEY = "wisp.username";
const TOKEN_KEY = "wisp.token";
const USERNAME_PATTERN = /^[A-Za-z0-9_]{1,20}$/;
const MAX_TEXT = 2000;
const REACTIONS = ["👍", "❤️", "😂", "😮", "😢", "🎉"];
const URL_PATTERN = /https?:\/\/[^\s<]+/gi;

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
let replyTo = null;
let editingId = null;
let lastTypingSent = 0;
const typingUntil = new Map();
let seenTimer;
const pendingSeen = new Set();
let pendingLifetime = "1h";
let joinOnOpen = false;
let roomExpiresAt = null;
let clockTimer;
let roomOfferTimer;

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
    if (name && (stayInRoom || joinOnOpen)) {
      joinOnOpen = false;
      send({ type: "join", username: name, token: deviceToken(), lifetime: pendingLifetime });
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
      stopRoomOffer();
      joinScreen.hidden = true;
      chatScreen.hidden = false;
      meLabel.textContent = me;
      renderUsers(data.users || []);
      messages.replaceChildren();
      applyRoom(data);
      if (!data.reconnected) {
        addSystem(`Welcome, ${me}.`);
      }
      for (const item of data.history || []) {
        renderMessage(item);
        noteSeen(item);
      }
      textInput.focus();
      break;
    case "ended":
      joined = false;
      stayInRoom = false;
      stopClock();
      messages.replaceChildren();
      showJoinScreen();
      roomStatus.hidden = false;
      roomStatus.textContent = data.text;
      break;
    case "users":
      renderUsers(data.users || []);
      break;
    case "system":
      addSystem(data.text);
      break;
    case "chat":
    case "pm":
    case "update":
      renderMessage(data);
      noteSeen(data);
      break;
    case "typing":
      showTyping(data.from, data.to);
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
    if (sameName(name, me)) classes.push("you");
    if (sameName(whisperTo, name)) classes.push("active");
    const item = addPerson(name, initial(name), sameName(name, me) ? `${name} (you)` : name, classes.join(" "));
    if (!sameName(name, me)) {
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
    item.classList.toggle("active", name ? sameName(target, name) : target === "");
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

function messageKind(data) {
  return data.messageType || data.type;
}

function renderMessage(data) {
  lastSystem = "";
  const kind = messageKind(data);
  let item = data.id ? messages.querySelector(`[data-id="${CSS.escape(data.id)}"]`) : null;
  const created = !item;
  if (!item) {
    item = document.createElement("li");
    if (data.id) item.dataset.id = data.id;
  }
  item.className = sameName(data.from, me) ? "mine" : "theirs";
  if (kind === "pm") item.classList.add("pm");
  item.replaceChildren();

  const meta = document.createElement("span");
  meta.className = "meta";
  meta.textContent = kind === "pm"
    ? sameName(data.from, me)
      ? `whisper to ${data.to}`
      : `whisper from ${data.from}`
    : data.from;
  item.append(meta);

  if (data.reply) {
    const quote = document.createElement("span");
    quote.className = "quote";
    quote.textContent = `${data.reply.from}: ${data.reply.text}`;
    item.append(quote);
  }

  const body = document.createElement("span");
  body.className = data.deleted ? "body deleted" : "body";
  if (data.deleted) {
    body.textContent = "This message was deleted";
  } else {
    appendTextWithLinks(body, data.text || "");
  }
  item.append(body);

  if (!data.deleted && data.preview && /^https?:\/\//.test(data.preview.url || "")) {
    item.append(previewCard(data.preview));
  }
  if (!data.deleted) {
    item.append(reactionRow(data));
  }

  const time = document.createElement("time");
  time.className = "time";
  time.dateTime = data.time || new Date().toISOString();
  time.textContent = formatTime(data.time) + (data.edited && !data.deleted ? " · edited" : "");
  item.append(time);

  const seen = (data.seen || []).filter((name) => !sameName(name, data.from));
  if (seen.length) {
    const receipt = document.createElement("span");
    receipt.className = "seen";
    receipt.textContent = `Seen by ${seen.join(", ")}`;
    item.append(receipt);
  }

  if (!data.deleted) {
    item.append(actionRow(data));
  }
  if (created) messages.append(item);
  if (created) item.scrollIntoView({ block: "end" });
}

function appendTextWithLinks(parent, text) {
  URL_PATTERN.lastIndex = 0;
  let last = 0;
  for (const match of text.matchAll(URL_PATTERN)) {
    if (match.index > last) {
      parent.append(document.createTextNode(text.slice(last, match.index)));
    }
    const url = match[0].replace(/[.,!?;:)]+$/, "");
    if (/^https?:\/\//i.test(url)) {
      const link = document.createElement("a");
      link.href = url;
      link.textContent = url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      parent.append(link);
    } else {
      parent.append(document.createTextNode(match[0]));
    }
    last = match.index + match[0].length;
  }
  if (last < text.length) parent.append(document.createTextNode(text.slice(last)));
}

function previewCard(preview) {
  const card = document.createElement("a");
  card.className = "preview";
  card.href = preview.url;
  card.target = "_blank";
  card.rel = "noopener noreferrer";
  if (typeof preview.image === "string" && preview.image.startsWith("https://")) {
    const image = document.createElement("img");
    image.alt = "";
    image.referrerPolicy = "no-referrer";
    image.src = preview.image;
    card.append(image);
  }
  const title = document.createElement("strong");
  title.textContent = preview.title || preview.site || preview.url;
  card.append(title);
  if (preview.description) {
    const description = document.createElement("span");
    description.textContent = preview.description;
    card.append(description);
  }
  return card;
}

function reactionRow(data) {
  const row = document.createElement("div");
  row.className = "reactions";
  const reactions = data.reactions || {};
  for (const emoji of REACTIONS) {
    const people = reactions[emoji] || [];
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = people.length ? `${emoji} ${people.length}` : emoji;
    if (people.some((name) => sameName(name, me))) button.classList.add("mine");
    button.addEventListener("click", () => send({ type: "react", id: data.id, emoji }));
    row.append(button);
  }
  return row;
}

function actionRow(data) {
  const row = document.createElement("div");
  row.className = "actions";
  const reply = document.createElement("button");
  reply.type = "button";
  reply.textContent = "Reply";
  reply.addEventListener("click", () => startReply(data));
  row.append(reply);
  if (sameName(data.from, me)) {
    const edit = document.createElement("button");
    edit.type = "button";
    edit.textContent = "Edit";
    edit.addEventListener("click", () => startEdit(data));
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "Delete";
    remove.addEventListener("click", () => {
      if (!data.id) {
        addSystem("This message cannot be deleted.");
        return;
      }
      send({ type: "delete", id: data.id });
    });
    row.append(edit, remove);
  }
  return row;
}

function startReply(data) {
  editingId = null;
  replyTo = { id: data.id, from: data.from, text: data.text || "" };
  composeContext.hidden = false;
  composeLabel.textContent = `Replying to ${data.from}`;
  textInput.focus();
}

function startEdit(data) {
  if (!data.id) {
    addSystem("This message cannot be edited.");
    return;
  }
  replyTo = null;
  editingId = data.id;
  textInput.value = data.text || "";
  composeContext.hidden = false;
  composeLabel.textContent = "Editing your message. Change it, then press Save.";
  sendButton.textContent = "Save";
  textInput.focus();
  textInput.select();
  document.getElementById("composer").scrollIntoView({ block: "end" });
}

function clearComposeContext() {
  replyTo = null;
  editingId = null;
  composeContext.hidden = true;
  composeLabel.textContent = "";
  sendButton.textContent = "Send";
}

function noteSeen(data) {
  if (!data.id || data.deleted || sameName(data.from, me)) return;
  if ((data.seen || []).some((name) => sameName(name, me))) return;
  pendingSeen.add(data.id);
  clearTimeout(seenTimer);
  seenTimer = setTimeout(() => {
    const ids = [...pendingSeen].slice(0, 30);
    pendingSeen.clear();
    if (ids.length) send({ type: "seen", ids });
  }, 400);
}

function showTyping(from, to) {
  if (!from || sameName(from, me)) return;
  if (whisperTo) {
    if (!sameName(from, whisperTo)) return;
  } else if (to) {
    return;
  }
  typingUntil.set(from, Date.now() + 3000);
  renderTyping();
}

function renderTyping() {
  const now = Date.now();
  const names = [...typingUntil.entries()].filter(([, until]) => until > now).map(([name]) => name);
  for (const [name, until] of typingUntil) {
    if (until <= now) typingUntil.delete(name);
  }
  if (!names.length) {
    typingLine.hidden = true;
    typingLine.textContent = "";
    return;
  }
  typingLine.hidden = false;
  typingLine.textContent = names.length === 1 ? `${names[0]} is typing…` : `${names.join(", ")} are typing…`;
  setTimeout(renderTyping, 1000);
}

function lockedName() {
  return localStorage.getItem(NAME_KEY) || "";
}

function sameName(left, right) {
  return String(left || "").toLowerCase() === String(right || "").toLowerCase();
}

function deviceToken() {
  let token = localStorage.getItem(TOKEN_KEY);
  if (!token) {
    token = crypto.randomUUID();
    localStorage.setItem(TOKEN_KEY, token);
  }
  return token;
}

function showJoinScreen() {
  const name = lockedName();
  joinError.hidden = true;
  joinScreen.hidden = false;
  chatScreen.hidden = true;
  stopClock();
  if (name) {
    usernameInput.removeAttribute("required");
    nameFields.hidden = true;
    resumeCopy.hidden = false;
    resumeName.textContent = name;
  } else {
    usernameInput.required = true;
    nameFields.hidden = false;
    resumeCopy.hidden = true;
  }
  refreshRoomOffer();
  stopRoomOffer();
  roomOfferTimer = setInterval(refreshRoomOffer, 5000);
}

function selectedLifetime() {
  const picked = document.querySelector("input[name=lifetime]:checked");
  return picked ? picked.value : "1h";
}

function stopRoomOffer() {
  clearInterval(roomOfferTimer);
  roomOfferTimer = undefined;
}

async function refreshRoomOffer() {
  let room;
  try {
    room = await (await fetch("/room")).json();
  } catch (error) {
    return;
  }
  if (joined) {
    return;
  }
  const saved = lockedName();
  if (room.open) {
    lifetimePicker.hidden = true;
    roomStatus.hidden = false;
    roomStatus.textContent = room.lifetime === "last"
      ? "A room is open. Messages disappear when the last person leaves."
      : "A room is open. It ends in " + formatRemaining(Date.parse(room.expiresAt) - Date.now()) + ".";
    joinSubmit.textContent = saved ? "Continue as " + saved : "Join the room";
  } else {
    lifetimePicker.hidden = false;
    if (!roomStatus.textContent.startsWith("This room ended")) {
      roomStatus.hidden = true;
    }
    joinSubmit.textContent = saved ? "Start as " + saved : "Start the room";
  }
}

function applyRoom(data) {
  roomExpiresAt = data.expiresAt ? Date.parse(data.expiresAt) : null;
  startClock();
}

function startClock() {
  stopClock();
  renderClock();
  if (roomExpiresAt) {
    clockTimer = setInterval(renderClock, 1000);
  }
}

function stopClock() {
  clearInterval(clockTimer);
  clockTimer = undefined;
}

function renderClock() {
  if (!roomExpiresAt) {
    roomClock.textContent = "Ends when the last person leaves";
    return;
  }
  const left = roomExpiresAt - Date.now();
  roomClock.textContent = left <= 0 ? "This room is ending" : "Ends in " + formatRemaining(left);
}

function formatRemaining(ms) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const pad = (value) => String(value).padStart(2, "0");
  if (hours > 0) {
    return hours + "h " + pad(minutes) + "m " + pad(seconds) + "s";
  }
  return pad(minutes) + ":" + pad(seconds);
}

function exitRoom() {
  joined = false;
  stayInRoom = false;
  stopClock();
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
  pendingLifetime = selectedLifetime();
  joinOnOpen = true;
  me = pendingUsername;
  joinError.hidden = true;
  if (!socket || socket.readyState === WebSocket.CLOSED) {
    connect();
  } else if (socket.readyState === WebSocket.OPEN) {
    send({ type: "join", username: pendingUsername, token: deviceToken(), lifetime: pendingLifetime });
  }
});

composer.addEventListener("submit", (event) => {
  event.preventDefault();
  const text = textInput.value.trim();
  if (!socket || socket.readyState !== WebSocket.OPEN) return;
  if (editingId && !text) {
    composeLabel.textContent = "Type the new text, then press Save. Delete removes the message.";
    return;
  }
  if (!text) return;
  if (text.length > MAX_TEXT) {
    addSystem("Messages can be at most " + MAX_TEXT + " characters.");
    return;
  }
  if (editingId) {
    send({ type: "edit", id: editingId, text });
  } else if (whisperTo) {
    const payload = { type: "pm", to: whisperTo, text };
    if (replyTo) payload.replyTo = replyTo.id;
    send(payload);
  } else {
    const payload = { type: "chat", text };
    if (replyTo) payload.replyTo = replyTo.id;
    send(payload);
  }
  textInput.value = "";
  clearComposeContext();
});

textInput.addEventListener("input", () => {
  if (editingId || !socket || socket.readyState !== WebSocket.OPEN) return;
  const now = Date.now();
  if (now - lastTypingSent < 1500) return;
  lastTypingSent = now;
  const payload = { type: "typing" };
  if (whisperTo) payload.to = whisperTo;
  send(payload);
});

composeCancel.addEventListener("click", () => {
  textInput.value = "";
  clearComposeContext();
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
  meLabel.textContent = savedName;
}

async function boot() {
  let room = { open: false };
  try {
    room = await (await fetch("/room")).json();
  } catch (error) {
    room = { open: false };
  }
  if (savedName && room.open) {
    pendingLifetime = room.lifetime || "last";
    stayInRoom = true;
    joinScreen.hidden = true;
    chatScreen.hidden = false;
    connect();
    return;
  }
  showJoinScreen();
  connect();
}

boot();

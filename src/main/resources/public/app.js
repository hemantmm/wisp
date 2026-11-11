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

let socket;
let me = "";
let whisperTo = null;
let pendingUsername = "";
let onlineUsers = [];

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws`;
}

function connect() {
  socket = new WebSocket(wsUrl());
  socket.addEventListener("open", () => {
    if (pendingUsername) {
      send({ type: "join", username: pendingUsername });
    }
  });
  socket.addEventListener("message", (event) => {
    handle(JSON.parse(event.data));
  });
  socket.addEventListener("close", () => {
    addSystem("Disconnected from server.");
  });
}

function send(payload) {
  socket.send(JSON.stringify(payload));
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
    case "error":
      if (!me) showJoinError(data.text);
      else addSystem(data.text);
      break;
    case "welcome":
      me = data.username;
      joinScreen.hidden = true;
      chatScreen.hidden = false;
      meLabel.textContent = me;
      renderUsers(data.users || []);
      addSystem(`Welcome, ${me}.`);
      textInput.focus();
      break;
    case "users":
      renderUsers(data.users || []);
      break;
    case "system":
      addSystem(data.text);
      break;
    case "chat":
      addChat(data.from, data.text, false);
      break;
    case "pm":
      addChat(data.from, data.text, true, data.to);
      break;
    default:
      break;
  }
}

function initial(name) {
  return (name || "?").slice(0, 1).toUpperCase();
}

function renderUsers(users) {
  onlineUsers = users;
  onlineCount.textContent = String(users.length);
  userList.innerHTML = "";

  const everyone = document.createElement("li");
  everyone.dataset.target = "";
  everyone.className = whisperTo ? "" : "active";
  everyone.innerHTML = `<span class="avatar room">All</span><span>Everyone</span>`;
  everyone.addEventListener("click", () => setTarget(null));
  userList.append(everyone);

  for (const name of users) {
    const item = document.createElement("li");
    item.dataset.target = name;
    if (name === me) item.classList.add("you");
    if (whisperTo === name) item.classList.add("active");
    const label = name === me ? `${name} (you)` : name;
    item.innerHTML = `<span class="avatar">${initial(name)}</span><span>${label}</span>`;
    if (name !== me) {
      item.addEventListener("click", () => setTarget(name));
    }
    userList.append(item);
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
  const item = document.createElement("li");
  item.className = "system";
  item.textContent = text;
  messages.append(item);
  item.scrollIntoView({ block: "end" });
}

function addChat(from, text, isPm, to) {
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
  item.append(meta, body);
  messages.append(item);
  item.scrollIntoView({ block: "end" });
}

peopleToggle.addEventListener("click", openPeople);
onlinePill.addEventListener("click", openPeople);
sidebarClose.addEventListener("click", closePeople);
scrim.addEventListener("click", closePeople);

joinForm.addEventListener("submit", (event) => {
  event.preventDefault();
  pendingUsername = usernameInput.value.trim();
  joinError.hidden = true;
  if (!socket || socket.readyState === WebSocket.CLOSED) {
    connect();
  } else if (socket.readyState === WebSocket.OPEN) {
    send({ type: "join", username: pendingUsername });
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

connect();

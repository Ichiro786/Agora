// Agora WebUI: sign-in and the chat layout frame, styled after the app's Material 3 theme.
// Conversations, messages and chat actions arrive with the WebSocket sync in later stages.
import { h, render } from "./vendor/preact.mjs";
import { useEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import htm from "./vendor/htm.mjs";

const html = htm.bind(h);

const TEXT = {
  en: {
    title: "Agora",
    signInTitle: "Sign In",
    signInHint: "Enter the WebUI password set in the Agora app.",
    show: "Show",
    hide: "Hide",
    password: "Password",
    signIn: "Sign In",
    signingIn: "Signing in…",
    signOut: "Sign Out",
    openMenu: "Open Conversations",
    conversations: "Conversations",
    conversationsEmpty: "Conversations from the phone will appear here in a later update.",
    newChat: "New Chat",
    chatEmptyTitle: "Connected to Agora",
    chatEmptyHint: "Reading and sending messages from the browser arrive in a later update.",
    message: "Message",
    send: "Send",
    wrongPassword: (left) => `Wrong password. ${left} attempts left before a 5-minute lock.`,
    locked: (seconds) => `Too many attempts. Try again in ${Math.ceil(seconds / 60)} min.`,
    notConfigured: "No WebUI password is set in the app.",
    failed: "Could not reach Agora. Check that the phone is on the same network.",
  },
  zh: {
    title: "Agora",
    signInTitle: "登录",
    signInHint: "输入在 Agora App 里设置的 WebUI 密码。",
    show: "显示",
    hide: "隐藏",
    password: "密码",
    signIn: "登录",
    signingIn: "正在登录…",
    signOut: "退出登录",
    openMenu: "打开会话列表",
    conversations: "会话",
    conversationsEmpty: "手机上的会话会在后续更新中显示在这里。",
    newChat: "新对话",
    chatEmptyTitle: "已连接 Agora",
    chatEmptyHint: "在浏览器里查看和发送消息会在后续更新中加入。",
    message: "消息",
    send: "发送",
    wrongPassword: (left) => `密码错误。再错 ${left} 次将锁定 5 分钟。`,
    locked: (seconds) => `尝试次数过多，请 ${Math.ceil(seconds / 60)} 分钟后再试。`,
    notConfigured: "App 里还没有设置 WebUI 密码。",
    failed: "连不上 Agora。请确认手机和这台设备在同一网络。",
  },
};

const lang = navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en";
const t = TEXT[lang];
document.documentElement.lang = lang;

async function postJson(path, body) {
  return fetch(path, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
}

// Material Symbols paths (Apache 2.0), as the app's icons.
const ICON_VISIBILITY = "M12 4.5C7 4.5 2.73 7.61 1 12c1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5zM12 17c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zm0-8c-1.66 0-3 1.34-3 3s1.34 3 3 3 3-1.34 3-3-1.34-3-3-3z";
const ICON_VISIBILITY_OFF = "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z";
const ICON_WEB = "M20 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2zm-5 14H4v-4h11v4zm0-5H4V9h11v4zm5 5h-4V9h4v9z";

const ICON_MENU = "M3 18h18v-2H3v2zm0-5h18v-2H3v2zm0-7v2h18V6H3z";
const ICON_LOGOUT = "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z";
const ICON_SEND = "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z";
const ICON_CHAT = "M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z";

const icon = (d) => html`<svg viewBox="0 0 24 24" aria-hidden="true"><path d=${d} /></svg>`;

function Brand() {
  return html`<div class="brand">${icon(ICON_WEB)}<span>${t.title}</span></div>`;
}

/** Field error for a wrong password; banner text for everything else. */
function describeError(status, body) {
  if (status === 401 && body?.error === "wrong_password") {
    return { field: t.wrongPassword(body.attemptsLeft) };
  }
  if (status === 429) return { banner: t.locked(body?.retryAfterSeconds ?? 300) };
  if (status === 503) return { banner: t.notConfigured };
  return { banner: t.failed };
}

function SignIn({ onSignedIn }) {
  const [password, setPassword] = useState("");
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState({});

  async function submit(event) {
    event.preventDefault();
    if (!password || busy) return;
    setBusy(true);
    setError({});
    try {
      const response = await postJson("/api/login", { password });
      if (response.ok) {
        onSignedIn();
        return;
      }
      const body = await response.json().catch(() => null);
      setError(describeError(response.status, body));
    } catch {
      setError({ banner: t.failed });
    } finally {
      setBusy(false);
    }
  }

  return html`
    <form class="card" onSubmit=${submit}>
      <${Brand} />
      <h1>${t.signInTitle}</h1>
      <p>${t.signInHint}</p>
      ${error.banner && html`<div class="banner" role="alert">${error.banner}</div>`}
      <div class=${error.field ? "field invalid" : "field"}>
        <input id="password" type=${visible ? "text" : "password"} autocomplete="current-password"
          autofocus placeholder=" " aria-invalid=${error.field ? "true" : "false"}
          aria-describedby="password-supporting"
          value=${password} onInput=${(e) => { setPassword(e.currentTarget.value); setError({}); }} />
        <label for="password">${t.password}</label>
        <button class="icon-button" type="button" onClick=${() => setVisible(!visible)}
          aria-label=${visible ? t.hide : t.show}>
          ${icon(visible ? ICON_VISIBILITY_OFF : ICON_VISIBILITY)}
        </button>
      </div>
      <div class="supporting" id="password-supporting" role=${error.field ? "alert" : null}>
        ${error.field ?? ""}
      </div>
      <div class="actions">
        <button class="button filled" type="submit" disabled=${busy || !password}
          aria-label=${busy ? t.signingIn : null}>
          ${busy ? html`<span class="spinner" aria-hidden="true"></span>` : t.signIn}
        </button>
      </div>
    </form>`;
}

// Material 3 "expanded" width class: from here the conversation list is a standing sidebar.
const WIDE_LAYOUT_QUERY = "(min-width: 840px)";

function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => window.matchMedia(query).matches);
  useEffect(() => {
    const list = window.matchMedia(query);
    const update = () => setMatches(list.matches);
    update();
    list.addEventListener("change", update);
    return () => list.removeEventListener("change", update);
  }, [query]);
  return matches;
}

function Sidebar({ onSignedOut }) {
  const [signingOut, setSigningOut] = useState(false);
  async function signOut() {
    if (signingOut) return;
    setSigningOut(true);
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  return html`
    <div class="sidebar-header"><${Brand} /></div>
    <h2 class="sidebar-label">${t.conversations}</h2>
    <div class="sidebar-list">
      <p class="sidebar-empty">${t.conversationsEmpty}</p>
    </div>
    <div class="sidebar-footer">
      <button class="button text with-icon" type="button" onClick=${signOut} disabled=${signingOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>
    </div>`;
}

/** Composer frame; sending arrives with the chat actions stage, so it stays disabled for now. */
function Composer() {
  return html`
    <form class="composer" onSubmit=${(event) => event.preventDefault()}>
      <label class="visually-hidden" for="composer-input">${t.message}</label>
      <textarea id="composer-input" rows="1" placeholder=${t.message} disabled></textarea>
      <button class="icon-button send" type="submit" disabled aria-label=${t.send}>
        ${icon(ICON_SEND)}
      </button>
    </form>`;
}

function ChatEmpty() {
  return html`
    <div class="chat-empty">
      ${icon(ICON_CHAT)}
      <h2>${t.chatEmptyTitle}</h2>
      <p>${t.chatEmptyHint}</p>
    </div>`;
}

/**
 * Chat layout: a standing sidebar next to the chat on wide screens; on narrow screens the sidebar
 * is a modal drawer opened from the top bar, and the chat behind it is inert while it is open.
 */
function Shell({ onSignedOut }) {
  const wide = useMediaQuery(WIDE_LAYOUT_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const modal = !wide;
  const open = modal && drawerOpen;

  useEffect(() => {
    if (wide) setDrawerOpen(false);
  }, [wide]);

  const wasOpen = useRef(false);

  function closeDrawer() {
    setDrawerOpen(false);
  }

  useEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!open && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = open;
    if (!open) return undefined;
    drawer.current?.querySelector("button:not([disabled])")?.focus();
    const onKey = (event) => {
      if (event.key === "Escape") closeDrawer();
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  return html`
    <div class=${open ? "shell drawer-open" : "shell"}>
      <aside id="sidebar" class="sidebar" ref=${drawer} aria-label=${t.conversations}
        role=${modal ? "dialog" : null} aria-modal=${open ? "true" : null}
        inert=${modal && !open}>
        <${Sidebar} onSignedOut=${onSignedOut} />
      </aside>
      ${modal && html`<div class="scrim" aria-hidden="true" onClick=${closeDrawer}></div>`}
      <main class="chat" inert=${open}>
        <header class="top-bar">
          ${modal && html`
            <button class="icon-button" type="button" ref=${menuButton} aria-label=${t.openMenu}
              aria-controls="sidebar" aria-expanded=${open ? "true" : "false"}
              onClick=${() => setDrawerOpen(true)}>
              ${icon(ICON_MENU)}
            </button>`}
          <h1 class="top-title">${t.newChat}</h1>
        </header>
        <section class="messages" aria-label=${t.newChat}>
          <${ChatEmpty} />
        </section>
        <${Composer} />
      </main>
    </div>`;
}

function App() {
  // null while the session check is in flight, so neither screen flashes.
  const [signedIn, setSignedIn] = useState(null);
  useEffect(() => {
    fetch("/api/session", { credentials: "same-origin" })
      .then((r) => r.json())
      .then((body) => setSignedIn(body.signedIn === true))
      .catch(() => setSignedIn(false));
  }, []);
  if (signedIn === null) return null;
  return signedIn
    ? html`<${Shell} onSignedOut=${() => setSignedIn(false)} />`
    : html`<main class="sign-in-page"><${SignIn} onSignedIn=${() => setSignedIn(true)} /></main>`;
}

render(html`<${App} />`, document.getElementById("app"));

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
    // The app's own strings (values/strings.xml), so the chat frame reads like the app.
    menu: "Menu",
    options: "Options",
    conversations: "Conversations",
    searchHint: "Search conversations...",
    tasks: "Tasks",
    remote: "Remote",
    newChat: "New Chat",
    settings: "Settings",
    conversationSearch: "Search",
    systemPrompt: "System Prompt",
    askAgora: "Ask Agora anything...",
    expand: "Expand",
    addAttachment: "Add Attachment",
    selectModel: "Select Model",
    tools: "Tools",
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
    menu: "菜单",
    options: "选项",
    conversations: "对话",
    searchHint: "搜索对话...",
    tasks: "任务",
    remote: "远程",
    newChat: "新对话",
    settings: "设置",
    conversationSearch: "搜索",
    systemPrompt: "系统指令",
    askAgora: "向 Agora 提问...",
    expand: "展开",
    addAttachment: "添加附件",
    selectModel: "选择模型",
    tools: "工具",
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
const ICON_ADD = "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z";
const ICON_MORE_VERT = "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z";
const ICON_SEARCH = "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z";
const ICON_REPEAT = "M7 7h10v3l4-4-4-4v3H5v6h2V7zm10 10H7v-3l-4 4 4 4v-3h12v-6h-2v4z";
const ICON_DEVICES = "M4 6h18V4H4c-1.1 0-2 .9-2 2v11H0v3h14v-3H4V6zm19 2h-6c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h6c.55 0 1-.45 1-1V9c0-.55-.45-1-1-1zm-1 9h-4v-7h4v7z";
const ICON_SETTINGS = "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z";
const ICON_PSYCHOLOGY = "M13 8.57c-.79 0-1.43.64-1.43 1.43s.64 1.43 1.43 1.43 1.43-.64 1.43-1.43-.64-1.43-1.43-1.43zM13 3C9.25 3 6.2 5.94 6.02 9.64L4.1 12.2c-.25.33-.01.8.4.8H6v3c0 1.1.9 2 2 2h1v3h7v-4.68c2.36-1.12 4-3.53 4-6.32 0-3.87-3.13-7-7-7zm3 7c0 .13-.01.26-.02.39l.83.66c.08.06.1.16.05.25l-.8 1.39c-.05.09-.16.12-.24.09l-.99-.4c-.21.16-.43.29-.67.39L14 13.83c-.01.1-.1.17-.2.17h-1.6c-.1 0-.18-.07-.2-.17l-.15-1.06c-.25-.1-.47-.23-.68-.39l-.99.4c-.09.03-.2 0-.25-.09l-.8-1.39c-.05-.08-.03-.19.05-.25l.84-.66c-.01-.13-.02-.26-.02-.39s.02-.27.04-.39l-.85-.66c-.08-.06-.1-.16-.05-.26l.8-1.38c.05-.09.15-.12.24-.09l1 .4c.2-.15.43-.29.67-.39L12 6.17c.02-.1.1-.17.2-.17h1.6c.1 0 .18.07.2.17l.15 1.06c.24.1.46.23.67.39l1-.4c.09-.03.2 0 .24.09l.8 1.38c.05.09.03.2-.05.26l-.85.66c.03.12.04.25.04.39z";
const ICON_LOGOUT = "M17 7l-1.41 1.41L18.17 11H8v2h10.17l-2.58 2.58L17 17l5-5zM4 5h8V3H4c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h8v-2H4V5z";
// res/drawable/expand_all_24px.xml (960 x 960 viewport), the composer's expand icon.
const ICON_EXPAND_ALL = "M480,880L240,640L297,583L480,766L663,583L720,640L480,880ZM298,376L240,320L480,80L720,320L662,376L480,194L298,376Z";
const ICON_ARROW_UPWARD = "M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z";

const icon = (d, viewBox = "0 0 24 24") =>
  html`<svg viewBox=${viewBox} aria-hidden="true"><path d=${d} /></svg>`;

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

/*
 * Chat frame, mirroring the app's chat screen (ChatTopBar, ChatDrawerContent, ChatBottomBar).
 * Controls the browser cannot use yet are shown as in the app but disabled.
 */
// ChatDrawerHost.usesSideBySideDrawer: wider than DRAWER_MAX_WIDTH (360) + CHAT_APP_WIDTH_THRESHOLD (600).
const SIDE_BY_SIDE_QUERY = "(min-width: 960.02px)";

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

function DrawerButton({ className, iconPath, label }) {
  return html`
    <button class=${`drawer-button ${className}`} type="button" disabled>
      ${icon(iconPath)}<span>${label}</span>
    </button>`;
}

/** ChatDrawerContent: title, search, Tasks / Remote, New Chat, the list, Settings. */
function DrawerContent() {
  return html`
    <h2 class="drawer-title">${t.conversations}</h2>
    <div class="drawer-search">
      ${icon(ICON_SEARCH)}
      <input type="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled />
    </div>
    <${DrawerButton} className="tonal group-top" iconPath=${ICON_REPEAT} label=${t.tasks} />
    <${DrawerButton} className="tonal group-bottom" iconPath=${ICON_DEVICES} label=${t.remote} />
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat} />
    <div class="drawer-list" role="list" aria-label=${t.conversations}></div>
    <${DrawerButton} className="tonal settings" iconPath=${ICON_SETTINGS} label=${t.settings} />`;
}

/** AgoraDropdownMenu: 24 dp corners, 48 dp items with an inset capsule highlight. */
function MoreMenu({ onSignOut, onClose }) {
  const menu = useRef(null);
  useEffect(() => {
    menu.current?.querySelector("[role=menuitem]:not([disabled])")?.focus();
    const onPointer = (event) => {
      if (!menu.current?.parentElement?.contains(event.target)) onClose(false);
    };
    const onKey = (event) => {
      if (event.key === "Escape") onClose(true);
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, []);
  return html`
    <div class="dropdown" role="menu" ref=${menu}>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_SEARCH)}<span>${t.conversationSearch}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_PSYCHOLOGY)}<span>${t.systemPrompt}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" onClick=${onSignOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>
    </div>`;
}

/** ChatTopBar in new-chat mode: title capsule (menu + brand) and actions capsule (new chat + more). */
function TopBar({ drawerOpen, onToggleDrawer, menuButton, onSignedOut }) {
  const [menuOpen, setMenuOpen] = useState(false);
  const moreButton = useRef(null);
  async function signOut() {
    setMenuOpen(false);
    await postJson("/api/logout", {}).catch(() => null);
    onSignedOut();
  }
  function closeMenu(restoreFocus) {
    setMenuOpen(false);
    if (restoreFocus) moreButton.current?.focus();
  }
  return html`
    <header class="top-bar">
      <div class="capsule title-capsule">
        <button class="bar-button" type="button" ref=${menuButton} aria-label=${t.menu}
          aria-controls="drawer" aria-expanded=${drawerOpen ? "true" : "false"} onClick=${onToggleDrawer}>
          ${icon(ICON_MENU)}
        </button>
        <h1 class="brand-title">${t.title}</h1>
      </div>
      <div class="capsule actions-capsule">
        <button class="bar-button add" type="button" aria-label=${t.newChat} disabled>
          ${icon(ICON_ADD)}
        </button>
        <div class="menu-anchor">
          <button class="bar-button" type="button" ref=${moreButton} aria-label=${t.options}
            aria-haspopup="menu" aria-expanded=${menuOpen ? "true" : "false"}
            onClick=${() => setMenuOpen(!menuOpen)}>
            ${icon(ICON_MORE_VERT)}
          </button>
          ${menuOpen && html`<${MoreMenu} onSignOut=${signOut} onClose=${closeMenu} />`}
        </div>
      </div>
    </header>`;
}

/** ChatBottomBar: surface card with the text field, the expand button and the controls row. */
function Composer() {
  return html`
    <div class="composer-host">
      <form class="composer" onSubmit=${(event) => event.preventDefault()}>
        <div class="composer-field">
          <textarea rows="1" placeholder=${t.askAgora} aria-label=${t.askAgora} disabled></textarea>
          <button class="expand-button" type="button" aria-label=${t.expand} disabled>
            ${icon(ICON_EXPAND_ALL, "0 0 960 960")}
          </button>
        </div>
        <div class="composer-controls">
          <div class="control-group">
            <button class="control-icon" type="button" aria-label=${t.addAttachment} disabled>
              ${icon(ICON_ADD)}
            </button>
            <button class="model-selector" type="button" disabled>${t.selectModel}</button>
            <button class="control-icon" type="button" aria-label=${t.tools} disabled>
              ${icon(ICON_MORE_VERT)}
            </button>
          </div>
          <button class="send-button" type="submit" aria-label=${t.send} disabled>
            ${icon(ICON_ARROW_UPWARD)}
          </button>
        </div>
      </form>
    </div>`;
}

/**
 * The drawer overlays the chat with a scrim up to 960 px; wider, it sits beside the chat and the
 * chat narrows, as the app's side-by-side drawer. Both start closed and open from the menu button.
 */
function Shell({ onSignedOut }) {
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;

  useEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    if (!modalOpen) return undefined;
    // The dialog itself takes focus while none of its controls is enabled yet.
    (drawer.current?.querySelector("input:not([disabled]), button:not([disabled])") ?? drawer.current)
      ?.focus();
    const onKey = (event) => {
      if (event.key === "Escape") setDrawerOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => setDrawerOpen(false)}></div>
      <main class="chat" inert=${modalOpen}>
        <${TopBar} drawerOpen=${drawerOpen} menuButton=${menuButton} onSignedOut=${onSignedOut}
          onToggleDrawer=${() => setDrawerOpen(!drawerOpen)} />
        <section class="messages" aria-label=${t.newChat}></section>
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

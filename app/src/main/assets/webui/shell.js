import { useEffect, useLayoutEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { t } from "./i18n.js";
import {
  icon, ICON_ADD, ICON_ARROW_UPWARD, ICON_CALL_SPLIT, ICON_EXPAND_ALL, ICON_LOGOUT,
  ICON_MENU, ICON_MORE_VERT, ICON_PSYCHOLOGY, ICON_REPEAT, ICON_SEARCH, ICON_SHARE,
} from "./icons.js";
import { postJson } from "./api.js";
import { sync, useSync } from "./sync.js";
import { MessageList } from "./messages.js";
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

/**
 * A drawer row: 44 dp with 2 dp above and below, a capsule highlight on secondaryContainer when
 * selected, the title in bodyLarge, and an 18 dp slot for the generating spinner or unread dot.
 */
function ConversationRow({ conversation, selected, onSelect }) {
  // resolveDrawerConversationIndicator: generating first; unread only when not selected.
  const indicator = conversation.generating ? "generating"
    : conversation.unread && !selected ? "unread" : null;
  return html`
    <button class=${selected ? "conversation-row selected" : "conversation-row"} type="button"
      role="listitem" aria-current=${selected ? "true" : null} onClick=${() => onSelect(conversation.id)}>
      <span class="conversation-title">${conversation.title}</span>
      <span class="conversation-indicator">
        ${indicator === "generating" && html`<span class="spinner" aria-hidden="true"></span>`}
        ${indicator === "unread" && html`<span class="unread-dot" role="img" aria-label=${t.unreadGeneration}></span>`}
      </span>
    </button>`;
}

/** ChatDrawerContent: title, search, Tasks, New Chat, then the conversation list. */
function DrawerContent({ conversations, openId, onSelect }) {
  return html`
    <h2 class="drawer-title">${t.conversations}</h2>
    <div class="drawer-search">
      ${icon(ICON_SEARCH)}
      <input type="search" placeholder=${t.searchHint} aria-label=${t.searchHint} disabled />
    </div>
    <${DrawerButton} className="tonal tasks" iconPath=${ICON_REPEAT} label=${t.tasks} />
    <${DrawerButton} className="filled new-chat" iconPath=${ICON_ADD} label=${t.newChat} />
    <div class="drawer-list" role="list" aria-label=${t.conversations}>
      ${conversations.map((conversation) => html`
        <${ConversationRow} key=${conversation.id} conversation=${conversation}
          selected=${conversation.id === openId} onSelect=${onSelect} />`)}
    </div>`;
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
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_CALL_SPLIT)}<span>${t.forkConversation}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" disabled>
        ${icon(ICON_SHARE)}<span>${t.share}</span>
      </button>
      <button class="dropdown-item" role="menuitem" type="button" onClick=${onSignOut}>
        ${icon(ICON_LOGOUT)}<span>${t.signOut}</span>
      </button>
    </div>`;
}

/**
 * ChatTopBar: title capsule (menu + brand, or the conversation title at conversationTitleSolo)
 * and actions capsule (new chat + more).
 */
function TopBar({ title, drawerOpen, onToggleDrawer, menuButton, onSignedOut }) {
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
        ${title
          ? html`<h1 class="conversation-bar-title">${title}</h1>`
          : html`<h1 class="brand-title">${t.title}</h1>`}
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
export function Shell({ onSignedOut }) {
  const state = useSync();
  useEffect(() => {
    sync.start(onSignedOut);
    return () => sync.stop();
  }, []);
  const sideBySide = useMediaQuery(SIDE_BY_SIDE_QUERY);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const shell = useRef(null);
  const drawerTarget = useRef(0);
  const drawerAnimation = useRef(null);
  const drawerDrag = useRef(null);
  const dragClick = useRef(null);
  const drawer = useRef(null);
  const menuButton = useRef(null);
  const wasOpen = useRef(false);
  const wasModalOpen = useRef(false);
  const modalOpen = drawerOpen && !sideBySide;
  const reduceMotion = !!state.display?.reduceMotion;
  useLayoutEffect(() => {
    const chat = shell.current.querySelector(".chat");
    const composer = chat.querySelector(".composer-host");
    const capsules = [...chat.querySelectorAll(".top-bar > .capsule")];
    const measure = () => {
      const bounds = chat.getBoundingClientRect();
      const top = Math.max(...capsules.map((node) => node.getBoundingClientRect().bottom)) - bounds.top + 8;
      const bottom = bounds.bottom - composer.getBoundingClientRect().top;
      chat.style.setProperty("--chat-top-inset", `${top}px`);
      chat.style.setProperty("--chat-bottom-inset", `${bottom}px`);
    };
    const geometry = new ResizeObserver(measure);
    [chat, composer, ...capsules].forEach((node) => geometry.observe(node, { box: "border-box" }));
    measure();
    return () => geometry.disconnect();
  }, []);
  // One interpolated progress drives drawer, scrim and desktop inset; freeze its actual value on takeover.
  function freezeDrawer() {
    const progress = Number(getComputedStyle(shell.current).getPropertyValue("--drawer-progress"));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    drawerAnimation.current?.cancel();
    drawerAnimation.current = null;
    return progress;
  }
  function settleDrawer(open) {
    const from = freezeDrawer();
    const to = open ? 1 : 0;
    drawerTarget.current = to;
    setDrawerOpen(from > 0 || to > 0);
    if (reduceMotion || from === to) {
      shell.current.style.setProperty("--drawer-progress", String(to));
      setDrawerOpen(to > 0);
      return;
    }
    const animation = shell.current.animate(
      [{ "--drawer-progress": String(from) }, { "--drawer-progress": String(to) }],
      { duration: 300, easing: "cubic-bezier(0, 0, 0.2, 1)", fill: "forwards" },
    );
    drawerAnimation.current = animation;
    animation.onfinish = () => {
      if (drawerAnimation.current !== animation) return;
      shell.current.style.setProperty("--drawer-progress", String(to));
      animation.cancel();
      drawerAnimation.current = null;
      setDrawerOpen(to > 0);
    };
  }
  function endDrawerDrag(event, cancelled = false) {
    const drag = drawerDrag.current;
    if (!drag || (event && drag.id !== event.pointerId)) return;
    drawerDrag.current = null;
    if (shell.current.hasPointerCapture(drag.id)) shell.current.releasePointerCapture(drag.id);
    if (!drag.accepted) {
      if (drag.interrupted) settleDrawer(drawerTarget.current > 0);
      return;
    }
    dragClick.current = drag.id;
    const progress = freezeDrawer();
    const velocity = event && event.timeStamp - drag.time < 100 ? drag.velocity : 0;
    settleDrawer(cancelled ? drawerTarget.current > 0 : velocity === 0 ? progress >= 0.5 : velocity > 0);
  }
  function beginDrawerDrag(event) {
    dragClick.current = null;
    if (sideBySide || !event.isPrimary || event.button !== 0 ||
        event.target.closest(".detail-sheet-layer, .dropdown, dialog, input, textarea, a, [contenteditable]") ||
        !window.getSelection()?.isCollapsed ||
        (event.pointerType === "mouse" && event.target.closest(".markdown, .user-text"))) return;
    for (let node = event.target; node && node !== shell.current; node = node.parentElement) {
      if (node.scrollWidth > node.clientWidth + 1 && /auto|scroll/.test(getComputedStyle(node).overflowX)) return;
    }
    const interrupted = drawerAnimation.current != null;
    drawerDrag.current = { id: event.pointerId, x: event.clientX, y: event.clientY,
      lastX: event.clientX, time: event.timeStamp, velocity: 0, accepted: false, interrupted, progress: freezeDrawer() };
  }
  function moveDrawerDrag(event) {
    const drag = drawerDrag.current;
    if (!drag || drag.id !== event.pointerId) return;
    const dx = event.clientX - drag.x;
    const dy = event.clientY - drag.y;
    if (!drag.accepted) {
      if (Math.max(Math.abs(dx), Math.abs(dy)) < 8) return;
      if (Math.abs(dy) >= Math.abs(dx)) { endDrawerDrag(event, true); return; }
      drag.accepted = true;
      shell.current.setPointerCapture(event.pointerId);
    }
    const elapsed = event.timeStamp - drag.time;
    if (elapsed > 0) drag.velocity = (event.clientX - drag.lastX) / elapsed;
    drag.lastX = event.clientX;
    drag.time = event.timeStamp;
    const progress = Math.max(0, Math.min(1, drag.progress + dx / drawer.current.clientWidth));
    shell.current.style.setProperty("--drawer-progress", String(progress));
    setDrawerOpen(progress > 0);
    event.preventDefault();
  }
  useLayoutEffect(() => {
    const node = shell.current;
    const stopOwnedTouch = (event) => { if (drawerDrag.current?.accepted) event.preventDefault(); };
    const onResize = () => endDrawerDrag(null, true);
    node.addEventListener("touchmove", stopOwnedTouch, { passive: false });
    window.addEventListener("resize", onResize);
    return () => {
      node.removeEventListener("touchmove", stopOwnedTouch);
      window.removeEventListener("resize", onResize);
    };
  }, [sideBySide, reduceMotion]);
  useEffect(() => () => drawerAnimation.current?.cancel(), []);
  useLayoutEffect(() => {
    endDrawerDrag(null, true);
    settleDrawer(drawerTarget.current > 0);
  }, [sideBySide, reduceMotion]);
  // ChatTopBar falls back to the brand while the title is blank.
  const openTitle = state.conversations.find((c) => c.id === state.openId)?.title?.trim() || null;

  useEffect(() => {
    // The chat is inert until the closed state renders, so focus returns to the menu button here.
    if (!drawerOpen && wasOpen.current) menuButton.current?.focus();
    wasOpen.current = drawerOpen;
    const enteringModal = modalOpen && !wasModalOpen.current;
    wasModalOpen.current = modalOpen;
    if (!modalOpen) return undefined;
    // The dialog itself takes focus while none of its controls is enabled yet.
    if (enteringModal) {
      (drawer.current?.querySelector("input:not([disabled]), button:not([disabled])") ?? drawer.current)
        ?.focus();
    }
    const onKey = (event) => {
      if (event.defaultPrevented || shell.current.querySelector(".detail-sheet-layer, .dropdown")) return;
      if (event.key === "Escape") { event.preventDefault(); settleDrawer(false); }
      if (event.key === "Tab") {
        const controls = [...drawer.current.querySelectorAll("input:not([disabled]), button:not([disabled])")];
        const first = controls[0] ?? drawer.current;
        const last = controls.at(-1) ?? drawer.current;
        if (!drawer.current.contains(document.activeElement) ||
            (event.shiftKey ? document.activeElement === first : document.activeElement === last)) {
          event.preventDefault();
          (event.shiftKey ? last : first).focus();
        }
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [drawerOpen, modalOpen, reduceMotion]);

  const shellClass = ["shell", drawerOpen && "drawer-open", sideBySide ? "side-by-side" : "modal"]
    .filter(Boolean).join(" ");
  return html`
    <div class=${shellClass} ref=${shell} data-blur-effects=${state.display?.blurEffectsEnabled == null ? null : String(state.display.blurEffectsEnabled)}
      data-reduce-motion=${state.display?.reduceMotion == null ? null : String(state.display.reduceMotion)}
      onPointerDown=${beginDrawerDrag} onPointerMove=${moveDrawerDrag}
      onPointerUp=${(event) => endDrawerDrag(event)} onPointerCancel=${(event) => endDrawerDrag(event, true)}
      onLostPointerCapture=${(event) => { if (event.target === shell.current) endDrawerDrag(event, true); }}
      onClickCapture=${(event) => {
        if (event.pointerId === dragClick.current) { event.preventDefault(); event.stopPropagation(); dragClick.current = null; }
      }}>
      <aside id="drawer" class="drawer" ref=${drawer} aria-label=${t.conversations} tabindex="-1"
        role=${sideBySide ? null : "dialog"} aria-modal=${modalOpen ? "true" : null}
        inert=${!drawerOpen}>
        <${DrawerContent} conversations=${state.conversations} openId=${state.openId}
          onSelect=${(id) => { sync.open(id); if (!sideBySide) settleDrawer(false); }} />
      </aside>
      <div class="scrim" aria-hidden="true" onClick=${() => settleDrawer(false)}></div>
      <main class="chat" inert=${modalOpen}>
        <${TopBar} title=${openTitle} drawerOpen=${drawerOpen} menuButton=${menuButton} onSignedOut=${onSignedOut}
          onToggleDrawer=${() => settleDrawer(drawerTarget.current === 0)} />
        <${MessageList} state=${state} label=${openTitle || t.newChat} />
        <${Composer} />
      </main>
    </div>`;
}

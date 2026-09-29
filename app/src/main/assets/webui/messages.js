// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// Only rows near the screen are watched, so the phone sends just those bodies.
import { useEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { Markdown } from "./markdown.js";
import { icon, ICON_BUILD, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT, ICON_IMAGE, ICON_NEUROLOGY } from "./icons.js";
import { sync } from "./sync.js";

// Rows within one screen above or below stay watched, so scrolling rarely meets a blank row.
const WATCH_MARGIN = "100% 0px";

/** UserMessageBubble: plain text in a primaryContainer bubble, 54-300 dp wide. */
function UserBubble({ message }) {
  return html`
    <div class="user-row">
      <div class="user-bubble"><div class="user-text">${message.text.markdown}</div></div>
    </div>`;
}

function CardIcon({ kind }) {
  if (kind === "LOADING") return html`<span class="card-spinner" aria-hidden="true"></span>`;
  const path = kind === "TOOL" ? ICON_BUILD : kind === "IMAGE" ? ICON_IMAGE : ICON_NEUROLOGY;
  return icon(path, kind === "THINKING" ? "0 0 960 960" : "0 0 24 24");
}

function liveTitle(group, strings, elapsed) {
  if (group.liveBaseMs == null || !strings) return group.title;
  const seconds = Math.floor((group.liveBaseMs + elapsed) / 1000);
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const remaining = seconds % 60;
  const template = hours ? strings.hours : seconds >= 60 ? strings.minutes : strings.seconds;
  const args = hours ? [hours, minutes, remaining] : seconds >= 60 ? [minutes, remaining] : [seconds];
  let index = 0;
  return template.replace(/%(?:(\d+)\$)?d/g, (_, position) => args[position ? Number(position) - 1 : index++] ?? "");
}

function useCardTitle(group, strings) {
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    setElapsed(0);
    if (group.liveBaseMs == null) return undefined;
    const started = Date.now();
    const timer = setInterval(() => setElapsed(Date.now() - started), 1000);
    return () => clearInterval(timer);
  }, [group.liveBaseMs]);
  return liveTitle(group, strings, elapsed);
}

function createGroupExpansionController() {
  const states = new Map();
  const collapsedImageBoundaryKeys = new Set();
  return {
    reset() {
      states.clear();
      collapsedImageBoundaryKeys.clear();
    },
    shouldCollapseForImageBoundary(key, hasImageBoundary) {
      return hasImageBoundary && !collapsedImageBoundaryKeys.has(key);
    },
    claimImageBoundaryCollapse(key, hasImageBoundary) {
      if (!hasImageBoundary || collapsedImageBoundaryKeys.has(key)) return false;
      collapsedImageBoundaryKeys.add(key);
      states.set(key, "INACTIVE");
      return true;
    },
    shouldPresentInitiallyExpanded(key, isActive, enabled) {
      return enabled && isActive && !states.has(key);
    },
    update(key, isActive, enabled) {
      if (collapsedImageBoundaryKeys.has(key)) return null;
      if (!enabled) {
        if (isActive) states.delete(key);
        else states.set(key, "INACTIVE");
        return null;
      }
      switch (states.get(key)) {
        case undefined:
        case "INACTIVE":
          states.set(key, isActive ? "ACTIVE" : "INACTIVE");
          return isActive ? "EXPAND" : null;
        case "ACTIVE":
          if (!isActive) {
            states.set(key, "INACTIVE");
            return "COLLAPSE";
          }
          return null;
        default:
          return null;
      }
    },
  };
}

/** The preview rows stay inert until the shared segment detail sheet is implemented. */
function InfoItem({ item, compact = false }) {
  const text = item.type === "thought" ? item.content?.markdown?.replace(/\n/g, " ")
    : item.type === "transcription" ? item.content?.markdown?.replace(/\n/g, " ") || "Image transcription is empty."
      : item.summary;
  return html`
    <div class=${compact ? "info-item compact-item" : "info-item timeline-item"}>
      ${!compact && html`<span class="info-item-icon">
        <${CardIcon} kind=${item.type === "tool" ? "TOOL" : item.type === "transcription" ? "IMAGE" : "THINKING"} />
      </span>`}
      <div class="info-item-text">
        <span class="info-item-title">${item.title}</span>
        ${text && html`<span class="info-item-summary">${text}</span>`}
      </div>
      ${!compact && html`<span class="info-item-arrow">${icon(ICON_CHEVRON_RIGHT)}</span>`}
    </div>`;
}

/** Browser-local expansion memory survives payload eviction and off-screen row hydration. */
function InfoGroup({ group, messageId, display, expansion, expansionController, opensSheet, appearances, streaming }) {
  const key = `${messageId}:${group.key}`;
  const initiallyActive = !!(display?.autoExpandActiveGroup && group.autoExpansionActive);
  const imageBoundary = group.imageDetailIndex != null;
  const autoExpandEnabled = !!display?.autoExpandActiveGroup;
  const initiallyAutoExpanded = expansionController.shouldPresentInitiallyExpanded(
    key,
    initiallyActive,
    autoExpandEnabled,
  );
  const [expanded, setExpanded] = useState(() =>
    expansionController.shouldCollapseForImageBoundary(key, imageBoundary)
      ? false
      : expansion.get(key) ?? initiallyAutoExpanded,
  );
  const surface = useRef(null);
  const titleNode = useRef(null);
  const title = useCardTitle(group, display?.liveThinking);
  const [widths, setWidths] = useState(null);
  const firstAppearance = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  useEffect(() => {
    const parent = surface.current?.parentElement;
    if (!parent || !titleNode.current) return undefined;
    const measure = () => {
      const text = titleNode.current;
      const canvas = document.createElement("canvas");
      const context = canvas.getContext("2d");
      context.font = getComputedStyle(text).font;
      setWidths({
        collapsed: Math.min(parent.clientWidth, Math.ceil(context.measureText(text.textContent).width) + 80),
        expanded: parent.clientWidth,
      });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(parent);
    return () => observer.disconnect();
  }, [title]);
  useEffect(() => {
    if (expansionController.shouldCollapseForImageBoundary(key, imageBoundary)) {
      if (expansionController.claimImageBoundaryCollapse(key, imageBoundary)) {
        expansion.set(key, false);
        setExpanded(false);
      }
      return;
    }
    const action = expansionController.update(key, initiallyActive, autoExpandEnabled);
    if (action === null) return;
    const nextExpanded = action === "EXPAND";
    expansion.set(key, nextExpanded);
    setExpanded(nextExpanded);
  }, [key, initiallyActive, autoExpandEnabled, imageBoundary, expansionController]);
  function toggle() {
    expansion.set(key, !expanded);
    setExpanded(!expanded);
  }
  const targetExpanded = expanded && !opensSheet;
  return html`
    <div class=${`info-group ${targetExpanded ? "expanded" : ""} ${opensSheet ? "sheet-mode" : ""} ${group.precededByAnswer ? "after-answer" : ""} ${group.items.some((item) => item.type === "tool") ? "has-tool" : ""} ${firstAppearance.current ? "entering" : ""}`}
      data-group=${group.key}
      style=${widths == null ? null : { "--card-content-width": `${targetExpanded ? widths.expanded : widths.collapsed}px`, "--card-expanded-width": `${widths.expanded}px` }}>
      <div class="info-group-surface" ref=${surface}
        style=${widths == null ? null : { width: `${targetExpanded ? widths.expanded : widths.collapsed}px` }}>
        <button class="info-group-header" type="button" aria-expanded=${opensSheet ? null : expanded}
          onClick=${opensSheet ? undefined : toggle} disabled=${opensSheet}>
          <span class="info-header-icon"><${CardIcon} kind=${group.icon} /></span>
          <span class="info-header-title" ref=${titleNode}>${title}</span>
          <span class="info-disclosure">${icon(ICON_CHEVRON_DOWN)}</span>
        </button>
        <div class="info-group-reveal" inert=${!targetExpanded}>
          <div class="info-group-items">
            ${group.items.map((item) => html`<${InfoItem} key=${item.detailIndex} item=${item} compact />`)}
          </div>
        </div>
      </div>
    </div>`;
}

function InfoCard({ block, messageId, appearances, streaming }) {
  const key = `${messageId}:card:${block.item.detailIndex}`;
  const entering = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  return html`
    <div class=${`info-card ${block.groupPosition.toLowerCase()} ${block.precededByAnswer ? "after-answer" : ""} ${block.item.type === "tool" ? "has-tool" : ""} ${entering.current ? "entering" : ""}`}
      data-detail=${block.item.detailIndex}>
      <div class="info-card-surface"><${InfoItem} item=${block.item} /></div>
    </div>`;
}

/** AssistantMessageContent reads the same presentation decisions as the phone. */
function ModelMessage({ message, wrap, display, expansion, expansionController, appearances, streaming }) {
  const presentation = message.presentation;
  const error = message.participant === "ERROR";
  let body = null;
  if (presentation?.useTimeline) {
    body = presentation.blocks.map((block) => {
      switch (block.type) {
        case "answer":
          return html`<div class="answer-block" key=${`a${block.index}`}>
            <${Markdown} text=${block.text} wrap=${wrap} />
          </div>`;
        case "group":
          return html`<${InfoGroup} key=${block.group.key} group=${block.group}
            messageId=${message.id} display=${display} expansion=${expansion}
            expansionController=${expansionController}
            appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`;
        case "card":
          return html`<${InfoCard} key=${`card:${block.item.detailIndex}`} block=${block}
            messageId=${message.id} appearances=${appearances} streaming=${streaming} />`;
        default:
          return null;
      }
    });
  } else {
    body = html`
      ${presentation?.compact && html`<${InfoGroup} group=${presentation.compact} messageId=${message.id}
        display=${display} expansion=${expansion} expansionController=${expansionController}
        appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`}
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} wrap=${wrap} />`}`;
  }
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}</div>`;
}

function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming }) {
  const message = body ?? null;
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER"
      ? html`<${UserBubble} message=${message} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} display=${display}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming} />`;
  }
  return html`<div class="message-row" data-id=${entry.id}>${content}</div>`;
}

export function MessageList({ state, label }) {
  const scroller = useRef(null);
  const visible = useRef(new Set());
  const pinned = useRef(false);
  const expansion = useRef(new Map());
  const expansionController = useRef(createGroupExpansionController());
  const appearances = useRef(new Set());
  const previousOpenId = useRef(state.openId);
  const ids = state.path.map((entry) => entry.id).join(",");
  const wrap = state.display?.autoWrapCodeBlocks ?? true;

  useEffect(() => {
    if (previousOpenId.current === state.openId) return;
    previousOpenId.current = state.openId;
    expansion.current.clear();
    expansionController.current.reset();
    appearances.current.clear();
  }, [state.openId]);

  useEffect(() => {
    const root = scroller.current;
    if (!root) return undefined;
    visible.current = new Set();
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        const id = entry.target.dataset.id;
        if (entry.isIntersecting) visible.current.add(id);
        else visible.current.delete(id);
      }
      sync.watch([...visible.current]);
    }, { root, rootMargin: WATCH_MARGIN });
    root.querySelectorAll(".message-row").forEach((row) => observer.observe(row));
    return () => observer.disconnect();
  }, [ids]);

  // The app opens a conversation at its newest message. Row bodies arrive after the path, so
  // the list stays at the bottom while they load, until the reader scrolls.
  useEffect(() => {
    pinned.current = true;
  }, [state.openId]);
  useEffect(() => {
    const root = scroller.current;
    const column = root?.firstElementChild;
    if (!root || !column) return undefined;
    const release = () => { pinned.current = false; };
    const follow = new ResizeObserver(() => {
      if (pinned.current) root.scrollTop = root.scrollHeight;
    });
    follow.observe(column);
    const inputs = ["wheel", "touchstart", "keydown", "pointerdown"];
    inputs.forEach((type) => root.addEventListener(type, release, { passive: true }));
    return () => {
      follow.disconnect();
      inputs.forEach((type) => root.removeEventListener(type, release));
    };
  }, []);

  return html`
    <section class="messages" ref=${scroller} aria-label=${label}>
      <div class="message-column">
        ${state.path.map((entry) => html`
          <${Row} key=${entry.id} entry=${entry} wrap=${wrap} display=${state.display}
            expansion=${expansion.current} expansionController=${expansionController.current}
            appearances=${appearances.current}
            streaming=${state.streaming?.id === entry.id}
            body=${state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} />`)}
      </div>
    </section>`;
}

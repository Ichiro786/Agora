// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// Only rows near the screen are watched, so the phone sends just those bodies.
import { useEffect, useRef, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { Markdown } from "./markdown.js";
import { icon, ICON_BUILD, ICON_CHEVRON_DOWN, ICON_CHEVRON_RIGHT, ICON_IMAGE, ICON_NEUROLOGY } from "./icons.js";
import { sync } from "./sync.js";

// Rows within one screen above or below stay watched, so scrolling rarely meets a blank row.
const WATCH_MARGIN = "100% 0px";

const SHEET_BACK_PATH = "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z";
const SHEET_CLOSE_PATH = "M18.3 5.71 12 12l6.3 6.29-1.41 1.42L10.59 13.41 4.29 19.71 2.88 18.3 9.17 12 2.88 5.7 4.29 4.29 10.59 10.59 16.89 4.29z";

function groupForMessage(message, groupKey) {
  const presentation = message?.presentation;
  if (!presentation) return null;
  if (presentation.compact?.key === groupKey) return presentation.compact;
  return presentation.blocks?.find((block) => block.type === "group" && block.group.key === groupKey)?.group ?? null;
}

function sheetItemsForMessage(message, groupKey) {
  if (!message?.presentation) return [];
  if (groupKey != null) return groupForMessage(message, groupKey)?.items ?? [];
  return message.presentation.blocks
    ?.filter((block) => block.type === "card")
    .map((block) => block.item) ?? [];
}

function canOpenSheetItem(item) {
  return item?.type === "thought" || item?.type === "transcription";
}

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

/** A row can activate details only when it has a Thought or Transcription target. */
function InfoItem({ item, compact = false, onClick }) {
  const text = item.type === "thought" ? item.content?.markdown?.replace(/\n/g, " ")
    : item.type === "transcription" ? item.content?.markdown?.replace(/\n/g, " ") || "Image transcription is empty."
      : item.summary;
  const interactive = typeof onClick === "function";
  const Tag = interactive ? "button" : "div";
  return html`
    <${Tag} class=${`${compact ? "info-item compact-item" : "info-item timeline-item"} ${interactive ? "sheet-item" : ""}`}
      type=${interactive ? "button" : null}
      onClick=${onClick}>
      ${!compact && html`<span class="info-item-icon">
        <${CardIcon} kind=${item.type === "tool" ? "TOOL" : item.type === "transcription" ? "IMAGE" : "THINKING"} />
      </span>`}
      <div class="info-item-text">
        <span class="info-item-title">${item.title}</span>
        ${text && html`<span class="info-item-summary">${text}</span>`}
      </div>
      ${!compact && interactive && html`<span class="info-item-arrow">${icon(ICON_CHEVRON_RIGHT)}</span>`}
    </${Tag}>`;
}

/** Browser-local expansion memory survives payload eviction and off-screen row hydration. */
function InfoGroup({ group, messageId, display, expansion, expansionController, opensSheet, onOpenSheet, onOpenDetail, appearances, streaming }) {
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
          aria-haspopup=${opensSheet ? "dialog" : null}
          onClick=${opensSheet ? () => onOpenSheet?.(messageId, group.key) : toggle}>
          <span class="info-header-icon"><${CardIcon} kind=${group.icon} /></span>
          <span class="info-header-title" ref=${titleNode}>${title}</span>
          <span class="info-disclosure">${icon(ICON_CHEVRON_DOWN)}</span>
        </button>
        <div class="info-group-reveal" inert=${!targetExpanded}>
          <div class="info-group-items">
            ${group.items.map((item) => html`<${InfoItem} key=${item.detailIndex} item=${item} compact
              onClick=${canOpenSheetItem(item)
                ? () => onOpenDetail(messageId, group.key, item.detailIndex) : undefined} />`)}
          </div>
        </div>
      </div>
    </div>`;
}

function InfoCard({ block, messageId, appearances, streaming, onOpenDetail }) {
  const key = `${messageId}:card:${block.item.detailIndex}`;
  const entering = useRef(!appearances.has(key) && streaming);
  useEffect(() => { appearances.add(key); }, [key]);
  return html`
    <div class=${`info-card ${block.groupPosition.toLowerCase()} ${block.precededByAnswer ? "after-answer" : ""} ${block.item.type === "tool" ? "has-tool" : ""} ${entering.current ? "entering" : ""}`}
      data-detail=${block.item.detailIndex}>
      <div class="info-card-surface"><${InfoItem} item=${block.item}
        onClick=${canOpenSheetItem(block.item)
          ? () => onOpenDetail(messageId, null, block.item.detailIndex) : undefined} /></div>
    </div>`;
}

function DetailSheet({ group, items, page, detailIndex, selectedItem, display, wrap, onSelectItem, onBack, onClose }) {
  const [expanded, setExpanded] = useState(false);
  const closeButton = useRef(null);
  const restoreFocus = useRef(null);
  const sheet = useRef(null);
  const dragStart = useRef(null);
  const suppressHandleClick = useRef(false);
  const backAction = useRef(onBack);
  backAction.current = onBack;
  const groupTitle = useCardTitle(group ?? { title: "", liveBaseMs: null }, display?.liveThinking);
  const title = page === "detail" ? selectedItem?.title : groupTitle;
  useEffect(() => {
    restoreFocus.current = document.activeElement;
    closeButton.current?.focus();
    const onKey = (event) => {
      if (event.key === "Escape") {
        event.preventDefault();
        event.stopPropagation();
        backAction.current();
      } else if (event.key === "Tab") {
        const controls = [...sheet.current?.querySelectorAll("button:not([disabled])") ?? []];
        if (!controls.length) return;
        const target = event.shiftKey ? controls.at(-1) : controls[0];
        const atEdge = event.shiftKey ? document.activeElement === controls[0]
          : document.activeElement === controls.at(-1);
        if (atEdge || !sheet.current?.contains(document.activeElement)) {
          event.preventDefault();
          target.focus();
        }
      }
    };
    document.addEventListener("keydown", onKey, true);
    return () => {
      document.removeEventListener("keydown", onKey, true);
      if (restoreFocus.current?.isConnected) restoreFocus.current.focus();
    };
  }, []);
  useEffect(() => {
    sheet.current?.querySelector(".detail-sheet-content")?.scrollTo(0, 0);
  }, [page, detailIndex]);
  function beginDrag(event) {
    dragStart.current = event.clientY;
    event.currentTarget.setPointerCapture?.(event.pointerId);
  }
  function endDrag(event) {
    if (dragStart.current == null) return;
    const delta = event.clientY - dragStart.current;
    dragStart.current = null;
    if (Math.abs(delta) > 24) {
      suppressHandleClick.current = true;
      if (delta < 0) setExpanded(true);
      else setExpanded(false);
    }
  }
  function onWheel(event) {
    const content = sheet.current?.querySelector(".detail-sheet-content");
    if (event.deltaY < 0 && content?.scrollTop === 0 && expanded) setExpanded(false);
    else if (event.deltaY > 0 && !expanded) setExpanded(true);
  }
  return html`
    <div class="detail-sheet-layer">
      <button class="detail-sheet-backdrop" type="button" aria-label="Close" onClick=${onClose}></button>
      <section class=${`detail-sheet ${expanded ? "expanded" : ""}`} role="dialog" aria-modal="true"
        aria-label=${title || "Message details"} ref=${sheet}>
        <button class="detail-sheet-handle" type="button"
          aria-label=${expanded ? "Collapse details" : "Expand details"}
          onPointerDown=${beginDrag} onPointerUp=${endDrag} onPointerCancel=${() => { dragStart.current = null; }}
          onClick=${() => {
            if (suppressHandleClick.current) suppressHandleClick.current = false;
            else setExpanded((value) => !value);
          }}>
          <span></span>
        </button>
        <header class="detail-sheet-header">
          ${page === "detail" && group && html`<button class="detail-sheet-icon detail-sheet-back" type="button" aria-label="Back" onClick=${onBack}>
            ${icon(SHEET_BACK_PATH)}
          </button>`}
          <h2>${title || "Message details"}</h2>
          <button class="detail-sheet-icon" type="button" aria-label="Close" ref=${closeButton} onClick=${onClose}>
            ${icon(SHEET_CLOSE_PATH)}
          </button>
        </header>
        <div class="detail-sheet-content" onWheel=${onWheel}>
          <div class=${`detail-sheet-page ${page === "list" ? "list-page" : "detail-page"}`} key=${page}>
            ${page === "list" ? html`
              <div class="detail-sheet-list">
                ${items.map((item, index) => html`
                  <div class=${`detail-sheet-list-row ${index === 0 ? "first" : index === items.length - 1 ? "last" : "middle"}`} key=${item.detailIndex}>
                    <${InfoItem} item=${item}
                      onClick=${canOpenSheetItem(item) ? () => onSelectItem(item.detailIndex) : undefined} />
                  </div>`)}
              </div>` : selectedItem && html`
              <div class=${`detail-sheet-markdown ${selectedItem.streaming ? "streaming" : ""}`}>
                ${selectedItem.type === "transcription" && !selectedItem.content?.markdown
                  ? html`<p class="detail-sheet-empty">Image transcription is empty.</p>`
                  : html`<${Markdown} text=${selectedItem.content} variant="thought" wrap=${wrap} />`}
              </div>`}
          </div>
        </div>
      </section>
    </div>`;
}

/** AssistantMessageContent reads the same presentation decisions as the phone. */
function ModelMessage({ message, wrap, display, expansion, expansionController, appearances, streaming, onOpenSheet, onOpenDetail }) {
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
            expansionController=${expansionController} onOpenSheet=${onOpenSheet}
            onOpenDetail=${onOpenDetail}
            appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`;
        case "card":
          return html`<${InfoCard} key=${`card:${block.item.detailIndex}`} block=${block}
            messageId=${message.id} appearances=${appearances} streaming=${streaming}
            onOpenDetail=${onOpenDetail} />`;
        default:
          return null;
      }
    });
  } else {
    body = html`
      ${presentation?.compact && html`<${InfoGroup} group=${presentation.compact} messageId=${message.id}
        display=${display} expansion=${expansion} expansionController=${expansionController}
        onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail}
        appearances=${appearances} streaming=${streaming} opensSheet=${presentation.useThinkingSheet} />`}
      ${presentation?.answer && html`<${Markdown} text=${presentation.answer} wrap=${wrap} />`}`;
  }
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}</div>`;
}

function Row({ entry, body, wrap, display, expansion, expansionController, appearances, streaming, onOpenSheet, onOpenDetail }) {
  const message = body ?? null;
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER"
      ? html`<${UserBubble} message=${message} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} display=${display}
          expansion=${expansion} expansionController=${expansionController}
          appearances=${appearances} streaming=${streaming}
          onOpenSheet=${onOpenSheet} onOpenDetail=${onOpenDetail} />`;
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
  const [sheet, setSheet] = useState(null);
  const sheetWatchedId = sheet?.conversationId === state.openId ? sheet.messageId : null;
  const watchedSheet = useRef(sheetWatchedId);
  watchedSheet.current = sheetWatchedId;
  const ids = state.path.map((entry) => entry.id).join(",");
  const wrap = state.display?.autoWrapCodeBlocks ?? true;
  const selectedOnPath = sheet && state.openId === sheet.conversationId &&
    state.path.some((entry) => entry.id === sheet.messageId);
  const sheetMessage = selectedOnPath
    ? state.streaming?.id === sheet.messageId ? state.streaming : state.bodies.get(sheet.messageId)
    : null;
  const sheetGroup = sheetMessage && sheet?.groupKey != null
    ? groupForMessage(sheetMessage, sheet.groupKey) : null;
  const sheetItems = sheetMessage ? sheetItemsForMessage(sheetMessage, sheet.groupKey) : [];
  const selectedItem = sheetItems.find((item) => item.detailIndex === sheet?.detailIndex);
  const validSheet = selectedOnPath && (!sheetMessage ||
    ((sheet.groupKey == null || sheetGroup) &&
      (sheet.page !== "detail" || canOpenSheetItem(selectedItem))));

  function openSheet(messageId, groupKey) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "list", detailIndex: null });
  }
  function openDetail(messageId, groupKey, detailIndex) {
    setSheet({ conversationId: state.openId, messageId, groupKey, page: "detail", detailIndex });
  }
  useEffect(() => {
    if (!sheet) return;
    if (sheet.conversationId !== state.openId || state.openStatus === "deleted" ||
        state.openStatus === "failed" ||
        (state.openStatus === "ready" && !state.path.some((entry) => entry.id === sheet.messageId)) ||
        (sheetMessage && !validSheet)) setSheet(null);
  }, [sheet, state.openId, state.openStatus, ids, sheetMessage, validSheet]);

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
      const watched = new Set(visible.current);
      if (watchedSheet.current) watched.add(watchedSheet.current);
      sync.watch([...watched]);
    }, { root, rootMargin: WATCH_MARGIN });
    root.querySelectorAll(".message-row").forEach((row) => observer.observe(row));
    return () => observer.disconnect();
  }, [ids]);

  useEffect(() => {
    const watched = new Set(visible.current);
    if (sheetWatchedId) watched.add(sheetWatchedId);
    sync.watch([...watched]);
  }, [sheetWatchedId]);

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
            onOpenSheet=${openSheet} onOpenDetail=${openDetail}
            streaming=${state.streaming?.id === entry.id}
            body=${state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} />`)}
      </div>
    </section>
    ${sheet && validSheet && html`<${DetailSheet} key=${`${sheet.conversationId}:${sheet.messageId}:${sheet.groupKey ?? "direct"}`}
      group=${sheetGroup} items=${sheetItems} page=${sheet.page} detailIndex=${sheet.detailIndex}
      selectedItem=${selectedItem} display=${state.display} wrap=${wrap}
      onSelectItem=${(detailIndex) => setSheet({ ...sheet, page: "detail", detailIndex })}
      onBack=${() => sheet.page === "detail" && sheetGroup
        ? setSheet({ ...sheet, page: "list", detailIndex: null }) : setSheet(null)}
      onClose=${() => setSheet(null)} />`}`;
}

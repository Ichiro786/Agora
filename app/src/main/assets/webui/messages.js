// The open conversation's selected branch, drawn as the app's MessageList and MessageItem.
// Only rows near the screen are watched, so the phone sends just those bodies.
import { useEffect, useRef } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { Markdown } from "./markdown.js";
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

/** AssistantMessageContent; info cards and terminal bars follow in later steps. */
function ModelMessage({ message, wrap }) {
  const presentation = message.presentation;
  const error = message.participant === "ERROR";
  let body = null;
  if (presentation?.useTimeline) {
    body = presentation.blocks.map((block) => block.type === "answer"
      ? html`<div class="answer-block" key=${`a${block.index}`}>
          <${Markdown} text=${block.text} wrap=${wrap} />
        </div>`
      : null);
  } else if (presentation?.answer) {
    body = html`<${Markdown} text=${presentation.answer} wrap=${wrap} />`;
  }
  return html`<div class=${error ? "model-message error" : "model-message"}>${body}</div>`;
}

function Row({ entry, body, wrap }) {
  const message = body ?? null;
  let content = html`<div class="row-placeholder"></div>`;
  if (message) {
    content = message.participant === "USER"
      ? html`<${UserBubble} message=${message} />`
      : html`<${ModelMessage} message=${message} wrap=${wrap} />`;
  }
  return html`<div class="message-row" data-id=${entry.id}>${content}</div>`;
}

export function MessageList({ state, label }) {
  const scroller = useRef(null);
  const visible = useRef(new Set());
  const pinned = useRef(false);
  const ids = state.path.map((entry) => entry.id).join(",");
  const wrap = state.display?.autoWrapCodeBlocks ?? true;

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
          <${Row} key=${entry.id} entry=${entry} wrap=${wrap}
            body=${state.streaming?.id === entry.id ? state.streaming : state.bodies.get(entry.id)} />`)}
      </div>
    </section>`;
}

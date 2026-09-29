// Agora WebUI entry: the session check, then sign-in or the chat frame.
import { render } from "./vendor/preact.mjs";
import { useEffect, useState } from "./vendor/preact-hooks.mjs";
import { html } from "./html.js";
import { SignIn } from "./signin.js";
import { Shell } from "./shell.js";

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

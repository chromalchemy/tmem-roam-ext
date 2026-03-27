# Scittle nREPL in Roam Research: Architecture & Implementation Notes

A live ClojureScript REPL inside Roam Research's Electron app, providing editor-connected interactive development against the Roam Alpha API.

## The Problem

Roam Research has a built-in SCI (Small Clojure Interpreter) runtime that evaluates ClojureScript from `roam/render` code blocks. This is powerful — it gives extensions access to Reagent, Datascript, and the full Roam CLJS API — but the development experience is painful:

- **No REPL connection.** Roam's SCI is a closed sandbox. There's no way to connect an external editor and evaluate forms interactively.
- **No hot reload.** Every change requires rebuilding the extension, reloading in Roam, and navigating back to the test page.
- **Roam is an Electron app.** Browser extensions (like Epupp/Browser Jack-In, which inject Scittle into web pages) don't work in Electron.

## The Solution

Inject [Scittle](https://github.com/babashka/scittle) — a browser-native SCI-based ClojureScript interpreter with nREPL support — into Roam's Electron process via the extension's `onload()` lifecycle. A local [Babashka](https://github.com/babashka/babashka) process acts as an nREPL↔WebSocket relay, letting any editor with nREPL support (Calva, CIDER, Conjure) evaluate ClojureScript directly inside the running Roam instance.

## Architecture

```
┌─── Editor (Calva / CIDER / Conjure) ────┐
│  nREPL client → localhost:1339           │
└──────────────────┬───────────────────────┘
                   │ nREPL protocol (TCP)
                   ▼
┌─── bb roam-nrepl (Babashka process) ─────┐
│  sci.nrepl.browser-server                │
│  nREPL :1339  ←→  WebSocket :1340        │
│  Forwards eval to browser, returns result│
└──────────────────┬───────────────────────┘
                   │ WebSocket (ws://localhost:1340)
                   ▼
┌─── Roam Electron ────────────────────────┐
│                                          │
│  ┌─── Hidden iframe ──────────────────┐  │
│  │  Scittle SCI (isolated globals)    │  │
│  │  scittle.nrepl.js (WS client)      │  │
│  │  scittle.promesa.js                │  │
│  │  scittle.pprint.js                 │  │
│  │  roam.api namespace (auto-loaded)  │  │
│  │                                    │  │
│  │  window.roamAlphaAPI ──────────────┼──┼── bridged from parent
│  └────────────────────────────────────┘  │
│                                          │
│  Roam's own SCI (separate, untouched)    │
│  Roam's compiled CLJS ($APP globals)     │
│  window.roamAlphaAPI (the real one)      │
└──────────────────────────────────────────┘
```

### Component Roles

| Component | Role |
|---|---|
| **bb roam-nrepl** | Babashka task that starts `sci.nrepl.browser-server`. Exposes nREPL on TCP `:1339` for editors, WebSocket on `:1340` for the browser. Stateless relay — just forwards messages. |
| **scittle-nrepl.js** | Extension module. On `onload()`, creates a hidden iframe, writes an HTML document into it via `document.write()`, loads Scittle from CDN, bridges `roamAlphaAPI`, patches WebSocket URLs. On `onunload()`, removes the iframe. |
| **Hidden iframe** | Isolated JavaScript execution context. Contains Scittle's SCI interpreter, the nREPL WebSocket client, and the `roam.api` wrapper namespace. All nREPL eval happens here. |
| **roam.api namespace** | Idiomatic CLJS wrappers auto-loaded into every nREPL session. Wraps `js/window.roamAlphaAPI` calls with proper `clj->js` / `js->clj` conversions. |

## Key Implementation Challenges

### 1. Global Namespace Collision

**Problem:** Both Scittle and Roam are Google Closure compiled ClojureScript. They share global namespace objects (`$APP`, `cljs.core`, `goog.*`). Loading Scittle's `scittle.js` directly into Roam's window **destroys Roam's runtime** — functions like `$APP.p` (compiled `cljs.core` functions) get overwritten, causing cascading `TypeError` failures across the entire app.

**Solution:** Load Scittle inside a hidden `<iframe>`. The iframe has its own JavaScript global scope, so Scittle's compiled CLJS namespaces exist in complete isolation. Roam's `$APP` globals are untouched.

**Why about:blank + document.write():** Several iframe strategies were attempted:

| Approach | Result |
|---|---|
| Blob URL iframe with inline scripts | Inline `<script>` tags blocked by inherited CSP |
| about:blank + `contentWindow` property setting | Window object gets replaced when scripts start loading; patches discarded |
| about:blank + `document.write()` | ✅ Inline scripts execute as part of HTML parsing. Window is stable. No CSP issues. |

The `document.write()` approach works because writing to an `about:blank` document triggers the HTML parser, which executes inline scripts synchronously as they're encountered — before any external `<script src="...">` tags load. This guarantees the bridge and monkey-patches are in place when Scittle scripts execute.

### 2. WebSocket URL Rewriting

**Problem:** Scittle's `scittle.nrepl.js` constructs its WebSocket URL using `window.location.hostname`. In the iframe context (written via `document.write()`), this resolves to the parent page's hostname — `roamresearch.com`. The resulting URL `ws://roamresearch.com:1340/_nrepl` fails for two reasons:

1. There's no nREPL relay at `roamresearch.com:1340`
2. Chromium blocks `ws://` (insecure) connections from an `https://` page (Mixed Content)

**Solution:** Monkey-patch `window.WebSocket` inside the iframe *before* `scittle.nrepl.js` loads. Any WebSocket URL containing `_nrepl` has its host rewritten to `localhost`:

```javascript
// "ws://roamresearch.com:1340/_nrepl"
//   → parse out scheme, find host:port boundary
//   → "ws://localhost:1340/_nrepl"
```

Chromium treats `localhost` as a secure context, so `ws://localhost:*` is allowed from HTTPS pages. The monkey-patch uses string parsing (not regex) because webpack's template literal minification can mangle regex patterns embedded in multi-layer string contexts.

### 3. roamAlphaAPI Bridge

**Problem:** The Scittle iframe is a separate browsing context. Code evaluated via nREPL can access the iframe's `window`, but needs to reach the *parent* window's `roamAlphaAPI` to interact with Roam.

**Solution:** A single line in the iframe's setup script:

```javascript
window.roamAlphaAPI = parent.window.roamAlphaAPI;
```

Because the iframe is created by Roam's own page (same origin), `parent.window` is directly accessible. The assignment makes `js/window.roamAlphaAPI` in Scittle CLJS point to the real Roam API object. All API methods (queries, block CRUD, UI navigation) work transparently — they execute in Roam's context because the underlying JS objects live on the parent window.

### 4. Script Load Ordering

Scittle scripts must load in a specific order:

1. **Inline setup script** — bridge, WebSocket patch, config vars
2. **scittle.js** — SCI core interpreter (must be first of the Scittle scripts)
3. **scittle.promesa.js** — Promise support plugin
4. **scittle.pprint.js** — Pretty-printing plugin
5. **scittle.nrepl.js** — nREPL WebSocket client (must be last — connects on load)
6. **`<script type="application/x-scittle">`** — The `roam.api` namespace, evaluated by Scittle

Using `document.write()` with sequential `<script src="...">` tags guarantees this ordering: the HTML parser blocks on each external script, loading and executing them in document order.

## Two SCI Runtimes: What Lives Where

Roam now has **two independent SCI interpreters** running simultaneously:

| | Roam's SCI | Scittle's SCI (iframe) |
|---|---|---|
| **Purpose** | Evaluate `roam/render` components | nREPL-connected dev REPL |
| **Triggered by** | `{{[[roam/render]]:((uid))}}` blocks | Editor eval via nREPL |
| **Available namespaces** | `reagent.core`, `roam.datascript`, `roam.datascript.reactive`, `roam.block`, `promesa.core` | `clojure.core`, `clojure.string`, `cljs.pprint`, `promesa.core`, plus custom `roam.api` |
| **Reagent/React** | ✅ Full Reagent with Roam's React | ❌ Not loaded (would conflict) |
| **Reactive Datascript** | ✅ `rdr/pull` re-renders on changes | ❌ Not available |
| **roamAlphaAPI** | Available via JS interop | ✅ Bridged from parent window |
| **DOM access** | Limited to render component output | ✅ Full access to `parent.document` |
| **nREPL connection** | ❌ None | ✅ Full editor integration |
| **State persistence** | Survives navigation (Reagent atoms) | Lost on page reload |

### Implications for Development Workflow

The Scittle nREPL session is ideal for:
- **Exploratory queries** — test Datalog queries interactively before embedding them
- **Data inspection** — pull entities, explore the graph structure, debug block content
- **Rapid prototyping** — try out API calls, build helper functions, test transformations
- **Scripting** — batch operations on blocks/pages, data migrations, graph maintenance

For **rendering components**, the workflow is:
1. Develop and test logic via the nREPL (fast feedback)
2. Move finalized code into `component.cljs` (Roam's SCI, with Reagent support)
3. Build and reload the extension

## The bb Relay

The `bb.edn` file defines a single task:

```clojure
{:deps {io.github.babashka/sci.nrepl {...}}
 :tasks
 {roam-nrepl
  {:requires ([sci.nrepl.browser-server :as nrepl])
   :task (do (nrepl/start! {:nrepl-port 1339 :websocket-port 1340})
             (deref (promise)))}}}
```

`sci.nrepl.browser-server` is the bridge component. It:
- Starts an nREPL server on TCP port 1339 (what editors connect to)
- Starts a WebSocket server on port 1340 (what the browser connects to)
- Forwards nREPL messages bidirectionally between the two

The relay is stateless — it doesn't evaluate code itself. All evaluation happens in the browser's Scittle context. The relay must be running *before* or *when* Roam loads the extension (the WebSocket client in `scittle.nrepl.js` attempts to connect on script load).

## Extension Integration

The injection hooks into the existing Roam extension lifecycle:

```javascript
// src/index.js
import { injectScittleNrepl, removeScittleNrepl } from "./scittle-nrepl";

async function onload({extensionAPI}) {
  // ... existing extension setup ...

  // Inject Scittle nREPL (non-blocking, failure is non-fatal)
  try {
    await injectScittleNrepl();
  } catch (e) {
    console.warn("[tmem] Scittle nREPL injection failed (non-fatal):", e);
  }
}

function onunload() {
  removeScittleNrepl();
  // ... existing extension cleanup ...
}
```

The injection is wrapped in try/catch — if it fails (network issues, CSP changes, etc.), the rest of the extension continues to function normally.

## The `roam.api` Namespace

Auto-loaded into every nREPL session. Provides idiomatic ClojureScript access to the Roam Alpha API:

```clojure
(require '[roam.api :as r])

;; Reads
(r/q "[:find ?t :where [?e :node/title ?t]]")
(r/pull "[*]" [:block/uid "abc123"])
(r/search "meeting notes" :limit 5)
(r/block-string "abc123")
(r/page-uid "My Page")
(r/children "parent-uid")

;; Writes (return Promises)
(r/create-block "parent-uid" "Hello world")
(r/update-block "abc123" "Updated text")
(r/delete-block "abc123")
(r/create-page "New Page")

;; UI
(r/open-page :title "Some Page")
(r/open-block "abc123")
(r/focused-block)

;; Utilities
(r/today-title)       ;; => "March 27th, 2026"
(r/generate-uid)      ;; => "aB3xY9kLm"
```

All functions use `clj->js` for arguments and return raw JS objects. Use `(js->clj result :keywordize-keys true)` to convert results to Clojure data structures.

## Limitations & Edge Cases

### No Reagent in the REPL

Scittle's `scittle.reagent.js` plugin is intentionally not loaded. It bundles its own React copy, and while the iframe isolates globals, attempting to render Reagent components from the iframe into Roam's DOM would create cross-React-instance conflicts. The nREPL is for data/logic work; rendering stays in Roam's native SCI.

### State is Ephemeral

All `def`s, `defn`s, and atoms created in the nREPL session exist only in the iframe's Scittle context. They are lost when:
- The page is reloaded
- The extension is unloaded/reloaded
- The iframe is removed

For persistent state, write to Roam blocks via the API.

### Promise-Based Writes

All write operations (`create-block`, `update-block`, etc.) return JavaScript Promises. In the nREPL, these resolve asynchronously:

```clojure
;; The block is created asynchronously
(r/create-block "parent-uid" "test")
;; => #<Promise [object Promise]>

;; To chain operations, use promesa or .then
(.then (r/create-block "parent-uid" "test")
       (fn [_] (println "created!")))
```

### Relay Must Be Running

If `bb roam-nrepl` is not running when the extension loads, `scittle.nrepl.js` will fail to connect the WebSocket (connection refused). The REPL won't be available, but Roam and the rest of the extension continue working. Reloading the extension (or refreshing the page) after starting the relay will establish the connection.

### Raw JS Return Values

Roam's API returns JavaScript objects, not Clojure data structures. Query results are JS arrays of arrays. Pull results are JS objects with string keys like `"block/string"`. Always convert:

```clojure
;; Without conversion — JS objects
(r/q "[:find ?t :where [?e :node/title ?t]]")
;; => #js [#js ["Page 1"] #js ["Page 2"]]

;; With conversion — Clojure data
(js->clj (r/q "[:find ?t :where [?e :node/title ?t]]"))
;; => [["Page 1"] ["Page 2"]]
```

## Security Considerations

- The iframe runs with the same origin as Roam (`https://roamresearch.com`). It has full access to the parent window, DOM, cookies, and localStorage.
- The WebSocket connection to `localhost:1340` is unencrypted (`ws://`, not `wss://`). This is acceptable for local development but should not be exposed beyond localhost.
- The nREPL relay binds to `0.0.0.0` by default. For security, ensure your firewall blocks external access to ports 1339 and 1340.

## File Map

| File | Role |
|---|---|
| `src/scittle-nrepl.js` | Iframe creation, `document.write()` HTML generation, Scittle injection, roamAlphaAPI bridge, WebSocket monkey-patch, `roam.api` CLJS source |
| `src/index.js` | Extension entry point. Calls `injectScittleNrepl()` in `onload()`, `removeScittleNrepl()` in `onunload()` |
| `bb.edn` | Babashka task definition for `bb roam-nrepl` relay |
| `dev.cljs` | Playground file for editor-connected REPL exploration |
| `REPL-DEV.md` | Quickstart setup guide |

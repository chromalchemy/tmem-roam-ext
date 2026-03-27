# REPL-Driven Development in Roam

Live ClojureScript REPL inside Roam Research via Scittle nREPL.

## How It Works

```
┌─── Your Editor ──────────────────────────┐
│  Calva / CIDER / Conjure / etc.          │
│  nREPL client → localhost:1339           │
└──────────────────┬───────────────────────┘
                   │ nREPL protocol (TCP)
                   ▼
┌─── bb roam-nrepl relay ──────────────────┐
│  nREPL :1339  ←→  WebSocket :1340        │
└──────────────────┬───────────────────────┘
                   │ WebSocket
                   ▼
┌─── Roam Desktop (Electron) ──────────────┐
│  Scittle SCI    (injected by extension)  │
│  scittle.nrepl  (WS client → :1340)      │
│                                          │
│  js/window.roamAlphaAPI  ← full access   │
│  js/document             ← DOM access    │
│  roam.api ns             ← CLJS wrapper  │
└──────────────────────────────────────────┘
```

The extension injects Scittle (a browser-native SCI ClojureScript interpreter) into Roam on load. Scittle's nREPL client connects to a local WebSocket relay. Your editor's nREPL client connects to the same relay. Forms you evaluate in your editor are sent to the browser for execution **inside Roam's Electron process**, with full access to the Roam Alpha API and the DOM.

## Prerequisites

- [Babashka](https://github.com/babashka/babashka#installation) (`bb`)
- The tmem extension loaded in Roam
- An editor with nREPL support

## Quickstart

### 1. Start the relay

```sh
cd /path/to/tmem-roam-ext
bb roam-nrepl
```

You'll see:

```
  ╔══════════════════════════════════════════════╗
  ║  Roam Scittle nREPL Relay                   ║
  ║                                             ║
  ║  nREPL server:     localhost:1339           ║
  ║  WebSocket server: localhost:1340           ║
  ║                                             ║
  ║  Connect your editor to port 1339           ║
  ║  REPL type: nbb                             ║
  ╚══════════════════════════════════════════════╝
```

### 2. Load/reload the extension in Roam

The extension will inject Scittle automatically. Check the browser dev console (Cmd+Option+I in Electron) for:

```
[scittle-nrepl] ✓ Scittle nREPL ready
[roam.api] ✓ loaded — (require '[roam.api :as r]) to use
```

### 3. Connect your editor

#### Calva (VS Code)
1. Open `dev.cljs`
2. Ctrl+Shift+P → "Calva: Connect to a Running REPL in your Project"
3. Select "ClojureScript nREPL Server"
4. Host: `localhost`, Port: `1339`
5. REPL type: `nbb`

#### CIDER (Emacs)
1. `M-x cider-connect-cljs`
2. Host: `localhost`, Port: `1339`
3. REPL type: `nbb`

#### Conjure (Neovim)
1. Create `.nrepl-port` file containing `1339`
2. Open `dev.cljs` — Conjure auto-connects

### 4. Evaluate!

```clojure
;; Check we're alive
(+ 1 2 3)
;;=> 6

;; Access Roam
(require '[roam.api :as r])

(r/today-title)
;;=> "March 27th, 2026"

;; Query the graph
(take 5
  (map first
    (js->clj (r/q "[:find ?t :where [?e :node/title ?t]]"))))

;; Create a block
(r/create-block
  (r/page-uid (r/today-title))
  "Hello from the REPL! 🎉")

;; Direct JS interop
(js/window.roamAlphaAPI.data.q
  "[:find ?uid ?s :where [?e :block/uid ?uid] [?e :block/string ?s] [(clojure.string/includes? ?s \"TODO\")]]")
```

## The `roam.api` Namespace

Auto-loaded into every nREPL session. Use `(require '[roam.api :as r])`.

### Reads
| Function | Description |
|---|---|
| `(r/q query & args)` | Datalog query |
| `(r/pull pattern eid)` | Pull entity |
| `(r/search text :limit n)` | Full-text search |
| `(r/block-string uid)` | Get block text |
| `(r/page-uid title)` | Get page UID by title |
| `(r/children uid)` | Get child blocks |

### Writes (return Promises)
| Function | Description |
|---|---|
| `(r/create-block parent-uid text)` | Create block |
| `(r/update-block uid text)` | Update block |
| `(r/delete-block uid)` | Delete block |
| `(r/move-block uid parent-uid order)` | Move block |
| `(r/create-page title)` | Create page |
| `(r/delete-page uid)` | Delete page |

### UI
| Function | Description |
|---|---|
| `(r/open-page :title "...")` | Navigate to page |
| `(r/open-block uid)` | Zoom into block |
| `(r/focused-block)` | Get focused block |
| `(r/open-sidebar uid)` | Open in sidebar |

### Utilities
| Function | Description |
|---|---|
| `(r/generate-uid)` | New block UID |
| `(r/today-title)` | Today's page title |
| `(r/date->page-title date)` | Date → title |
| `(r/mobile?)` | Mobile check |

## Important Notes

### Iframe isolation

Scittle runs inside a hidden iframe to avoid clobbering Roam's compiled CLJS globals (`$APP`). The iframe uses a blob URL (same origin) so it can access `parent.window.roamAlphaAPI`. Inside the Scittle nREPL session, `js/window.roamAlphaAPI` is bridged to the real Roam API.

### This is a separate SCI from Roam's built-in one

Scittle runs its own SCI interpreter. You get full access to `js/window.roamAlphaAPI` (the JS API), but **not** Roam's internal CLJS namespaces like `roam.datascript.reactive` or `reagent.core`. Those live in Roam's own SCI context.

For reactive rendering, develop via the nREPL, then inject finalized code into `roam/render` blocks using the existing extension pattern.

### WebSocket / HTTPS

The nREPL WebSocket connects to `ws://localhost:1340`. Chromium treats `localhost` as a secure context, so this works even from HTTPS pages. The `SCITTLE_NREPL_WEBSOCKET_HOST` is set to `"localhost"` automatically.

### Connection lifecycle

- The WebSocket connection is re-established on page reload
- The nREPL relay (`bb roam-nrepl`) must be running before/when Roam loads
- State (def'd vars) is lost on page reload

# tmem-roam-ext

A Roam Research extension serving as a foundation for custom ClojureScript-based behaviors inside Roam. Forked from the [Nautilus](https://github.com/tombarys/roam-depot-nautilus) project to reuse its proven roam-depot extension scaffolding.

## Purpose

Provide a working base for developing and deploying custom ClojureScript components and commands within Roam Research, leveraging Roam's built-in SCI (Small Clojure Interpreter) runtime and its Reagent/Datascript APIs.

## Architecture

### How It Works

Roam Research can evaluate ClojureScript at runtime via `roam/render` code blocks. This extension exploits that mechanism:

1. **Webpack bundles** `.cljs` source files as **raw text** (via `text-loader` — no CLJS compilation step).
2. On extension load, the JS layer **injects** that raw CLJS text into a code block on the `roam/render` page in the user's graph.
3. Roam's SCI runtime **evaluates** the CLJS, making it available as a render component or command.
4. The component is invoked from any block via `{{[[roam/render]]:((roam-render-tmem-cljs))}}` or the `;;tmem` template.

```
┌─────────────────────────────────────────────────────────┐
│  Build time (webpack)                                   │
│                                                         │
│  src/component.cljs ──text-loader──▶ raw string ──┐     │
│  src/index.js ────────────────────────────────────┼──▶ extension.js
│  src/entry-helpers.js ───────────────────────────┘     │
└─────────────────────────────────────────────────────────┘
         │
         ▼  (extension installed in Roam)
┌─────────────────────────────────────────────────────────┐
│  Runtime (inside Roam)                                  │
│                                                         │
│  onload() ──▶ injects CLJS text into roam/render page   │
│             ──▶ registers settings panel                 │
│             ──▶ creates template for easy insertion      │
│                                                         │
│  Roam SCI evaluates the CLJS ──▶ Reagent component      │
│                               ──▶ Datascript queries     │
│                               ──▶ Block read/write       │
└─────────────────────────────────────────────────────────┘
```

### File Roles

| File | Role |
|---|---|
| `src/index.js` | Extension entry point. `onload`/`onunload` lifecycle, settings panel, template string generation. |
| `src/entry-helpers.js` | Creates/updates/toggles the CLJS code block in the Roam graph. Handles version upgrades. |
| `src/component.cljs` | **Primary CLJS component.** Loaded as raw text, injected into Roam, evaluated by SCI. This is where custom behavior lives. |
| `src/component-ical.cljs` | Secondary CLJS module (iCal context-menu command). Example of a non-render-component pattern. |
| `webpack.config.js` | Bundles JS + raw CLJS text into `extension.js`. Declares `react` and `chrono-node` as Roam-provided externals. |
| `extension.js` | Build output. The single file Roam loads. |
| `extension.css` | Stylesheet (currently empty). Loaded by Roam alongside the extension. |

### Build

```sh
npm install
npm run build     # or: npm run watch
```

Produces `extension.js` in the project root.

## CLJS Runtime Environment

The ClojureScript runs inside Roam's SCI sandbox. This is **not** a full ClojureScript compilation — it is interpreted ClojureScript with a constrained set of available libraries.

### Available Namespaces

| Namespace | Provides |
|---|---|
| `reagent.core` | Reactive UI components, atoms, `r/with-let`, `r/track` |
| `roam.datascript` | `rd/q` (Datalog queries), `rd/pull` against the graph |
| `roam.datascript.reactive` | `rdr/pull` — reactive pull that re-renders on graph changes |
| `roam.block` | `block/create`, `block/update`, `block/delete` |
| `roam.ui.ms-context-menu` | `ms/add-command` — register right-click context menu commands |
| `promesa.core` | Promise handling (`p/do!`, `p/let`, etc.) |
| `clojure.string` | Standard string utilities |
| `clojure.pprint` | Pretty-printing (useful for debug) |

### Available JS Globals

| Global | Provides |
|---|---|
| `js/window.roamAlphaAPI` | Full Roam Alpha API — queries, block CRUD, page operations, utilities |
| `js/window.roamAlphaAPI.util` | Helpers like `dateToPageTitle`, `generateUID` |
| `js/window.roamAlphaAPI.platform.isMobile` | Mobile detection |

### Component Contract

A `roam/render` component must expose a `main` function:

```clojure
(defn main [{:keys [:block-uid]} & args]
  ;; block-uid: the UID of the block containing the render call
  ;; args: parameters passed after the render reference
  [:div "Hello from CLJS"])
```

A context-menu command (non-render pattern) calls `main` at the top level:

```clojure
(defn main []
  (ms/add-command
   {:label "My Command"
    :callback (fn [x] ...)}))
(main)
```

## Constraints & Considerations

- **No npm dependencies in CLJS.** The CLJS is interpreted by SCI at runtime — only namespaces Roam exposes are available. JS interop (`js/...`) can reach browser APIs and Roam globals.
- **Single code block per component.** Each render component lives in one code block on the `roam/render` page. Multiple components need multiple code blocks with distinct UIDs.
- **No hot reload.** Changes require rebuilding (`npm run build`) and reloading the extension in Roam (or refreshing the page).
- **Text-loader means no CLJS tooling at build time.** No macros, no `defprotocol`, no reader conditionals — just what SCI supports.
- **Size.** The entire CLJS source is stored as a string in a Roam block. Very large components may hit practical limits.

## Current State

The project is a fork of Nautilus with the component name changed to `tmem`. The existing `component.cljs` contains the full Nautilus spiral planner — this serves as a working reference implementation but is intended to be replaced or extended with custom behaviors.

### What Carries Over from Nautilus (Reusable Infrastructure)

- Extension lifecycle (`onload`/`onunload`)
- Code block injection and version-update mechanism
- Settings panel wiring
- Template registration pattern
- Webpack build configuration
- The general pattern of "CLJS-as-text → inject → SCI evaluates → Reagent renders"

### What Is Nautilus-Specific (Candidates for Replacement)

- The spiral SVG visualization and all drawing code
- Task/event parsing (time ranges, durations, progress)
- Legend collision algorithm
- The specific settings (workday start, description length, etc.)

# Agent Bridge — Roam ↔ External Agent Protocol

A minimal Roam extension that bridges external MCP agents (via the Local API)
to the full in-browser `roamAlphaAPI` surface: DOM overlays, JS eval,
view-state subscriptions, and more.

## Why

The Roam Local API (port 3333) gives external tools CRUD + navigation, but **cannot**:
- Inject visual elements into the DOM (badges, overlays, highlights)
- Subscribe to real-time view/focus changes
- Run arbitrary JS or call browser-only APIs
- Show toasts or notifications

This extension fills that gap by watching a **control page** for structured
commands written by the external agent via the Local API.

## Architecture

```
┌──────────────────┐          ┌─────────────────────────────────┐
│  External Agent   │   HTTP   │  Roam Desktop                   │
│  (MCP / CLI)      │ ◄──────► │  Local API (port 3333)          │
│                   │          │                                  │
│  Writes commands  │          │  ┌───────────────────────────┐  │
│  to control page  ├──────────┤► │  agent-bridge extension    │  │
│                   │          │  │  • addPullWatch on cmds    │  │
│  Polls __state__  │◄─────────┤  │  • renders DOM overlays    │  │
│  for view changes │          │  │  • writes state + responses│  │
│                   │          │  └───────────────────────────┘  │
└──────────────────┘          └─────────────────────────────────┘
```

### Data Flow

1. External agent writes a JSON command block under `__commands__` via Local API
2. The extension's `addPullWatch` fires immediately on the Datascript transaction
3. The extension parses the JSON, executes the command in the browser context
4. A response block is written as a **child of the command block**
5. The external agent reads the response via Local API

## Installation (Dev Mode)

### Local HTTPS Server

The extension can be served locally for development using Babashka:

```bash
# One-time setup: generate TLS certs
brew install mkcert socat
mkcert -install                          # trusts local CA (needs sudo)
mkdir -p .certs && cd .certs && mkcert localhost 127.0.0.1 ::1
openssl pkcs12 -export -out keystore.p12 -inkey localhost+2-key.pem \
  -in localhost+2.pem -passout pass:changeit
cd ..

# Serve over HTTPS
bb serve-https
# → https://localhost:8889/extension.js

# Or plain HTTP (also works — Roam desktop trusts localhost)
bb serve
# → http://localhost:8888/extension.js
```

> **Note:** Roam's Electron app trusts `localhost` over plain HTTP for dev-mode
> extensions. HTTPS is only required for remote URLs. Either `bb serve` or
> `bb serve-https` will work.

### Loading in Roam

1. Build: `npm run build` (or `bb build`)
2. Start the local server: `bb serve` or `bb serve-https`
3. In Roam: **Settings → Developer → Load extension**
4. Enter the URL: `http://localhost:8888/extension.js`

### Verifying It Loaded

Check the browser console for:
```
[agent-bridge] Loading...
[agent-bridge] Loaded. Control page: roam-agent/bridge
```

The log line `dev-load-extension extension: http://localhost:8888/` confirms
Roam fetched and executed the extension.

## Control Page

On load, the extension creates (or finds) the page **`roam-agent/bridge`** with
two structural blocks:

```
roam-agent/bridge               ← page (uid stored in bridgePageUid)
├── __commands__                 ← agent writes command JSON here
│   ├── {"id":"cmd-1","type":"annotate","args":{…}}
│   │   └── {"id":"cmd-1","status":"done","result":{"count":3}}
│   └── {"id":"cmd-2","type":"eval","args":{…}}
│       └── {"id":"cmd-2","status":"done","result":{"value":"my-graph"}}
│
└── __state__                    ← extension writes current view state (on change, polled every 2s)
    └── {"ts":…,"main":{…},"sidebar":[],"focused":null,"labels":{"A":"uid1","B":"uid2",…}}
```

## How To Tell It's Working

| Signal | Where | What to look for |
|--------|-------|-----------------|
| Console logs | Browser DevTools | `[agent-bridge] Loaded. Control page: roam-agent/bridge` |
| Control page | Navigate to `roam-agent/bridge` in Roam | `__commands__` and `__state__` blocks exist |
| State updates | `__state__` child block | `ts` field increments every ~2 seconds |
| Command palette | Cmd+P → "Agent Bridge: Show Status" | Toast showing active/inactive + annotation count |
| DOM elements | Inspect `<head>` | `<style id="agent-bridge-styles">` present |

## Local API Access

**The bridge state is just regular Roam blocks.** Any API surface that can
read/write blocks can interact with the bridge.

### Reading state via Local API

```bash
# Query for the bridge page and its children
curl -X POST http://localhost:3333/api/graph/tmem \
  -H "Content-Type: application/json" \
  -d '{
    "action": "pull",
    "selector": "[:block/uid :block/string {:block/children [:block/uid :block/string {:block/children [:block/uid :block/string]}]}]",
    "uid": "<bridge-page-uid>"
  }'
```

Or use a Datalog query:
```bash
curl -X POST http://localhost:3333/api/graph/tmem \
  -H "Content-Type: application/json" \
  -d '{
    "action": "q",
    "query": "[:find ?uid ?s :where [?p :node/title \"roam-agent/bridge\"] [?p :block/children ?c] [?c :block/uid ?uid] [?c :block/string ?s]]"
  }'
```

### Writing a command via Local API

```bash
curl -X POST http://localhost:3333/api/graph/tmem \
  -H "Content-Type: application/json" \
  -d '{
    "action": "create-block",
    "location": {"parent-uid": "<commands-block-uid>", "order": "last"},
    "block": {"string": "{\"id\":\"cmd-42\",\"type\":\"get-view\",\"args\":{}}"}
  }'
```

### API Surface Compatibility

| API | Read state? | Write commands? | Bridge reacts? |
|-----|-------------|-----------------|----------------|
| Local API (`:3333`) | ✅ | ✅ | ✅ Immediate (PullWatch) |
| Backend API (`api.roamresearch.com`) | ✅ | ✅ | ⚠️ After sync |
| `roamAlphaAPI` (in-browser) | ✅ | ✅ | ✅ Immediate |
| roam-mcp (MCP tools) | ✅ | ✅ | ✅ Immediate |

## Command Protocol

### Writing a command (from external agent)

Create a child block under `__commands__` with a JSON string:

```json
{"id": "cmd-001", "type": "annotate", "args": {"blocks": [{"uid": "abc123", "label": "A"}]}}
```

The extension's `addPullWatch` fires immediately. It processes the command and
writes a **response as a child of the command block**:

```json
{"id": "cmd-001", "status": "done", "result": {"count": 1}}
```

### Reading the response

Poll the command block's children via `pull` or `q` and look for a child
whose JSON has `"status": "done"` or `"status": "error"`.

### Cleanup

The external agent should delete processed command blocks when done to keep the
control page clean. Commands accumulate under `__commands__` indefinitely —
there is no automatic garbage collection.

### Deduplication

Each command `id` is tracked in memory. Re-processing the same `id` is skipped.
The dedup set is capped at 500 entries (oldest evicted first). If the extension
is reloaded, the set resets — stale undeleted commands may re-fire.

## Command Reference

### `annotate`

Render index badges on blocks visible in the DOM.

```json
{
  "id": "cmd-001",
  "type": "annotate",
  "args": {
    "blocks": [
      {"uid": "block-uid-1", "label": "A"},
      {"uid": "block-uid-2", "label": "B", "intent": "action"},
      {"uid": "block-uid-3", "label": "C", "intent": "success"}
    ]
  }
}
```

**`intent`** (optional): `"info"` (blue, default), `"action"` (red),
`"success"` (green), `"warn"` (yellow).

Calling `annotate` again **replaces** all existing annotations.

Badges are positioned absolute in the left gutter of each block container.
A `MutationObserver` on `.roam-body-main` re-applies them after Roam's
virtual-list re-renders.

Blocks are matched via `data-block-uid` attribute on `.roam-block-container`
elements. Blocks must be visible in the DOM (scrolled into view) for badges
to appear.

**Response:** `{"count": 3}`

### `clear`

Remove all annotations from the DOM.

```json
{"id": "cmd-002", "type": "clear", "args": {}}
```

**Response:** `{}`

### `get-view`

Snapshot the current main window, sidebar, and focused block.

```json
{"id": "cmd-003", "type": "get-view", "args": {}}
```

**Response:**
```json
{
  "ts": 1711234567890,
  "main": {"type": "outline", "uid": "page-uid"},
  "sidebar": [{"type": "outline", "block-uid": "...", "order": 0}],
  "focused": {"block-uid": "...", "window-id": "..."},
  "labels": {"A": "uid1", "B": "uid2"}
}
```

The `labels` field is included when annotations are active (via `scan-blocks`
or `annotate`). It maps visual hint labels to block UIDs.

### `scan-blocks`

Scan the DOM for all visible blocks, assign navigator-style labels (A, B, …
Z, AA, AB, …), render yellow hint badges, and return the full mapping.

```json
{
  "id": "cmd-006",
  "type": "scan-blocks",
  "args": {
    "scope": "main",
    "include_text": true
  }
}
```

**`scope`** (optional): `"main"` (default view), `"sidebar"`, or `"all"`
(both). Default: `"all"`.

**`include_text`** (optional): Include each block's text content in the
response. Default: `true`.

**Response:**
```json
{
  "count": 8,
  "mapping": [
    {"label": "A", "uid": "J3n66dJ3t", "region": "main", "text": "ray salamy", "page": "March 27th, 2026"},
    {"label": "B", "uid": "_ddHPWaGg", "region": "main", "text": "susie salamy", "page": "March 27th, 2026"},
    {"label": "C", "uid": "miiKh8x3o", "region": "main", "text": "#math #data/viz", "page": "March 27th, 2026"}
  ]
}
```

The label→uid mapping is also written to `__state__.labels` so any agent
polling state can resolve labels without re-issuing the command.

Badges use the `nav` intent (yellow, compact, Vimium-style). Calling
`scan-blocks` again re-scans and replaces all labels. Use `clear` to remove.

**Typical agent workflow:**
```
# 1. Scan what the user is looking at
send: {"id":"s1","type":"scan-blocks","args":{"scope":"main"}}
→ response includes mapping with labels A–H and block text

# 2. User says "move block C under block A"
# Agent resolves: C → miiKh8x3o, A → J3n66dJ3t
move_block(uid="miiKh8x3o", parentUid="J3n66dJ3t", order="last")

# 3. Re-scan to update labels after the move
send: {"id":"s2","type":"scan-blocks","args":{"scope":"main"}}

# 4. Clear when done
send: {"id":"s3","type":"clear","args":{}}
```

### `eval`

Run arbitrary JavaScript in the Roam browser context. Has access to
`roamAlphaAPI` as a parameter and full access to `window`, `document`, etc.

```json
{
  "id": "cmd-004",
  "type": "eval",
  "args": {
    "code": "return roamAlphaAPI.graph.name"
  }
}
```

**Response:** `{"value": "my-graph"}`

The `code` string becomes the body of `new Function("roamAlphaAPI", code)`, so:
- Use `return` to pass values back
- `roamAlphaAPI` is available as a local binding
- `window`, `document`, etc. are all accessible
- Async code works: `return await roamAlphaAPI.data.pull("...", ...)`

**Error response:** `{"error": "ReferenceError: x is not defined", "stack": "..."}`

> **Security note:** `eval` gives full browser-context access. Any agent with
> write access to the graph can execute arbitrary code in the user's session.

### `notify`

Show a toast notification.

```json
{
  "id": "cmd-005",
  "type": "notify",
  "args": {
    "message": "3 blocks updated",
    "intent": "success"
  }
}
```

**`intent`**: `"info"`, `"warning"`, `"error"`, `"success"`

Uses Blueprint's Toaster if available, otherwise renders a fixed-position
fallback div at top-right.

**Response:** `{}`

## View State (Proactive)

The extension polls view state every 2 seconds and writes to `__state__`
**only when something changes** (navigation, focus, label map). Unchanged
polls are skipped to avoid Datascript churn.

```json
{
  "ts": 1774648294879,
  "main": {"type": "outline", "uid": "03-27-2026", "title": "March 27th, 2026"},
  "sidebar": [],
  "focused": {"block-uid": "focused-uid", "window-id": "main-window"},
  "labels": {"A": "J3n66dJ3t", "B": "_ddHPWaGg", "C": "miiKh8x3o"}
}
```

- **`labels`** — present when annotations are active (via `scan-blocks` or
  `annotate`). Maps visual hint labels to block UIDs. Agents can resolve
  "block B" → `_ddHPWaGg` by reading this field.
- **`focused`** — `null` when no block is being edited.

The external agent can poll this block to detect navigation and focus changes
without needing to issue `get-view` commands.

## In-Memory State

| Variable | Purpose |
|----------|---------|
| `bridgePageUid` | UID of the `roam-agent/bridge` page |
| `commandsBlockUid` | UID of the `__commands__` block |
| `stateBlockUid` | UID of the `__state__` block |
| `currentAnnotations` | Array of `{uid, label, intent}` for active badge overlays |
| `processedCommandIds` | `Set<string>` dedup guard, capped at 500 entries |
| `pullWatchCallback` | Reference to the PullWatch listener (for cleanup) |
| `stateInterval` | `setInterval` handle for the 2s view-state poll |
| `lastStateJson` | Previous state snapshot for diff-before-write optimization |
| `blockObserver` | `MutationObserver` on `.roam-body-main` for re-applying badges |
| `activeLabelMap` | `{"A": "uid1", …}` — current label→uid mapping, included in state |

## Example: MCP Agent Workflow

### Navigator pattern (scan → reference by label)

```
# 1. Scan what the user sees — labels appear in Roam UI
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"s1","type":"scan-blocks","args":{"scope":"main"}}'
)
→ response: {"count":8,"mapping":[{"label":"A","uid":"J3n66dJ3t","text":"ray salamy"}, ...]}
→ yellow A–H badges appear on blocks in Roam

# 2. User says "edit block C"
# Agent resolves C → miiKh8x3o from the mapping (or from __state__.labels)
update_block(uid="miiKh8x3o", string="Updated content here")

# 3. Re-scan after edits to refresh labels
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"s2","type":"scan-blocks","args":{"scope":"main"}}'
)

# 4. Clear when done
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"s3","type":"clear","args":{}}'
)
```

### Manual annotate pattern (custom labels on specific blocks)

```
# Annotate specific blocks with custom labels/colours
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"a1","type":"annotate","args":{"blocks":[{"uid":"uid1","label":"A","intent":"action"},{"uid":"uid2","label":"B","intent":"success"}]}}'
)

# Poll __state__ to detect navigation or resolve labels
get_page(title="roam-agent/bridge")
→ __state__ includes {"labels":{"A":"uid1","B":"uid2"}, ...}
```

## Known Issues

| Severity | Issue | Notes |
|----------|-------|-------|
| 🟡 | No command garbage collection | Commands accumulate under `__commands__` forever; agent must clean up |
| 🟡 | Dedup can produce duplicate responses | PullWatch can fire twice before the dedup set catches up |
| 🟢 | Fallback toasts don't stack | Multiple simultaneous `notify` commands overlap at the same position |
| 🟢 | `scan-blocks` only sees rendered DOM | Collapsed children and blocks scrolled out of Roam's virtual list won't appear |
| ✅ | ~~State writes every 2s unconditionally~~ | Fixed: diff-before-write skips unchanged state |
| ✅ | ~~Dead `navObserver` variable~~ | Fixed: removed |

## Building

```bash
npm run build     # one-shot production build → extension.js
bb build          # alias for npm run build
bb serve          # serve over HTTP  (port 8888)
bb serve-https    # serve over HTTPS (port 8889, requires mkcert + socat)
```

Output: `extension.js` (≈7KB minified)

## Files

| File | Purpose |
|------|---------|
| `src/agent-bridge.js` | Extension source (onload/onunload) |
| `src/agent-bridge.css` | Badge/overlay styles |
| `webpack.config.js` | Webpack config (entry: `src/agent-bridge.js`) |
| `extension.js` | Built output (loaded by Roam) |
| `bb.edn` | Babashka tasks (serve, serve-https, build) |
| `serve_https.bb` | HTTPS server script (http-kit + socat TLS) |
| `.certs/` | Local TLS certificates (gitignored) |
| `AGENT-BRIDGE.md` | This file |

# Agent Bridge — Roam ↔ External Agent Protocol

A minimal Roam extension that bridges external MCP agents (via the Local API)
to the full in-browser `roamAlphaAPI` surface: DOM overlays, JS eval,
view-state subscriptions, and more.

## Why

The Roam Local API (port 3333) gives external tools CRUD + navigation, but **cannot**:
- Inject visual elements into the DOM (overlays, highlights, bullet labels)
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
    └── {"ts":…,"main":{…},"sidebar":[],"focused":null,"labels":{"A":{"uid":"uid1","region":"main"},"B":{"uid":"uid2","region":"main"},…}}
```

## How To Tell It's Working

| Signal | Where | What to look for |
|--------|-------|-----------------|
| Console logs | Browser DevTools | `[agent-bridge] Loaded. Control page: roam-agent/bridge` |
| Control page | Navigate to `roam-agent/bridge` in Roam | `__commands__` and `__state__` blocks exist |
| State updates | `__state__` child block | `ts` field increments every ~2 seconds |
| Command palette | Cmd+P → "Agent Bridge: Show Status" | Toast showing active/inactive, annotation count, and nav-mode state |
| Command palette | Cmd+P → "Agent Bridge: Toggle Nav Mode" | Toggles nav-mode on/off with toast confirmation |
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
| Backend API (`api.roamresearch.com`) | ✅ | ✅ | ⚠️ After sync¹ |
| `roamAlphaAPI` (in-browser) | ✅ | ✅ | ✅ Immediate |
| roam-mcp (MCP tools) | ✅ | ✅ | ✅ Immediate |

¹ The Backend API writes are synced to the local Roam client on a delay
(typically seconds to tens of seconds depending on sync conditions). The
extension's PullWatch won't fire until the sync completes, making Backend
API unsuitable for latency-sensitive command flows.

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

**Example: deleting a processed command via Local API**

```bash
# After reading the response from command block <cmd-block-uid>, delete it:
curl -X POST http://localhost:3333/api/graph/tmem \
  -H "Content-Type: application/json" \
  -d '{
    "action": "delete-block",
    "block": {"uid": "<cmd-block-uid>"}
  }'
```

**Bulk cleanup:** To clear all commands at once, delete all children of the
`__commands__` block. The extension will continue to function — the PullWatch
and dedup set are unaffected by deletions.

### Deduplication

Each command `id` is tracked in memory. Re-processing the same `id` is skipped.
The dedup set is capped at 500 entries (oldest evicted first). On load, the
extension pre-seeds the dedup set from all existing command blocks, preventing
re-processing of old commands after a reload.

## Command Reference

### `annotate`

Render index labels on block bullets visible in the DOM.

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

Labels are rendered by setting `data-agent-label` and `data-agent-intent`
attributes on the native bullet element (`.rm-bullet__inner` or
`.rm-bullet__inner--user-icon`). CSS transforms the bullet into a labeled
indicator. All native bullet behavior is preserved — click to zoom, drag to
move, and right-click for the context menu all work normally.

A `MutationObserver` on `.roam-body-main` re-applies labels after Roam's
virtual-list re-renders.

Blocks are matched via `data-block-uid` attribute on `.roam-block-container`
elements. Blocks must be visible in the DOM (scrolled into view) for labels
to appear.

**Response:** `{"count": 3}`

> **Side-effect:** Also updates `activeLabelMap` and `__state__.labels` with the provided label→uid mapping.

### `clear`

Remove all annotations from the DOM.

```json
{"id": "cmd-002", "type": "clear", "args": {}}
```

**Response:** `{}`

> **Side-effect:** Also clears the label map — `__state__.labels` will be absent on the next state write.

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
  "labels": {"A": {"uid": "uid1", "region": "main"}, "B": {"uid": "uid2", "region": "main"}}
}
```

The `labels` field is included when annotations are active (via `scan-blocks`
or `annotate`). It maps visual hint labels to block UIDs.

### `scan-blocks`

Scan the DOM for all visible blocks, assign navigator-style labels (A, B, …
Z, AA, AB, …), render yellow hint labels on the native bullets, and return the full mapping.

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

**Response fields per entry:**
- **`label`**: Assigned letter label (A, B, … Z, AA, AB, …)
- **`uid`**: Block UID
- **`region`**: `"main"` or `"sidebar"`
- **`text`**: Block string content (present when `include_text` is true)
- **`page`**: Page title from the block's `data-page-title` DOM attribute
  (present when the block is rendered under a page header, e.g. on daily notes
  or linked references — may be absent for blocks on their own page)

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

Labels use the `nav` intent (yellow, compact, Vimium-style) rendered
directly on the block bullets. Calling `scan-blocks` again re-scans and
replaces all labels. Use `clear` to remove.

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

### `nav-mode`

Persistent auto-labelling mode. Scans visible blocks, assigns labels, renders
yellow nav labels on bullets, and auto-rescans whenever the view changes (page navigation,
sidebar open/close, blocks appearing/disappearing).

```json
{
  "id": "cmd-007",
  "type": "nav-mode",
  "args": {"scope": "all"}
}
```

**`scope`** (optional): `"main"`, `"sidebar"`, or `"all"`. Default: `"all"`.

While active, the extension monitors a view fingerprint (current page, sidebar
state, visible block UIDs) on every 2s poll. If the fingerprint changes, a full
rescan is triggered automatically — labels are re-assigned and
`__state__.labels` is updated. No manual re-scan needed.

**Response:**
```json
{
  "active": true,
  "scope": "all",
  "count": 12,
  "labels": {"A": {"uid": "J3n66dJ3t", "region": "main"}, "B": {"uid": "_ddHPWaGg", "region": "main"}}
}
```

Use `nav-off` to disable. Use `clear` to remove labels without disabling the
mode (though nav-mode will re-render them on next rescan).

### `nav-off`

Turn off nav-mode auto-labelling. Clears all annotations and the label map.

```json
{"id": "cmd-008", "type": "nav-off", "args": {}}
```

**Response:** `{"active": false}`

> **Side-effect:** Clears all annotations from the DOM and resets the label map — `__state__.labels` will be absent on the next state write (same effect as `clear`, plus disabling auto-rescan).

### `select-block`

Highlight or edit block(s) in the Roam UI. Accepts single or multiple UIDs.

```json
{
  "id": "cmd-009",
  "type": "select-block",
  "args": {
    "uids": ["J3n66dJ3t", "_ddHPWaGg"],
    "window_id": "main-window",
    "mode": "focus"
  }
}
```

**`uids`** (required): Array of block UIDs to select. Also accepts `uid`
(string) for a single block.

**`window_id`** (optional): `"main-window"` (default) or a sidebar window ID.
Determines which pane to search for the block.

**`mode`** (optional): `"focus"` (default) highlights the block(s) with a
visual indicator without entering edit mode. `"edit"` focuses the first block's
textarea for text input.

In focus mode, highlighted blocks get an `agent-select-highlight` CSS class and
are scrolled into view. The highlight clears on the next user click or keypress.
The selected UIDs are written to `__state__.selected`.

**Response:** `{"uids": ["J3n66dJ3t", "_ddHPWaGg"], "mode": "focus", "window_id": "main-window"}`

The response echoes all requested UIDs regardless of whether they were found
in the DOM. Blocks not currently rendered (scrolled out of Roam's virtual list)
will be included in the response but won't receive a visual highlight.

See `docs/BLOCK-SELECTION-LIMITATIONS.md` for known limitations of block
selection.

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

The `code` string becomes the body of an `AsyncFunction("roamAlphaAPI", code)`,
so:
- Use `return` to pass values back
- Top-level `await` is supported:
  `return await roamAlphaAPI.data.pull("[:block/string]", [":block/uid", "abc123"])`
- `roamAlphaAPI` is available as a local binding
- `window`, `document`, etc. are all accessible
- Multiple async steps work naturally:
  `const a = await fetch(...); return await a.json()`

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
  "labels": {"A": {"uid": "J3n66dJ3t", "region": "main"}, "B": {"uid": "_ddHPWaGg", "region": "main"}, "C": {"uid": "miiKh8x3o", "region": "main"}},
  "selected": ["J3n66dJ3t"]
}
```

The external agent can poll this block to detect navigation and focus changes
without needing to issue `get-view` commands.

### State Schema

Canonical reference for all fields in the `__state__` JSON object:

| Field | Type | Presence | Description |
|-------|------|----------|-------------|
| `ts` | `number` | Always | Unix timestamp (ms) of when this state snapshot was captured |
| `main` | `object` | Always | Current main window view. Contains `type` (`"outline"`, `"graph"`, etc.), `uid` (page UID), and `title` (page title) |
| `sidebar` | `array` | Always | Array of sidebar window objects. Each has `type`, `block-uid`, `order`, and `window-id`. Empty array `[]` when sidebar is closed or empty |
| `focused` | `object \| null` | Always | Currently focused (editing) block: `{"block-uid": "...", "window-id": "..."}`. `null` when no block is being edited |
| `labels` | `object` | Conditional | Present when annotations are active (via `scan-blocks`, `annotate`, or `nav-mode`). Maps label strings to `{"uid": "...", "region": "main"|"sidebar"}`. Agents resolve "block B" → UID by reading `labels.B.uid`. Absent when no annotations are active |
| `selected` | `string[]` | Conditional | Present when blocks are highlighted via `select-block` focus mode. Array of block UIDs. Clears automatically when the user clicks or presses a key |

## In-Memory State

| Variable | Purpose |
|----------|---------|
| `bridgePageUid` | UID of the `roam-agent/bridge` page |
| `commandsBlockUid` | UID of the `__commands__` block |
| `stateBlockUid` | UID of the `__state__` block |
| `currentAnnotations` | Array of `{uid, label, intent}` for active bullet label overlays |
| `processedCommandIds` | `Set<string>` dedup guard, capped at 500 entries |
| `pullWatchCallback` | Reference to the PullWatch listener (for cleanup) |
| `stateInterval` | `setInterval` handle for the 2s view-state poll |
| `lastStateJson` | Previous state snapshot for diff-before-write optimization |
| `blockObserver` | `MutationObserver` on `.roam-body-main` and `#right-sidebar` for re-applying bullet labels |
| `activeLabelMap` | `{"A": {"uid": "uid1", "region": "main"}, …}` — current label→uid mapping, included in state |
| `navModeActive` | `boolean` — whether nav-mode auto-labelling is active |
| `navModeScope` | `"main"` \| `"sidebar"` \| `"all"` — scope for nav-mode scanning |
| `lastNavViewKey` | Fingerprint of last view state for nav-mode change detection |
| `selectedBlockUids` | `string[]` — UIDs of blocks highlighted via `select-block` focus mode |
| `renderingInProgress` | `boolean` — suppresses MutationObserver callbacks during own DOM writes |

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
→ yellow A–H labels appear on block bullets in Roam

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
→ __state__ includes {"labels":{"A":{"uid":"uid1","region":"main"},"B":{"uid":"uid2","region":"main"}}, ...}
```

## CLI Client (`bridge.bb`)

A Babashka CLI client that communicates with the agent bridge via Roam's
Local API. Provides command-line access to nav-mode, block selection, and
block movement.

### Usage

```bash
bb bridge --on                    # turn on nav-mode labels
bb bridge --off                   # turn off nav-mode labels
bb bridge --labels                # show label→uid map
bb bridge --select A              # highlight block A
bb bridge --select A,B,C          # highlight multiple blocks
bb bridge --select A -e           # focus block A for editing
bb bridge --select A -s           # highlight block A in sidebar
bb bridge --select A -s -e        # edit block A in sidebar
bb bridge --move A --to D         # move block A under block D
bb bridge --move A,B --to D       # move blocks A,B under block D
bb bridge --move-selected --to D  # move currently selected blocks under D
bb bridge --label A               # ⚠️ deprecated — append timestamp to block A
```

### Flags

| Flag | Description | Default |
|------|-------------|---------|
| `--graph` | Roam graph name | `tmem` |
| `--on` | Turn on nav-mode (scan + label visible blocks) | |
| `--off` | Turn off nav-mode | |
| `--labels` | Print current label→uid mapping | |
| `--select <label>` | Select/highlight block(s) by label (comma-separated) | |
| `-e` / `--edit` | Edit mode: focus block text for typing | |
| `--move <labels>` | Move block(s) by label (comma-separated) | |
| `--move-selected` | Move currently selected (highlighted) blocks | |
| `--to <label>` | Target parent block label for `--move` / `--move-selected` | |
| `-s` / `--sidebar` | Select in sidebar (optionally Nth: `-s 2`) | |
| `--scope` | Nav scope: `main` \| `sidebar` \| `all` | `all` |
| `--label <X>` | **Deprecated.** Legacy action that appends a ✅ timestamp to a block. Use `--select <X>` instead for highlighting, or operate on blocks directly via the Local API | |

### How it works

The CLI discovers the bridge control page (`roam-agent/bridge`) via Datalog
queries against the Local API. It sends commands by creating blocks under
`__commands__` and polls for response children. Label resolution reads
`__state__.labels` (auto-enabling nav-mode if labels aren't present).

## Security Considerations

The agent bridge intentionally exposes powerful browser-context capabilities
to any process that can write blocks to the Roam graph. Understand the trust
boundaries before deploying.

**Trust model:** Any client with write access to the `roam-agent/bridge`
page can execute arbitrary JavaScript in the user's browser session via the
`eval` command. This includes full access to `roamAlphaAPI`, the DOM,
`localStorage`, cookies, and any other browser APIs.

**Attack surface:**

| Vector | Risk | Mitigation |
|--------|------|------------|
| Local API (`:3333`) | Low — localhost only, requires graph token | Ensure token is not leaked; Local API binds to `127.0.0.1` |
| Shared/multiplayer graph | **High** — any collaborator can write commands | Never use the bridge on shared graphs, or delete the control page when not in use |
| Backend API | Medium — requires API token with write access | Treat API tokens as secrets; rotate if compromised |
| `eval` command | **High** — arbitrary code execution by design | Restrict to trusted agents only; consider removing `eval` in production builds |

**Recommendations:**
- Only run the bridge extension on graphs you fully control
- Do not share the `roam-agent/bridge` page with collaborators
- Treat your Roam API token and Local API port as sensitive credentials
- Delete the control page when the bridge is not actively in use
- Audit any agent code that issues `eval` commands

## Known Issues

| Severity | Issue | Notes |
|----------|-------|-------|
| 🟡 | No command garbage collection | Commands accumulate under `__commands__` forever; agent must clean up |
| 🟡 | Block selection is fragile | Focus-mode highlight uses DOM class manipulation; see `docs/BLOCK-SELECTION-LIMITATIONS.md` |
| 🟢 | Fallback toasts don't stack | Multiple simultaneous `notify` commands overlap at the same position |
| 🟢 | `scan-blocks` only sees rendered DOM | Collapsed children and blocks scrolled out of Roam's virtual list won't appear |
| ✅ | ~~Dedup re-fires on reload~~ | Fixed: pre-seeds dedup set from existing commands on load |
| ✅ | ~~State writes every 2s unconditionally~~ | Fixed: diff-before-write skips unchanged state |
| ✅ | ~~Dead `navObserver` variable~~ | Fixed: removed |
| ✅ | ~~Dead CLJS interop code~~ | Fixed: removed `resolveCljsSymbols` and `selectBlockViaInternals` |
| ✅ | ~~Dead `navRescanTimer` reference~~ | Fixed: removed from `onunload` |

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
| `src/agent-bridge.css` | Bullet-label and overlay styles |
| `bridge.bb` | Babashka CLI client for the agent bridge |
| `webpack.config.js` | Webpack config (entry: `src/agent-bridge.js`) |
| `extension.js` | Built output (loaded by Roam) |
| `bb.edn` | Babashka tasks (serve, serve-https, build, bridge) |
| `serve_https.bb` | HTTPS server script (http-kit + socat TLS) |
| `docs/BLOCK-SELECTION-LIMITATIONS.md` | Known limitations of CLJS-based block selection |
| `.certs/` | Local TLS certificates (gitignored) |
| `AGENT-BRIDGE.md` | This file |

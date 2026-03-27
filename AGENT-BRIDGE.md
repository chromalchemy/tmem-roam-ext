# Agent Bridge — Roam ↔ External Agent Protocol

A minimal Roam extension that bridges external MCP agents (via the Local API)
to the full in-browser `roamAlphaAPI` surface: DOM overlays, JS eval,
view-state subscriptions, and more.

## Why

The Roam Local API gives external tools CRUD + navigation, but **cannot**:
- Inject visual elements into the DOM (badges, overlays, highlights)
- Subscribe to real-time view/focus changes
- Run arbitrary JS or call other extensions
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

## Installation

### Option A: Dev-mode extension

1. Build: `npm run build:bridge`
2. In Roam: **Settings → Developer → Load extension**
3. Point to the generated `agent-bridge.js`

### Option B: roam/js

1. Build: `npm run build:bridge`
2. Create a `{{[[roam/js]]}}` block
3. Paste the contents of `agent-bridge.js` as a child code block

## Control Page

On load, the extension creates (or finds) the page **`roam-agent/bridge`** with
two heading blocks:

```
roam-agent/bridge
├── __commands__    ← external agent writes command JSON here
└── __state__       ← extension writes current view state here (auto-polled every 2s)
```

## Command Protocol

### Writing a command (from external agent)

Create a child block under `__commands__` with a JSON string:

```json
{"id": "cmd-001", "type": "annotate", "args": {"blocks": [{"uid": "abc123", "label": "A"}, {"uid": "def456", "label": "B", "intent": "action"}]}}
```

The extension's `addPullWatch` fires immediately. It processes the command and
writes a **response as a child of the command block**:

```json
{"id": "cmd-001", "status": "done", "result": {"count": 2}}
```

### Reading the response (from external agent)

Poll the command block via `get_block(uid, maxDepth=1)` and look for a child
whose JSON has `"status": "done"` or `"status": "error"`.

### Cleanup

The external agent should delete processed command blocks when done to keep the
control page clean.

## Command Reference

### `annotate`

Render index badges on blocks in the DOM.

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

**Response:** `{"count": 3}`

### `clear`

Remove all annotations.

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
  "focused": {"block-uid": "...", "window-id": "..."}
}
```

### `eval`

Run arbitrary JavaScript in the Roam browser context. Has access to
`roamAlphaAPI` as a parameter.

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
- `window`, `document`, etc. are available
- Async code works: `return await roamAlphaAPI.data.pull("...", ...)`

**Error response:** `{"error": "ReferenceError: x is not defined", "stack": "..."}`

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

**Response:** `{}`

## View State (Proactive)

The extension writes the current view state to `__state__` every 2 seconds:

```json
{
  "ts": 1711234567890,
  "main": {"type": "outline", "uid": "page-uid-here"},
  "sidebar": [
    {"type": "outline", "block-uid": "sidebar-uid", "order": 0, "pinned-to-top": false}
  ],
  "focused": {"block-uid": "focused-uid", "window-id": "main-window"}
}
```

The external agent can poll this block to detect navigation and focus changes
without needing to issue `get-view` commands.

## Example: MCP Agent Workflow

```
# 1. Read current page blocks
get_page(title="My Page")
→ returns blocks with UIDs

# 2. Annotate visible blocks with index labels
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"a1","type":"annotate","args":{"blocks":[{"uid":"uid1","label":"A"},{"uid":"uid2","label":"B"},{"uid":"uid3","label":"C"}]}}'
)
→ blocks A, B, C now have blue badges in Roam UI

# 3. User says "edit block B"
# Agent knows B → uid2
update_block(uid="uid2", string="Updated content here")

# 4. Clear annotations when done
create_block(
  pageTitle="roam-agent/bridge",
  nestUnder="__commands__",
  markdown='{"id":"a2","type":"clear","args":{}}'
)

# 5. Poll __state__ to detect if user navigated away
get_page(title="roam-agent/bridge")
→ read __state__ child for latest view info
```

## Building

```bash
npm run build     # one-shot production build → extension.js
npm run watch     # rebuild on changes
```

Output: `extension.js` (≈7KB minified)

## Files

| File | Purpose |
|------|---------|
| `src/agent-bridge.js` | Extension source (onload/onunload) |
| `src/agent-bridge.css` | Badge/overlay styles |
| `webpack.config.js` | Webpack config |
| `extension.js` | Built output (loaded by Roam) |
| `AGENT-BRIDGE.md` | This file |

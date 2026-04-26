# Command Schema — Composable Wire Protocol (v1)

**Status:** Phase A — schema lock. The shapes here are the canonical wire
contract for every command crossing the agent ↔ bridge boundary. Phases
B–H of [`COMPOSABLE-REFACTOR-PLAN.md`](./COMPOSABLE-REFACTOR-PLAN.md)
implement them.

This document is **descriptive of intent**, not exhaustive of what the
JS extension currently dispatches. The legacy `{id,type,args}` shape
still works for the action types the JS extension still handles, but
the envelope **must** include `version: 1`. (Phases A–F kept missing-
version as a `console.warn`; Phase G tightened it to a hard reject —
`{error: "missing-version"}` — once all in-tree senders were verified
to emit version=1.)

---

## 1 · Envelope

Every command block under `__commands__` is a JSON string. The canonical
shape:

```jsonc
{
  "version":       1,                      // schema version, integer
  "id":            "cmd-001",              // dedup id, string, required
  "spokenForm":    "chuck every child of A", // optional; for telemetry/debug
  "labelsVersion": 1774648294879,          // optional; matches __state__.ts
  "action": {
    "name":   "remove",                    // action ID from §5
    /* …action-shape-specific slots… */
  }
}
```

### Field rules

| Field | Required | Type | Notes |
|---|---|---|---|
| `version` | required | `1` | Hard-rejected if absent (`missing-version`) or not `1` (`unknown-version`). Bump on any breaking change to envelope or AST. |
| `id` | required | `string` | Dedup key. Repeats are silently dropped by the receiver. |
| `spokenForm` | optional | `string` | Pass-through label for logs. Never affects dispatch. |
| `labelsVersion` | optional | `number` | The `__state__.ts` snapshot the sender saw at parse time. The bridge MAY reject the command with `{"error":"stale-labels","currentTs":N}` when a referenced `label` mark no longer resolves. |
| `action` | required | `object` | See §5 for shapes. |

### Response shape

The receiver writes a response **as a child of the command block**:

```jsonc
{ "id": "cmd-001", "status": "done"  | "error", "result": { … } | null }
```

`result` is action-specific (§5). On error, `result.error` is a string;
`result.stack` may be present.

---

## 2 · Target AST

A **target** describes a region of the block tree. Four variants:

### 2.1 `primitive`

```jsonc
{ "type": "primitive",
  "mark": <Mark>,
  "modifiers": [ <Modifier>, … ] }
```

Either `mark` OR `modifiers` may be absent — but not both.
A bare modifier list applies to the implicit selection (cursor /
`__state__.selected`). A bare mark with no modifiers resolves to the
mark's own region.

### 2.2 `range`

```jsonc
{ "type": "range",
  "anchor":        <primitive>,
  "active":        <primitive>,
  "excludeAnchor": false,
  "excludeActive": false }
```

**Roam constraint:** `anchor` and `active` MUST resolve to siblings of
the same parent. Otherwise: `{"error":"range-cross-parent"}`.

A range expands to the contiguous run of siblings from `anchor.order`
through `active.order`, inclusive (modulo `excludeAnchor`/`excludeActive`).

### 2.3 `list`

```jsonc
{ "type": "list",
  "elements": [ <primitive | range>, … ] }
```

Single-element lists collapse to the element. The receiver MAY accept
either form.

### 2.4 `implicit`

```jsonc
{ "type": "implicit" }
```

Sentinel meaning "use the per-action default" (see §6).

---

## 3 · Marks

A mark identifies a starting region without modifiers. Mark kinds:

| `type`        | Other fields                | Resolves to |
|---|---|---|
| `label`       | `value: "A"`                | `__state__.labels[value].uid` |
| `uid`         | `value: "abc123"`           | the block with that UID |
| `pageTitle`   | `value: "Tasks"`            | the page (top-level region) |
| `daily`       | `value: "today"\|"+3"\|"next-mon"` | a daily-note page, creating it if needed |
| `cursor`      | —                           | `__state__.focused.block-uid` |
| `selection`   | —                           | `__state__.selected[]` |
| `that`        | —                           | last operated target (pronoun, §7) |
| `source`      | —                           | source of last move/link (pronoun, §7) |
| `phrase`      | `value: "buy milk"`         | substring match against `block.string` (**case-sensitive** — Roam's Datalog whitelist excludes `clojure.string/lower-case`) |
| `placeholder` | `index: 0`                  | filled by enclosing context (embedded DSL only — §8 of refactor plan) |

### Label-mark constraint

A `label` mark resolves against the `__state__.labels` snapshot at
`labelsVersion`. If the snapshot is stale (label re-assigned to a
different UID), the receiver responds `{"error":"stale-labels"}` with
the current `ts` so the sender can re-snapshot and retry.

---

## 4 · Modifiers

A modifier takes a region and returns a region. Categories:

### 4.1 `containing`

```jsonc
{ "type": "containing",
  "scope": "parent" | "page" | "topLevel",
  "ancestorIndex": 1 }   // optional, defaults to 1; >1 = grandparent etc.
```

| `scope`     | Result                                                     |
|---|---|
| `parent`    | direct parent block (or `ancestorIndex`-th ancestor)       |
| `page`      | the page node containing the region                        |
| `topLevel`  | top-level block of the page (depth-1 ancestor)             |

### 4.2 `every`

```jsonc
{ "type": "every",
  "scope": "child" | "descendant" | "sibling" | "reference" | "mention",
  "tag":   "math" }   // only for scope:"mention"
```

| `scope`      | Result                                                |
|---|---|
| `child`      | direct children                                       |
| `descendant` | recursive descendants                                 |
| `sibling`    | siblings (excluding self) sharing the parent          |
| `reference`  | every block that links to the region                  |
| `mention`    | every block whose string contains `#tag`              |

### 4.3 `ordinal`

```jsonc
{ "type": "ordinal",
  "scope": "child" | "sibling",
  "index": 0 }   // 0-indexed; negative counts from end (-1 = last)
```

### 4.4 `relative`

```jsonc
{ "type": "relative",
  "scope":     "sibling",
  "direction": "forward" | "backward",
  "count":     3 }
```

### 4.5 `head` / `tail`

```jsonc
{ "type": "head" | "tail",
  "scope": "child",
  "count": 5 }
```

`head` keeps the first N of the region's children; `tail` the last N.

### 4.6 `position` (destination-only)

```jsonc
{ "type": "position",
  "at":   "start" | "end" }
```

Collapses a region to an insertion point. **Only valid inside a
destination's `target`** — applying `position` to a primitive that's
about to be acted on directly is a parse error.

---

## 5 · Action shapes

Six shapes cover the entire surface. Each shape names the slots in its
`action` object.

### 5.1 Action + target

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "remove", "target": <Target> } }
```

| `name`                | Result `result` |
|---|---|
| `remove`              | `{"deleted": ["…"], "count": N}` |
| `setSelection`        | `{"uids": ["…"]}` |
| `addToSelection`      | `{"uids": ["…"]}` |
| `removeFromSelection` | `{"uids": ["…"]}` |
| `collapse`            | `{"count": N}` |
| `expand`              | `{"count": N}` |
| `zoom`                | `{"uid": "…"}` |
| `openInSidebar`       | `{"uid": "…", "windowId": "…"}` |
| `getText`             | `{"texts": [{"uid":"…","string":"…"}, …]}` |
| `nudge`               | `{"uid": "…", "direction": "…"}` (also takes `direction` slot) |
| `getRefs`             | `{"refs": [{"uid":"…","page":"…"}, …]}` |

### 5.2 Action + source + destination

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "moveToTarget",
              "source": <Target>,
              "destination": <Destination> } }
```

| `name`         | Notes |
|---|---|
| `moveToTarget` | Moves source blocks to destination. |
| `linkToTarget` | Creates a `((uid))` reference of source under destination. |
| `aliasMove`    | `moveToTarget` with `leaveAlias: true` flag — leaves an alias-reference behind at the source location. |

### 5.3 Action + destination

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "insertNewBlock",
              "destination": <Destination>,
              "string": "" } }   // optional initial content; default ""
```

| `name`           | Notes |
|---|---|
| `insertNewBlock` | Creates a new block at destination and focuses its textarea. |
| `pasteBlock`     | Inserts the clipboard's block subtree at destination. |
| `pasteText`      | Inserts the clipboard's text content at destination. |

### 5.4 Action + two targets with connective

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "swap",
              "target1": <Target>,
              "target2": <Target> } }
```

| `name`        | Notes |
|---|---|
| `swap`        | Swaps the two blocks' positions in their parents. |
| `swapContent` | Swaps the two blocks' string content (children stay put). |

### 5.5 Action + scope

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "setNavMode",
              "scopeType": "main" | "sidebar" | "all" | null } }
```

| `name`              | Notes |
|---|---|
| `setNavMode`        | `scopeType: null` disables nav-mode. |
| `foldEveryAtDepth`  | Takes additional `args.depth: number`. |

### 5.6 Pass-through

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "executeRoamCommand",
              "target": <Target>,           // optional pre-selection
              "commandId": "Open Block in Sidebar" } }
```

```jsonc
{ "version": 1, "id": "…",
  "action": { "name": "eval",
              "args": { "code": "return roamAlphaAPI.graph.name" } } }
```

`eval` retains its current contract for the migration window (see
[`AGENT-BRIDGE.md`](../AGENT-BRIDGE.md#eval)). It is **not** a target-
shaped action because its body is opaque.

---

## 6 · Destination AST

```jsonc
{ "type":          "destination",
  "insertionMode": "to" | "before" | "after",
  "target":        <Target> }
```

`insertionMode` semantics:

| Mode     | Effect on the target's resolved region |
|---|---|
| `to`     | Insert as a child of the target. Use a `position` modifier on `target` (`{at:"start"}` or `{at:"end"}`) to disambiguate first-vs-last child. Default: end. |
| `before` | Insert as a sibling immediately before the target. |
| `after`  | Insert as a sibling immediately after the target. |

> **Reorder semantics.** `moveToTarget` with `destination.target` set
> to `{type:"primitive", mark:{type:"that"}, modifiers:[{type:"containing", scope:"parent"}]}`
> and `insertionMode:"to"` + a `{type:"position", at:"end"}` modifier
> on the target collapses to "stay under current parent, position end" —
> the equivalent of today's `--reorder`.

---

## 7 · Per-action implicit-slot semantics

When a slot is `{"type":"implicit"}` (or absent), the receiver fills in
the per-action default below. This table is **the contract** — sender
and receiver MUST agree.

| Action               | Implicit `target` / `source`              | Implicit `destination` |
|---|---|---|
| `remove`             | `selection` pronoun, fallback `cursor`    | n/a |
| `collapse`           | `selection` pronoun, fallback `cursor`    | n/a |
| `expand`             | `selection` pronoun, fallback `cursor`    | n/a |
| `zoom`               | `selection` pronoun, fallback `cursor`    | n/a |
| `openInSidebar`      | `cursor`                                  | n/a |
| `getText`            | `selection` pronoun, fallback `cursor`    | n/a |
| `setSelection`       | **error** — must be explicit              | n/a |
| `addToSelection`     | **error** — must be explicit              | n/a |
| `removeFromSelection`| **error** — must be explicit              | n/a |
| `nudge`              | `selection` pronoun, fallback `cursor`    | n/a |
| `getRefs`            | `selection` pronoun, fallback `cursor`    | n/a |
| `moveToTarget`       | source = `selection` pronoun              | "stay under current parent, position end" |
| `linkToTarget`       | source = `that` pronoun                   | `cursor` |
| `aliasMove`          | source = `selection` pronoun              | "stay under current parent, position end" |
| `insertNewBlock`     | n/a                                       | first child of `cursor` |
| `pasteBlock`         | n/a                                       | first child of `cursor` |
| `pasteText`          | n/a                                       | first child of `cursor` |
| `swap`               | both targets MUST be explicit             | n/a |
| `swapContent`        | both targets MUST be explicit             | n/a |

---

## 8 · Pronouns

After every successful command, the bridge updates a pronoun map.

```jsonc
{ "that":   { "uids": ["…"], "ts": 1774648294879, "action": "setSelection" },
  "source": { "uids": ["…"], "ts": 1774648294879, "action": "moveToTarget" } }
```

A `that` mark resolves to `pronouns.that.uids`. A `source` mark
resolves to `pronouns.source.uids`. Both are first-class marks (§3).

### Persistence

`bb` invocations are ephemeral, so pronouns are persisted to a per-graph
JSON file:

```
${java.io.tmpdir}/roam-bridge-pronouns-{graph}.json
```

This survives across voice commands. The Phase G JS rewrite will
additionally mirror these into `__state__.pronouns` so external agents
polling state can read them without spawning `bb`.

### Update rules

| Action               | `:that` updated to                          | `:source` updated to                        |
|---|---|---|
| `setSelection`, `addToSelection`, `removeFromSelection` | dispatch result `:uids` | — |
| `remove`, `collapse`, `expand`, `zoom`, `openInSidebar`, `getText`, `nudge`, `getRefs` | dispatch result `:uids` | — |
| `moveToTarget`, `linkToTarget`, `aliasMove` | destination uids (resolved post-action) | source uids (resolved pre-action) |
| `insertNewBlock`     | the newly created block's uid               | — |
| `swap`, `swapContent`| both target1 and target2 uids               | — |

---

## 9 · Errors

All errors are returned via the response shape (§1) with
`status: "error"`. Canonical error codes:

| `result.error`         | Cause |
|---|---|
| `missing-version`      | `version` field absent. (Phase G tightened from warn-and-continue.) |
| `unknown-version`      | `version` field present but ≠ `1`. |
| `unknown-action`       | `action.name` not in catalogue. |
| `missing-slot`         | A required slot (per §5) is absent and no implicit default exists. |
| `range-cross-parent`   | `range`'s `anchor` and `active` don't share a parent. |
| `position-on-target`   | `position` modifier applied outside a destination. |
| `stale-labels`         | A `label` mark didn't resolve at the snapshot's `labelsVersion`. Includes `currentTs`. |
| `mark-not-found`       | A mark resolved to no blocks (and no implicit fallback applies). |
| `not-implemented`      | Action exists in schema but isn't yet wired up by the receiver. |

---

## 10 · Examples

### Single-target

> *"chuck A"*

```jsonc
{ "version": 1, "id": "c1", "spokenForm": "chuck A",
  "labelsVersion": 1774648294879,
  "action": {
    "name": "remove",
    "target": { "type": "primitive",
                "mark": { "type": "label", "value": "A" } } } }
```

### Modifier composition

> *"fold every descendant of A"*

```jsonc
{ "version": 1, "id": "c2",
  "action": {
    "name": "collapse",
    "target": { "type": "primitive",
                "mark": { "type": "label", "value": "A" },
                "modifiers": [ { "type": "every", "scope": "descendant" } ] } } }
```

### Source + destination

> *"move A to first child of D"*

```jsonc
{ "version": 1, "id": "c3",
  "action": {
    "name": "moveToTarget",
    "source": { "type": "primitive",
                "mark": { "type": "label", "value": "A" } },
    "destination": {
      "type": "destination",
      "insertionMode": "to",
      "target": { "type": "primitive",
                  "mark": { "type": "label", "value": "D" },
                  "modifiers": [ { "type": "position", "at": "start" } ] } } } }
```

### Range

> *"chuck A through C"* (siblings)

```jsonc
{ "version": 1, "id": "c4",
  "action": {
    "name": "remove",
    "target": {
      "type": "range",
      "anchor": { "type": "primitive",
                  "mark": { "type": "label", "value": "A" } },
      "active": { "type": "primitive",
                  "mark": { "type": "label", "value": "C" } } } } }
```

### List

> *"chuck A, C, and F"*

```jsonc
{ "version": 1, "id": "c5",
  "action": {
    "name": "remove",
    "target": {
      "type": "list",
      "elements": [
        { "type": "primitive", "mark": { "type": "label", "value": "A" } },
        { "type": "primitive", "mark": { "type": "label", "value": "C" } },
        { "type": "primitive", "mark": { "type": "label", "value": "F" } } ] } } }
```

### Implicit (selection-driven)

> *"chuck"* (operates on `__state__.selected`)

```jsonc
{ "version": 1, "id": "c6",
  "action": { "name": "remove", "target": { "type": "implicit" } } }
```

### Pronoun

> *"chuck that"* (re-removes the result of the previous command — useful
> for "undo last add" patterns)

```jsonc
{ "version": 1, "id": "c7",
  "action": {
    "name": "remove",
    "target": { "type": "primitive",
                "mark": { "type": "that" } } } }
```

---

## 11 · Versioning policy

- `version: 1` is locked at Phase A. Additive changes (new actions,
  new modifier scopes, new mark kinds) do **not** bump the version.
- Bump to `version: 2` on any of:
  - removing or renaming an existing action / modifier / mark
  - changing the meaning of an existing slot
  - changing the response shape
- The receiver SHOULD support the previous version for one full
  release cycle alongside the current version.

---

## See also

- [`COMPOSABLE-REFACTOR-PLAN.md`](./COMPOSABLE-REFACTOR-PLAN.md) — phased
  refactor toward this schema.
- [`AGENT-BRIDGE.md`](../AGENT-BRIDGE.md) — current `{id,type,args}`
  command catalogue (legacy shape, still supported during migration).
- [`composable-action-grammar.md`](file:///Users/ryan/.talon/user/docs/composable-action-grammar.md)
  — Cursorless-style grammar that this schema ports to block trees.

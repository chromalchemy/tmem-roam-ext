# Composable Action Grammar — Refactor Plan

A staged refactor of the three layers (`agent-bridge.js`, `bridge.clj`,
Talon grammar) toward Cursorless-style composable command grammars,
adapted for **block trees** rather than text spans.

Source patterns: [`composable-action-grammar.md`](file:///Users/ryan/.talon/user/docs/composable-action-grammar.md).
Current architecture: [`AGENT-BRIDGE.md`](../AGENT-BRIDGE.md).

---

## 0 · Why refactor

Three signals from the current code:

| Layer | LoC | Problem |
|---|---|---|
| `agent-bridge.js` | 1041 | 11-arm `switch (type)` with bespoke args per command. Every new operation = new case + new arg schema. |
| `bridge.clj` | 1163 | ~25 public fns. Sources, targets, modifiers exist as ad-hoc map keys re-implemented per command (`new-child!` vs `fold-children!` vs `zoom-parent!` all bake "child of"/"parent of" into the function name). |
| Talon | ~390 | Each spoken-form variant is a hand-written rule that string-concatenates a Clojure expression. `block_edit.talon` alone has 12 `new-block` shapes. |

The `transfer!` function and the `<user.roam_source>` / `<user.roam_destination>`
captures already prove the composable approach works in this codebase —
one rule covers move/link/alias × labels/uids/selected/parent ×
labels/uids/pages/dailies × first/last/before/after. The plan extends
that proof from one verb to the whole surface.

**The rephrasing for Roam:** every operation in the bridge is a verb on
a *region of the block tree*. The region is described by a **mark**
(label, UID, page-title, pronoun, fuzzy-text) plus a **modifier chain**
(every-child, parent-of, first-N, before, after, …). That's all.

---

## 1 · Cursorless concepts → Roam-block port

| Cursorless | Roam (block tree) | Notes |
|---|---|---|
| character | — | Sub-block addressing is out of scope. |
| token | block | Atomic unit. Block.string is opaque; we don't address inside it. |
| line | sibling | A row at one depth in one parent. |
| paragraph | subtree | Block + descendants. |
| function / class | hierarchical scope | "the block containing", "the page containing". |
| selection | focused / multi-selected | `__state__.focused.block-uid` and `__state__.selected[]`. |

### Marks

| Mark kind | Meaning | Source today |
|---|---|---|
| `label` | navigator-style hint label `A`/`B`/… | `__state__.labels` |
| `uid` | direct block UID | `:source-uid`, `roam_ref` Talon list |
| `pageTitle` | page by title | `roam_tag` Talon list |
| `daily` | DNP keyword (`today`, `next-mon`, `+3`) | `roam_daily` Talon list |
| `cursor` | `__state__.focused.block-uid` | implicit today |
| `selection` | `__state__.selected[]` (a `ListTarget`) | explicit `:selected` flag |
| `that` | result target of last command | **missing** |
| `source` | source of last move/link | **missing** |
| `phrase` | fuzzy text match against block.string | **missing** |

### Modifiers (block-tree native)

These are the **seven categories** specialised for trees. Each one is
*an operation that takes a mark and returns a region*.

| Category | Spoken form | AST | Today's bridge equivalent |
|---|---|---|---|
| **containing** | "block of A" / "page of A" / "parent of A" | `{type:"containing", scope:"parent\|page\|topLevel"}` | `zoom-parent!`, `:parent true` flag |
| **every** | "every child of A" / "every descendant of A" / "every reference to A" / "every sibling of A" | `{type:"every", scope:"child\|descendant\|reference\|sibling"}` | `fold-children!`, `unfold-children!` |
| **ancestor** | "outer A" / "grandparent A" | `{type:"containing", scope:"parent", ancestorIndex:N}` | — |
| **ordinal** | "first child of A" / "third sibling of A" / "last child of A" | `{type:"ordinal", scope:"child\|sibling", index:N}` *(negative index = from end)* | `:order :first/:last` flag |
| **relative** | "next 3 siblings of A" / "previous block" | `{type:"relative", scope:"sibling", direction:"forward\|backward", count:N}` | — |
| **position** | "start of A" / "end of A" | `{type:"position", at:"start\|end"}` *(only as destination modifier — collapses scope to insertion point)* | `:first`/`:last` mode of destination |
| **head/tail** | "first 5 children of A" / "last 5 children of A" | `{type:"head"\|"tail", scope:"child", count:N}` | — |

Two extra modifiers worth listing because Roam is graph-shaped, not
just tree-shaped:

| Category | Spoken form | AST |
|---|---|---|
| **referencesTo** | "every reference to A" | `{type:"every", scope:"reference"}` |
| **mentions** | "every block mentioning #tag" | `{type:"every", scope:"mention", tag:"…"}` |

### Targets, ranges, lists, destinations (recursive AST)

```jsonc
// Primitive
{ "type": "primitive",
  "mark": { "type": "label", "value": "A" },
  "modifiers": [ {"type":"every","scope":"child"} ] }

// Range (sibling span — A through C must share a parent)
{ "type": "range",
  "anchor": <primitive>,
  "active": <primitive>,
  "excludeAnchor": false, "excludeActive": false }

// List
{ "type": "list", "elements": [<primitive | range>, …] }

// Implicit (sentinel)
{ "type": "implicit" }

// Destination = target with insertion mode
{ "type": "primitive", "insertionMode": "to" | "before" | "after",
  "target": <target> }
```

Three Roam-specific clarifications:

1. **Range only makes sense as a sibling span.** Cursorless ranges work
   in any 1-D text geometry. Roam blocks are 2-D (depth × order). A
   range `A through C` resolves to "the children of `parent(A)` from
   order(A) through order(C)" — and is rejected at runtime if A and C
   don't share a parent.
2. **`insertionMode: "to"` + a `position` modifier on the target is how
   "to first child of D" is expressed.** `--first` / `--last` flags
   today collapse into this.
3. **A mark can be a `target` itself (placeholder).** When the bridge
   accepts an embedded string DSL (§ 8), `<target>` placeholders parse
   as a leaf mark, recursively re-injected. (See §11 of the source doc.)

---

## 2 · The wire envelope

Every command crosses two wires today:

- **JS ↔ bridge.clj:** existing JSON in `__commands__` blocks. Type-tagged.
- **Talon ↔ bridge.clj:** Babashka shell-exec of a Clojure expression string.

The plan unifies them by giving both layers a **single canonical wire
shape** — the JSON envelope below — and making the Babashka entry point
just `(execute-command! payload-edn)` instead of 25 different functions.

```jsonc
{
  "version": 1,                            // schema version, bump on breaks
  "spokenForm": "chuck every child of A",  // for telemetry / debugging
  "labelsVersion": 1774648294879,          // matches __state__.ts at parse time
  "action": {
    "name": "remove",
    "target": <target>
  }
}
```

### Action shapes (the only shapes you need)

These mirror §4 of the source doc, with Roam-specific notes:

| Shape | Action examples | Slots |
|---|---|---|
| **Action + target** | `remove`, `setSelection`, `addToSelection`, `removeFromSelection`, `collapse`, `expand`, `zoom`, `openInSidebar`, `getText`, `nudge`, `getRefs` | `target` |
| **Action + source + dest?** | `moveToTarget`, `linkToTarget`, `aliasMove` | `source`, `destination` |
| **Action + dest** | `insertNewBlock` (creates an empty focused block), `pasteBlock`, `pasteText` | `destination` |
| **Action + 2 targets w/ connective** | `swap`, `swapContent` | `target1`, `target2` |
| **Action + scope** | `foldEveryAtDepth`, `setNavMode` (scope = main/sidebar/all) | `scopeType`, `args` |
| **Pass-through** | `executeRoamCommand` (pre-select target then fire IDE command), `eval` | `target?`, `commandId` |

### Per-action implicit-slot semantics

Document explicitly (per §10 of the source doc). For Roam:

| Action | Implicit `target` | Implicit `source` | Implicit `destination` |
|---|---|---|---|
| `remove`, `collapse`, `expand`, `getText` | the `selection` pronoun (or `cursor` if none) | n/a | n/a |
| `setSelection` | error — must name something | n/a | n/a |
| `moveToTarget` | n/a | `selection` pronoun | "stay under current parent, position end" (= reorder semantics) |
| `linkToTarget` | n/a | `that` (last operated target) | `cursor` |
| `insertNewBlock` | n/a | n/a | first child of `cursor` |

This table replaces the implicit branching baked into `--reorder` /
`--move-selected` / `new-sibling!` / `new-block!` today.

---

## 3 · Current → new mapping

Concrete refactor target. Left column lists every public Clojure entry
point (and the matching Talon rule); right column shows the unified
shape it folds into.

| Today (`bridge.clj` fn / Talon rule) | New shape | Notes |
|---|---|---|
| `select!` / `take A` | `setSelection target=A` | |
| `select-add!` / `also take A` | `addToSelection target=A` | |
| `select-remove!` / `untake A` | `removeFromSelection target=A` | |
| `delete!` / `chuck A` | `remove target=A` | |
| `fold!` / `fold A` | `collapse target=A` | |
| `unfold!` / `unfold A` | `expand target=A` | |
| `fold-children!` / `fold children of A` | `collapse target={A, every:child}` | **modifier replaces dedicated fn** |
| `unfold-children!` / `unfold children of A` | `expand target={A, every:child}` | |
| `zoom!` (label / uid / page / daily) | `zoom target=…` | mark variation, not action variation |
| `zoom-parent!` / `zoom parent of A` | `zoom target={A, containing:parent}` | |
| `zoom-out!` | `zoom target={cursor, containing:parent}` | |
| `open-sidebar!` (4 mark kinds) | `openInSidebar target=…` | |
| `new-block!` (page, order=first) | `insertNewBlock dest={to+start, page=P}` | |
| `new-block!` (page, order=last) | `insertNewBlock dest={to+end, page=P}` | |
| `new-sibling!` (focused, first) | `insertNewBlock dest={to+start, {cursor, containing:parent}}` | |
| `new-sibling!` (focused, last) | `insertNewBlock dest={to+end, {cursor, containing:parent}}` | |
| `new-child!` ref/label, first | `insertNewBlock dest={to+start, A}` | |
| `new-child!` ref/label, last | `insertNewBlock dest={to+end, A}` | |
| `new-before!` A | `insertNewBlock dest={before, A}` | |
| `new-after!` A | `insertNewBlock dest={after, A}` | |
| `move!` `:labels` … `:label` … `:order :first` | `moveToTarget src=A dest={to+start, D}` | |
| `move!` … `:before :B` | `moveToTarget src=A dest={before, B}` | |
| `move!` `:selected` | `moveToTarget src=selection dest=…` | |
| `move!` `:parent` | `moveToTarget src={A, containing:parent} dest=…` | |
| `link!` | `linkToTarget src=… dest=…` | |
| `transfer!` `:action :alias` | `aliasMove src=… dest=…` | (or `moveToTarget` with `leaveAlias:true`) |
| `swap-blocks!` A B | `swap target1=A target2=B` | |
| `swap-blocks!` A B `:content true` | `swapContent target1=A target2=B` | |
| `nudge!` direction | `nudge target=… direction=…` | label optional, defaults to selection/cursor |
| `hats-on!` / `hats-off!` | `setNavMode scope=…` / `setNavMode scope=null` | |
| `gc!` | (not a graph operation — keep as Babashka-side utility) | |

**What disappears:** `fold-children!`, `unfold-children!`, `zoom-parent!`,
`zoom-out!`, `new-sibling!`, `new-child!`, `new-before!`, `new-after!`,
plus all four `new-block!` arity overloads. ~10 public functions.
**What's added:** `every`, `containing`, `position` modifiers; one
`insertNewBlock` action.

---

## 4 · Talon surface (post-refactor)

### Two captures, one rule per action

```python
# roam_target.py — replaces roam_source AND roam_destination/destination-shape
@mod.capture(rule="<user.roam_modifier>+ [<user.roam_mark>] | <user.roam_mark>")
def roam_target(m) -> dict: ...

# roam_destination.py
@mod.capture(rule="{user.roam_insertion_mode} <user.roam_target>")
def roam_destination(m) -> dict: ...
```

Where `roam_modifier` is itself a flat union (mirrors §3.3 of source):

```python
@mod.capture(rule=
  "<user.roam_containing_modifier>"   # parent of, page of
  " | <user.roam_every_modifier>"     # every child of, every reference to
  " | <user.roam_ordinal_modifier>"   # first child of, third sibling of
  " | <user.roam_relative_modifier>"  # next 3 siblings of
  " | <user.roam_head_tail_modifier>" # first 5 children of
)
```

### `hats.talon` after refactor

The whole file collapses to one rule per action shape:

```talon
# Action + target
(take | mark) <user.roam_target>:
    user.roam_action("setSelection", roam_target)

(chuck | delete | remove) <user.roam_target>:
    user.roam_action("remove", roam_target)

(fold | collapse) <user.roam_target>:
    user.roam_action("collapse", roam_target)

(unfold | expand) <user.roam_target>:
    user.roam_action("expand", roam_target)

(zoom | load) <user.roam_target>:
    user.roam_action("zoom", roam_target)

(bar | sidebar) <user.roam_target>:
    user.roam_action("openInSidebar", roam_target)

# Action + source + destination
<user.roam_transfer_verb> <user.roam_target> <user.roam_destination>:
    user.roam_action_pair(roam_transfer_verb, roam_target, roam_destination)

# Action + destination
(insert | new) [block] <user.roam_destination>:
    user.roam_action_dest("insertNewBlock", roam_destination)

# Action + 2 targets w/ connective
swap [content] <user.roam_target> [and|with] <user.roam_target>:
    user.roam_swap(roam_target_1, roam_target_2, content_flag)

# Modifiers compose for free in all of the above
```

### Vocabulary in CSV (per §8 step 9)

```csv
# roam-actions.csv — spoken-form → action ID
chuck,remove
delete,remove
take,setSelection
mark,setSelection
fold,collapse
collapse,collapse
unfold,expand
expand,expand
zoom,zoom
load,zoom
bar,openInSidebar
sidebar,openInSidebar

# roam-scopes.csv — spoken-form → scope-type ID
child,child
kid,child
descendant,descendant
sub,descendant
sibling,sibling
neighbour,sibling
reference,reference
ref,reference
mention,mention
parent,parent
folks,parent
page,page
top level,topLevel
```

A new vocabulary item is one CSV row. A new modifier shape is one
Python `@mod.capture` plus an entry in `roam_modifier`'s union.

### Pronouns

```python
mod.list("roam_pronoun", desc="Roam pronoun marks")
ctx.lists["user.roam_pronoun"] = {
    "cursor": "cursor",
    "current": "cursor",
    "this": "cursor",
    "that": "that",
    "source": "source",
    "selection": "selection",
    "selected": "selection",
}
```

`that` and `source` get tracked in `__state__.pronouns` by the
extension after every command (§5).

---

## 5 · Bridge runtime (`bridge.clj`)

### Single dispatch entry point

Replace ~25 public fns with one:

```clojure
(defn execute-command!
  "Execute a composable command payload. Returns a result map."
  [payload]
  (let [{:keys [version action labelsVersion]} payload
        snapshot (load-state-snapshot labelsVersion)] ;; pre-phrase
    (dispatch (:name action) action snapshot)))

(defmulti dispatch (fn [name _action _state] name))

(defmethod dispatch "remove"        [_ {:keys [target]}      s] …)
(defmethod dispatch "setSelection"  [_ {:keys [target]}      s] …)
(defmethod dispatch "collapse"      [_ {:keys [target]}      s] …)
(defmethod dispatch "expand"        [_ {:keys [target]}      s] …)
(defmethod dispatch "zoom"          [_ {:keys [target]}      s] …)
(defmethod dispatch "openInSidebar" [_ {:keys [target]}      s] …)
(defmethod dispatch "moveToTarget"  [_ {:keys [source dest]} s] …)
(defmethod dispatch "linkToTarget"  [_ {:keys [source dest]} s] …)
(defmethod dispatch "insertNewBlock"[_ {:keys [destination]} s] …)
(defmethod dispatch "swap"          [_ {:keys [target1 t2]}  s] …)
(defmethod dispatch "nudge"         [_ {:keys [target dir]}  s] …)
;; …
```

### Resolver as the spine

Every action calls **one** function on its target before anything else:

```clojure
(declare resolve-target resolve-modifier resolve-mark)

(defn resolve-target
  "Resolve a target AST to a vector of {:uid :order :parent-uid :region} maps."
  [state target]
  (case (:type target)
    "primitive" (apply-modifiers state
                                 (resolve-mark state (:mark target))
                                 (:modifiers target))
    "list"      (mapcat #(resolve-target state %) (:elements target))
    "range"     (resolve-range state target)
    "implicit"  (implicit-default state)))

(defn resolve-mark [state mark] …)        ;; label / uid / page / daily / pronoun / phrase
(defn apply-modifiers [state region mods] (reduce apply-modifier region mods))
(defn apply-modifier  [region {:keys [type scope index direction count]}] …)
```

The current `resolve-source-uids`, `resolve-target` (the older one),
`resolve-uid`, `resolve-labels`, `resolve-label-region`, and most of
`do-move!` / `do-link!` / `do-move-selected!` / `transfer!` collapse
into this resolver plus a tiny per-action handler.

### Modifier implementations (block-tree)

```clojure
(defmulti apply-modifier (fn [_region mod] (:type mod)))

(defmethod apply-modifier "containing"
  [region {:keys [scope ancestorIndex]}]
  (let [n (or ancestorIndex 1)]
    (case scope
      "parent"   (mapv #(climb-parents % n) region)
      "page"     (mapv containing-page region)
      "topLevel" (mapv top-level-of-page region))))

(defmethod apply-modifier "every"
  [region {:keys [scope]}]
  (case scope
    "child"      (mapcat children-of region)
    "descendant" (mapcat descendants-of region)
    "sibling"    (mapcat siblings-of region)
    "reference"  (mapcat references-to region)
    "mention"    (mapcat mentions-of region)))

(defmethod apply-modifier "ordinal"
  [region {:keys [scope index]}]
  (case scope
    "child"   (mapv #(nth-child % index) region)
    "sibling" (mapv #(nth-sibling % index) region)))

(defmethod apply-modifier "relative" [region m] …)
(defmethod apply-modifier "head"     [region m] …)
(defmethod apply-modifier "tail"     [region m] …)
(defmethod apply-modifier "position" [region m] …)  ; for destinations only
```

Each modifier is **one Datalog query** against the local API today —
they're already implemented inside `bridge.clj`'s ad-hoc functions, just
not factored out. The refactor is mostly *moving code*, not writing new code.

### Pronouns

```clojure
;; After every successful command:
(swap! pronouns assoc :that  (last-operated-target target-result))
(swap! pronouns assoc :source (when (= action "moveToTarget")
                                (resolve-target state source)))
```

Write the pronoun map back to `__state__.pronouns` so external agents
and the Talon side can read it.

---

## 6 · JS extension (`agent-bridge.js`)

The current 11-arm `switch (type)` has two roles entangled:

1. **Mutate the DOM / Roam UI** (annotate, clear, select-block, scan-blocks, notify, eval).
2. **Pure data ops** (delete-blocks, get-view, nav-mode, nav-off, clear-selection).

The Babashka layer (§5) now owns category 2 entirely — those
operations don't need the JS extension at all once `bridge.clj` calls
`roamAlphaAPI` directly via the Local API.

### What stays in the JS extension

Only the operations that **require browser-context capabilities**:

| JS command (today) | Keep / fold |
|---|---|
| `annotate` | **Keep** — DOM overlay, can't be done from Local API |
| `clear` | **Keep** — DOM overlay |
| `scan-blocks` | **Keep** — reads visible DOM |
| `nav-mode` / `nav-off` | **Keep** — wraps scan + observers |
| `select-block` | **Keep** — DOM highlight + UI focus |
| `clear-selection` | **Keep** — DOM cleanup |
| `notify` | **Keep** — toast |
| `eval` | **Keep** — escape hatch |
| `get-view` | **Fold** — just read `__state__` block |
| `delete-blocks` | **Fold** — `bridge.clj` does it via Local API now |

So the JS bridge shrinks from 11 commands to 8, and stops accepting label
arrays for mutation commands (it only knows about labels for **DOM** ops
like select-highlight). Mutation commands resolve labels in
`bridge.clj`, never in the extension.

### The JS extension also adopts the AST

Even the DOM-side commands take a `target` AST instead of an args bag:

```jsonc
// Before
{ "type":"select-block", "args":{ "uids":["abc","def"], "mode":"focus" } }

// After
{ "version": 1,
  "action": {
    "name": "selectBlockHighlight",                    // DOM-only highlight
    "target": { "type":"list", "elements": [...] },
    "mode":   "focus" } }
```

The JS extension only resolves **label marks** (since it already knows
the label map). Other mark kinds (`uid`, `pageTitle`, `daily`, pronouns,
`phrase`, plus modifiers) are pre-resolved by `bridge.clj` to UIDs
before the JSON ever crosses the JS boundary. The JS extension's
resolver is therefore tiny: `label → uid` lookup only.

### Pre-phrase snapshot

Add `labelsVersion` to every command. The JS extension caches the last
N label snapshots (N=4 is plenty) keyed by `__state__.ts`. On stale
version → reply `{status:"error", result:{error:"stale-labels", currentTs:…}}`.

---

## 7 · Build order

Mirroring §8 of the source doc, in commit-sized chunks:

### Phase A — Schema lock
1. Write `docs/COMMAND-SCHEMA.md` defining the JSON envelope, target
   AST, destination AST, and per-action shapes from §2.
2. Add `version: 1` field check to the JS `processCommand` switch.
   Reject commands without it. (No behaviour change — every existing
   client gets a one-line update.)

### Phase B — Bridge.clj resolver spine
3. Extract `resolve-mark`, `resolve-target`, `apply-modifier` from the
   existing `resolve-source-uids` / `resolve-target` / `transfer!`
   code paths. **No call-site changes yet.** The new resolver is a
   superset of the old one; old fns delegate to it internally.
4. Implement modifier multimethods (`containing`, `every`, `ordinal`,
   `relative`, `head`, `tail`, `position`). Most have direct analogues
   in the existing fns (`get-children-uids`, `get-parent-uid`,
   `ancestor-path`).
5. Add `dispatch` multimethod with **one** action: `setSelection`. Wire
   from a new public fn `(execute! payload)` that takes EDN/JSON.
   Round-trip a single command end-to-end.

### Phase C — Mark and modifier coverage
6. Mark kinds: `label`, `uid`, `pageTitle`, `daily`, `cursor`,
   `selection`. Implement each in `resolve-mark`. (All exist as code
   today — just centralise.)
7. Add `that` and `source` pronouns. Atom + `__state__.pronouns`
   mirror.
8. Add `phrase` (fuzzy text match) — Datalog query against `block.string`.

### Phase D — Action coverage by shape
9. **Single-target shape:** add dispatch for `remove`, `collapse`,
   `expand`, `zoom`, `openInSidebar`, `getText`, `nudge`,
   `addToSelection`, `removeFromSelection`. Each is ~5 lines:
   `(let [uids (resolve-target state target)] (do-thing-on uids))`.
10. **Source+dest shape:** add `moveToTarget`, `linkToTarget`,
    `aliasMove`. Reuse existing `move-uids!` and `link-uids!`. Wire
    `ImplicitTarget` → `selection` pronoun, `ImplicitDestination` →
    "stay under current parent, position end" (= reorder semantics).
11. **Dest-only shape:** add `insertNewBlock`. Replaces `new-block!`,
    `new-sibling!`, `new-child!`, `new-before!`, `new-after!`.
12. **Two-target shape:** add `swap`, `swapContent`. Reuse existing
    `swap-blocks!` body.

### Phase E — Talon surface
13. Add `roam_mark` capture (label / uid / page / daily / pronoun /
    phrase). Replaces overlapping pieces of `roam_source` /
    `roam_destination`.
14. Add `roam_modifier` capture (containing / every / ordinal /
    relative / head-tail). One `@mod.capture` per category, all
    unioned.
15. Add **`roam_target`** capture: `<modifier>+ [<mark>] | <mark>`.
    Returns a dict (Python dict serialised to JSON).
16. Add **`roam_destination`** capture: `<insertion_mode> <target>`.
17. Add Python action `user.roam_action(name: str, target: dict)` that
    builds the envelope and shells `bb -e '(execute! …)'`.
18. **Cut over Talon files one at a time:** `hats.talon` first
    (smallest, most varied — proves the model). Then `block_edit.talon`
    (12 `new-block` rules collapse to 1). Then the rest.

### Phase F — Vocabulary externalisation
19. Move every action ID, scope-type ID, and pronoun out of
    `roam_tmem_ext.py` into CSV files
    (`roam-actions.csv`, `roam-scopes.csv`, `roam-pronouns.csv`).
20. Write a CSV-loader that populates the Talon lists. Mirror
    Cursorless's auto-add-on-missing-row trick to prevent vocabulary
    loss.

### Phase G — JS extension cleanup
21. Add `version` envelope check (warn on missing, error on mismatch).
22. Add `labelsVersion` snapshot cache.
23. Migrate `delete-blocks` and `get-view` callers to read `__state__`
    / call `bridge.clj` directly. Then delete those JS cases.
24. Add the AST resolver for `label` marks (the only mark kind the JS
    side needs). DOM-side commands (`selectBlockHighlight`,
    `annotate`, `scan-blocks`) take an AST list of label marks, not raw
    UID arrays.

### Phase H — Embedded subset (optional, last)
25. Following §11 of the source doc: ship a string-form DSL for the
    LLM-driven path:
    ```
    chuck every child of <target>
    move <target> to first child of <target>
    ```
    Restrict to: single-target, source+dest, primitive only (no range,
    no list), `containing`/`every`/`ordinal`/`position` modifiers,
    label/uid/pronoun/placeholder marks. Implement placeholder as a
    mark kind — `<target>` in the string parses to `{type:"mark",
    kind:"placeholder", index:0}` which is filled in at execute time.

---

## 8 · File-by-file plan

| File | Phase(s) | Change |
|---|---|---|
| `docs/COMMAND-SCHEMA.md` | A | **New.** JSON envelope + target/destination AST + per-action schemas. |
| `bridge.clj` | B–D | Extract resolver, add `execute!`, add `dispatch` multimethod, port action handlers. Delete ~10 narrow public fns once their callers move. |
| `bridge.clj` (cont) | C | Add `pronouns` atom + `__state__.pronouns` mirror. |
| `src/agent-bridge.js` | A, G | Add envelope check; cache `labelsVersion`; remove `delete-blocks` and `get-view` cases; rewrite remaining 8 DOM commands to take target AST. |
| `roam_tmem_ext.py` | E | Replace `roam_source` / `roam_destination` captures with `roam_mark` / `roam_modifier` / `roam_target` / `roam_destination`. Add `user.roam_action(name, target)` action. |
| `roam_tmem_ext.py` (cont) | F | Strip vocabulary lists; load from CSV. |
| `hats.talon` | E | Rewrite — every rule becomes one of the 5 action shapes. ~30 rules → ~10. |
| `block_edit.talon` | E | Replace 12 `new-X` rules with 1 `insert <user.roam_destination>` rule. |
| `tree_edit.talon`, `tree_select.talon`, etc. | E | Migrate any rule that builds Clojure-string fragments to use `user.roam_action`. Pure-keystroke rules (e.g. `key(cmd-shift-a)`) untouched. |
| `roam-actions.csv` | F | **New.** Spoken-form → action-ID. |
| `roam-scopes.csv` | F | **New.** Spoken-form → scope-type. |
| `roam-pronouns.csv` | F | **New.** Spoken-form → pronoun. |
| `docs/EMBEDDED-DSL.md` | H | **New.** String-form subset spec. |

---

## 9 · Migration risks and mitigations

| Risk | Mitigation |
|---|---|
| Big-bang refactor breaks daily voice usage | Phases B–D add the new resolver *behind* existing fns. No spoken commands change until phase E. Old and new entry points coexist for one milestone. |
| Pronoun drift (`that` becomes invalid mid-utterance) | Pre-phrase snapshot via `labelsVersion`. Stale snapshot → command rejected with current `ts`, agent retries. |
| Modifier semantics ambiguity ("first child of A" vs "first sibling of A") | Lock `scope` enum tightly: `child`/`descendant`/`sibling`/`reference`/`mention`/`parent`/`page`/`topLevel`. Anything outside the enum is a parse error. |
| Range targets with cross-tree endpoints | Resolver returns `{:error :range-cross-parent}` if A and C don't share `parent-uid`. Talon surfaces the error as a notify-toast. |
| CSV vocabulary loss (user deletes a row) | Auto-repopulate missing canonical rows on extension load (per Cursorless's "missing-line auto-add" trick). |
| Performance: every modifier = one Local-API round-trip | `bridge.clj` already memoises `(-bridge graph)`. Add per-utterance memoisation of `get-parent-uid` / `get-children-uids` keyed by `labelsVersion`. The same UID won't be re-fetched within one phrase. |

---

## 10 · Quick reference — the rules we adopt

From §12 of the source doc, restated for Roam:

1. **A target is `mark? + modifier*`** — at minimum one or the other.
2. **A range is anchor + connective + active**, anchor optional → `Implicit`. **Roam constraint: same parent.**
3. **A list is element (connective element)\***, single element collapses.
4. **A destination is a target with `insertionMode`** (`to`/`before`/`after`).
5. **Source and destination are different types**, not different positions.
6. **Pronouns** (`cursor`, `selection`, `that`, `source`) **are first-class marks**.
7. **Action lists pick the slot shape** from a fixed catalog (§2).
8. **Public surface is two captures** (`roam_target`, `roam_destination`) **plus a small CRUD action set** (`roam_action`, `roam_action_pair`, `roam_action_dest`, `roam_get_text`, `roam_insert`).
9. **Embedded string DSL is strictly narrower** than the spoken grammar.

Plus three Roam-specific rules:

10. **Modifier is the ONLY way to express "X of Y"** — never bake it into an action name. (`fold-children!` becomes `collapse target={A, every:child}`.)
11. **Mutation commands resolve marks in `bridge.clj`**, not in the JS extension. JS only knows about labels for DOM overlay.
12. **Every state-mutating command carries `labelsVersion`** from the snapshot the agent saw at parse time.

---

## Appendix A · Worked example: end-to-end refactor of `fold children of A`

### Before

```talon
# hats.talon
(fold | expand) (children | kids) [of] <user.letters>:
    user.roam_fn("(fold-children! :{letters_1})")
```

```clojure
;; bridge.clj — dedicated function
(defn fold-children! [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid (throw …))
    (doseq [c (get-children-uids graph uid)]
      (roam-set-block-open graph c false))
    (println …)))
```

### After

```talon
# hats.talon — generic action+target rule
(fold | collapse) <user.roam_target>:
    user.roam_action("collapse", roam_target)
```

The phrase *"fold children of A"* parses as:

```jsonc
{ "version": 1,
  "labelsVersion": 1774648294879,
  "spokenForm": "fold children of A",
  "action": {
    "name": "collapse",
    "target": {
      "type": "primitive",
      "mark": { "type": "label", "value": "A" },
      "modifiers": [ { "type": "every", "scope": "child" } ]
    }
  } }
```

```clojure
;; bridge.clj — generic dispatch
(defmethod dispatch "collapse"
  [_ {:keys [target]} state]
  (let [uids (resolve-target state target)]
    (doseq [uid uids]
      (roam-set-block-open (graph state) uid false))
    {:count (count uids)}))
```

`fold-children!` is gone. So is `unfold-children!`. *"unfold every
descendant of A"* now works for free, without any new code:

```jsonc
{ "type":"primitive",
  "mark":{"type":"label","value":"A"},
  "modifiers":[{"type":"every","scope":"descendant"}] }
```

That's the payoff: **one new modifier scope = every existing action gains a new capability.**

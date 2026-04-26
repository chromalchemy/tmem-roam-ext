# Composable Refactor — Progress Log

Status snapshot of the staged refactor outlined in
[`COMPOSABLE-REFACTOR-PLAN.md`](./COMPOSABLE-REFACTOR-PLAN.md). Updated
after each phase completion.

| Phase | Status | Date | Notes |
|---|---|---|---|
| A — Schema lock | ✅ done | 2026-04-25 | Wire shape locked at `version: 1`. |
| B — Bridge.clj resolver spine | ✅ done | 2026-04-25 | `resolve-target`/`resolve-mark`/`apply-modifier` + `dispatch` w/ `setSelection`. |
| C — Mark + modifier coverage | ✅ done | 2026-04-25 | All 9 mark kinds + pronouns + phrase. |
| D — Action coverage by shape | ⏳ next | — | `remove`, `collapse`, `expand`, `zoom`, `openInSidebar`, `moveToTarget`, `linkToTarget`, `aliasMove`, `insertNewBlock`, `swap`, `swapContent`, `nudge`, etc. |
| E — Talon surface | ⏳ later | — | `roam_target` / `roam_destination` captures + `user.roam_action`. |
| F — Vocabulary externalisation | ⏳ later | — | CSV-driven action/scope/pronoun vocab. |
| G — JS extension cleanup | ⏳ later | — | `version` enforcement + `labelsVersion` cache + drop `delete-blocks`/`get-view`. |
| H — Embedded DSL | ⏳ optional | — | String-form subset for LLM path. |

---

## Architectural pivot — daemon mode (highest-leverage open question)

**Status:** considered, not yet executed. Worth deciding between Phase D and Phase E.

The current architecture shells out to `bb` for every voice command and
treats process death as the cleanup step. This forces:

- Pronouns persisted via `/tmp/` JSON (atoms don't survive)
- Per-utterance memoization (plan §9 perf concern) is impossible
- ~210–380 ms bridge latency per command (mostly bb startup + load-file)
- The `(-bridge graph)` memoize call is dead code (process exits first)
- HTTP client torn down per call

A **long-running bb (or Clojure) nREPL daemon** that voice/CLI/agent
clients eval into would invert all of these.

### Latency comparison

| Stage | Shell mode | Daemon mode |
|---|---|---|
| Talon recognition | ~100 ms | ~100 ms |
| Shell fork | ~50 ms | — |
| `bb` startup + `load-file` | ~150–300 ms | — |
| nREPL eval (warm process) | — | ~3–10 ms |
| Roam Local API call | ~10–30 ms | ~10–30 ms |
| **Total bridge cost** | **~210–380 ms** | **~13–40 ms** |

5–10× speedup on the layer we control. Voice UX flips from "feels
laggy" to "feels live".

### What gets simpler

| Phase C artefact | Daemon mode |
|---|---|
| `pronouns-cache` atom + file mirror | Just the atom. |
| `pronouns-file` / `load-pronouns` / `save-pronouns!` (~30 lines) | Delete; one `(swap! pronouns ...)`. File becomes optional crash snapshot. |
| Per-utterance memoization (plan §9 perf) | Trivial — one atom keyed by `labelsVersion`. |
| `(-bridge graph)` memoize | Actually saves the bridge-UID lookup. |
| HTTP client connection pooling | Just works. |
| Datalevin pod load cost | Amortises to zero (still optional, but cheap to add). |

You'd **delete** ~50 lines from `bridge.clj`, not add any.

### Client tooling: `clj-nrepl-eval` (already installed)

[`clj-nrepl-eval`](https://github.com/bhauman/clojure-mcp-light#clj-nrepl-eval-llm-nrepl-connection-without-an-mcp)
is a CLI binary already on PATH at `/Users/ryan/.local/bin/clj-nrepl-eval`
that turns the daemon migration into a near-trivial change.

**Key features that map directly to the daemon use case:**
- `--discover-ports` auto-finds bb/Clojure nREPLs by scanning `.nrepl-port`
  files and running JVM/Babashka processes — no manual port pinning
- `--connected-ports` lists previously-used connections — Talon can
  remember the right daemon
- **Sessions are persistent by default** — atoms, vars, namespaces, and
  loaded libraries survive across `clj-nrepl-eval` invocations until
  the nREPL server restarts. This is exactly the property we need for
  Phase C's pronoun atoms.
- Heredoc / stdin support for multi-line payloads

**Verified at session-end:** 3 bb nREPLs already running in
`/Users/ryan/dev/tmem-roam-ext` (ports 52547, 53530, 55219).

### Migration becomes a `bb -e ...` → `clj-nrepl-eval ...` swap

**Before (Phase A–C, current):**
```bash
bb -e '(load-file "bridge.clj") (execute! {:version 1 :id "x" :action {:name "setSelection" :target {:type "primitive" :mark {:type "label" :value "A"}}}})'
# ~210–380 ms
```

**After (daemon mode):**
```bash
# One-time per daemon: load the code
clj-nrepl-eval --port 52547 '(load-file "bridge.clj")'

# Every voice command: just call execute!
clj-nrepl-eval --port 52547 '(execute! {:version 1 :id "x" :action {:name "setSelection" :target {:type "primitive" :mark {:type "label" :value "A"}}}})'
# ~13–40 ms
```

**Discovery-driven invocation (no hardcoded port):**
```bash
PORT=$(clj-nrepl-eval --discover-ports | grep '(bb)' | head -1 | awk -F: '{print $2}' | awk '{print $1}')
clj-nrepl-eval --port "$PORT" '(execute! ...)'
```

Talon's `user.roam_action` action becomes a single shell call instead
of a `bb` invocation that re-loads everything from scratch.

### What gets harder (honest list — much shorter than before)

1. **Process lifecycle.** Need supervision — auto-start, restart on
   crash. For dev: a tmux pane running `bb --nrepl-server 0` is enough.
   For production: `launchctl` plist on macOS.
2. **Code reload.** Solved cleanly:
   ```bash
   clj-nrepl-eval --port "$PORT" '(load-file "bridge.clj")'
   ```
   Bind that to a Talon command (`reload bridge`) for instant feedback
   loop.
3. ~~**Port discovery.**~~ Solved by `clj-nrepl-eval --discover-ports`.
4. **Multi-frontend coordination.** Three callers compete:
   - Talon (voice) → daemon via `clj-nrepl-eval`
   - `bridge.bb` CLI → keeps working as-is, OR add a `--daemon` flag
     that routes through `clj-nrepl-eval` instead of self-loading
   - External MCP agents → already daemon-agnostic (HTTP to Roam Local API)
5. **Stale state.** Buggy pronouns persist until restart. Add a
   `(reset-pronouns!)` REPL fn so a single `clj-nrepl-eval` call clears
   it without daemon restart.
6. **Concurrent eval.** nREPL is per-session serial, multi-session
   parallel. For voice this is fine. Agent burst traffic may want
   `locking` around state mutation.
7. **Reload-safe state.** `(def ^:private pronouns-cache (atom {}))` resets
   on every `(load-file)`. Switch to `defonce` for state, leave plain
   `def` for code. (Critical — see Open Questions below.)
8. **Daemon vs dev REPL collision.** The 3 currently-running bb nREPLs
   are likely dev sessions (REPL-driven dev workflow). Don't accidentally
   point Talon at one of those — connecting an editor will reset state.
   Run a separate, supervised daemon process with a known purpose.

### Datalevin calculus changes

In **shell-per-command** mode: Datalevin pod load cost makes it net
negative for any workload smaller than "queryable history". Not worth
it.

In **daemon** mode: pod load is paid once at startup. Datalevin becomes
**affordable** for everything, but still only **necessary** if the state
model needs Datalog queries (action history, undo stack, named
bookmarks, fuzzy historical search). For pronouns alone, atom is still
the right tool.

The daemon move makes Datalevin **viable**, not **automatic**.

### Recommended sequencing

```
Phase D  — implement actions in current shell-per-command model
          (no awareness of daemon question; works either way)
       ↓
[branch] — daemonize bridge.clj as a separate concern
          - start a supervised bb --nrepl-server (separate from dev REPLs)
          - flip relevant `def` → `defonce` for state atoms
          - add per-utterance memoization keyed by labelsVersion
          - replace /tmp file persistence with in-memory atom (snapshot
            file becomes optional crash-recovery)
          - keep bb shell mode working as fallback (set
            *persist-pronouns?* true when not in daemon)
       ↓
Phase E  — Talon points at clj-nrepl-eval instead of `bb -e ...`
          (this is the latency-win commit; ~5–10× speedup)
       ↓
Phase G+ — Datalevin only if/when state model warrants Datalog queries
```

### Concrete daemon bootstrap (when ready)

A minimal recipe to test the migration without committing to it:

```bash
# Terminal 1: start a dedicated daemon (separate from dev REPLs)
cd /Users/ryan/dev/tmem-roam-ext
bb --nrepl-server 7888  &  # pin a port for the daemon

# One-time: prime the daemon
clj-nrepl-eval --port 7888 '(load-file "bridge.clj")'

# Smoke test (compare timing to bb -e ... shell version)
time clj-nrepl-eval --port 7888 '(execute! {:version 1 :id "daemon-test" :action {:name "setSelection" :target {:type "primitive" :mark {:type "label" :value "A"}}}})'

# Inspect live atom state (impossible in shell mode)
clj-nrepl-eval --port 7888 '@#'\''user/pronouns-cache'
```

If the daemon reports the same result as the `bb -e ...` form but
~5–10× faster, the migration is greenlit.

### One pre-emptive refactor worth doing now (Phase D, ~2 min)

Factor `update-pronouns!` so file persistence is a toggleable hook:

```clojure
(def ^:dynamic *persist-pronouns?* true)

(defn- update-pronouns! [graph f]
  (let [updated (swap! pronouns-cache update graph #(f (or % {})))]
    (when *persist-pronouns?*
      (save-pronouns! graph (get updated graph)))
    updated))
```

In daemon mode, set `*persist-pronouns?*` to `false` (or write
periodically as a snapshot). Preserves current behaviour exactly;
makes the daemon flip a one-liner.

### Open questions to revisit at decision time

- **Daemon binary:** bb (faster startup, smaller deps) vs Clojure JVM
  (better ecosystem, slower startup). bb wins for "always on" — and
  `clj-nrepl-eval --discover-ports` already finds them.
- **Daemon vs dev REPL discrimination:** currently
  `clj-nrepl-eval --discover-ports` returns 3 bb processes for this
  directory — those are likely dev REPLs. The daemon needs a
  distinguishing marker (pinned port, or tag a custom var the
  discovery script greps for: `(def ^:private DAEMON :v1)`).
- **Failure semantics:** what does Talon show when the daemon is dead?
  Toast via the agent-bridge `notify` command, or fall through to
  `bb -e ...` automatic fallback?
- **Pronoun snapshot cadence:** every write (current) vs every N seconds
  vs only on shutdown signal? In daemon mode probably "shutdown signal +
  every minute as belt-and-braces".
- **Multi-graph state:** one daemon serving all graphs, or one per
  graph? Probably one global, keyed by `:graph` everywhere (matches
  current pronoun file naming).
- **Reload-safe atoms:** `(def ^:private pronouns-cache (atom {}))`
  resets on every `(load-file)`. Use `defonce` for state atoms
  (`pronouns-cache`, future per-utterance cache), plain `def` for
  code. **This is a 5-line audit pass during the daemonize commit.**
- **`bridge.bb` CLI fallback:** does the CLI try the daemon first and
  fall back to spawning, or does it stay shell-only? If the former,
  pronouns get out-of-sync between daemon-atom and CLI-shelled
  invocations. Pick one source of truth.

---

## What exists in the codebase right now

### Documents

| File | Phase | Purpose |
|---|---|---|
| `docs/COMPOSABLE-REFACTOR-PLAN.md` | — | The North Star. Don't edit; it's the source of truth for the staged plan. |
| `docs/COMMAND-SCHEMA.md` | A | Wire contract: envelope, target/destination AST, mark catalogue, modifier catalogue, action shapes, error codes, pronoun rules, examples. |
| `docs/REFACTOR-PROGRESS.md` | this | Status log. Update after every phase. |
| `AGENT-BRIDGE.md` | — | Legacy `{id,type,args}` command catalogue. Still authoritative for the **JS extension**. Will shrink in Phase G. |

### Code

| File | Touched in | Surface (post-Phase C) |
|---|---|---|
| `src/agent-bridge.js` | A | `processCommand` validates envelope `version`. Missing → warn-and-continue (transitional grace). Mismatched → hard reject `unknown-version`. Otherwise unchanged from pre-A. |
| `bridge.clj` | A, B, C | Legacy public API (`select!`, `move!`, `transfer!`, `swap-blocks!`, `nudge!`, etc.) **still works untouched**. New AST resolver + dispatch + `execute!` lives at the bottom of the file (after `nudge!`). The two surfaces coexist. |
| `probe.bb` | A | One-line update: emits `version: 1`. |

### What's NEW in `bridge.clj` (bottom of file, after `nudge!`)

```
;; ═══ Phase B+C: Composable resolver spine ═══

;; Pronoun persistence
pronouns-file, load-pronouns, save-pronouns!,
pronouns-cache (atom), get-pronouns, update-pronouns!

;; Resolver
resolve-mark*    (defmulti, dispatch on :type string)
  + methods: label uid cursor selection pageTitle daily that source phrase placeholder :default
resolve-mark     (public wrapper)

apply-modifier   (defmulti, dispatch on :type string)
  + methods: containing every ordinal relative head tail position :default
apply-modifiers  (private — folds modifier list)

resolve-target   (defn — primitive | list | range | implicit)

;; Dispatch
dispatch         (defmulti — only "setSelection" implemented)
update-pronouns-after!  (private hook called after dispatch)
execute!         (PUBLIC entry point — takes the v1 envelope)

;; Helpers
err              (private — throw ex-info with :error code)
ascend-n, collect-descendants, ascend-to-page, ascend-to-top-level
pick-by-index    (negative index = from end)
coerce-daily-value
source-slot-actions  (set: moveToTarget linkToTarget aliasMove)
```

### Renamed in B-1

The legacy `resolve-target` (which actually computed a *destination* — parent
uid + order) was renamed to `resolve-destination-legacy`. Two callers updated:
`do-move!`, `do-link!`. Will be deleted in Phase D when `moveToTarget` /
`insertNewBlock` take destination AST.

---

## The wire envelope (current contract)

```jsonc
{
  "version":       1,
  "id":            "cmd-001",
  "spokenForm":    "chuck every child of A",   // optional, debug only
  "labelsVersion": 1774648294879,              // optional, snapshot stamp
  "action": {
    "name":   "setSelection",                  // string, dispatch key
    "target": <Target AST>                     // shape per docs/COMMAND-SCHEMA.md §2
  }
}
```

`execute!` accepts the parsed map. Errors raised via `ex-info` with
`(:error data)` matching schema §9 codes.

### Implemented action surface (Phase C)

Just one action: **`setSelection`**.

It resolves the target to a uid vec and sends a `select-block` JS command.
Same end-effect as the legacy `(select! [:A])`, but reached through the
new AST path. The legacy fn is **not** modified — both work.

### Implemented mark kinds (all 10 from schema §3)

| Mark | Status | Notes |
|---|---|---|
| `label` | ✅ live | Case-insensitive value; reads `__state__.labels`. |
| `uid` | ✅ live | Pass-through. |
| `cursor` | ✅ live | Reads `__state__.focused.block-uid`. |
| `selection` | ✅ live | Reads `__state__.selected[]`. |
| `pageTitle` | ✅ live | Via `get-page-uid`; errors on missing page. |
| `daily` | ✅ live | Coerces strings to `:today`/int/MM-DD-YYYY before resolution. Errors with `:resolved-title` when DNP doesn't exist. |
| `that` | ✅ live | Reads `pronouns[:that][:uids]`. Persists across `bb` invocations. |
| `source` | ✅ live | Reads `pronouns[:source][:uids]`. Will be populated by Phase D's moveToTarget hook (already wired). |
| `phrase` | ✅ live | **Case-sensitive** Datalog `includes?`. See gotcha §1. |
| `placeholder` | 🚧 stub | Throws `not-implemented` w/ `:phase "H"`. |

### Implemented modifier categories (all 7 from schema §4)

| Modifier | Status | Scopes implemented |
|---|---|---|
| `containing` | ✅ live | `parent` (with `ancestorIndex`), `page`, `topLevel` |
| `every` | ✅ partial | `child`, `descendant`, `sibling`. `reference`/`mention` stubbed (Phase D). |
| `ordinal` | ✅ live | `child`, `sibling`. Negative `index` = from end. |
| `relative` | ✅ live | `sibling` only. |
| `head` | ✅ live | `child` only. |
| `tail` | ✅ live | `child` only. |
| `position` | ✅ live | Gated — throws `position-on-target` outside destination context. |

### Target AST variants (all 4 from schema §2)

| Type | Status | Notes |
|---|---|---|
| `primitive` | ✅ live | mark + modifier chain. |
| `list` | ✅ live | mapcat over elements. |
| `range` | ✅ live | Validates same parent → `range-cross-parent` if not. Endpoints recursively resolved with `:destination? false`. |
| `implicit` | ✅ live | Falls back: `selection` → `cursor` → `mark-not-found`. |

---

## Verified live (against running Roam graph "tmem")

These all **pass** as of 2026-04-25 with bridge extension loaded and
`hats-on!` active (9 labels):

```clojure
;; Single label
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive" :mark {:type "label" :value "I"}}}})
;; → {:uids ["YoffzFqlF"], :window_id "main-window", :count 1}

;; pageTitle
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive"
                             :mark {:type "pageTitle" :value "roam-agent/bridge"}}}})

;; daily today
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive"
                             :mark {:type "daily" :value "today"}}}})

;; phrase (case-sensitive substring)
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive"
                             :mark {:type "phrase" :value "salamy"}}}})

;; that pronoun (after any prior execute!)
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive" :mark {:type "that"}}}})

;; modifier composition: every child of A
(execute! {:version 1 :id "test"
           :action {:name "setSelection"
                    :target {:type "primitive"
                             :mark {:type "label" :value "A"}
                             :modifiers [{:type "every" :scope "child"}]}}})
```

### Error contract verified

| Trigger | Error code |
|---|---|
| `version: 0` | `unknown-version` |
| missing `action.name` | `missing-slot` |
| unknown action name | `unknown-action` |
| label resolves to nothing | `mark-not-found` (with `:available` list) |
| `that` with no prior command | `mark-not-found` |
| daily for nonexistent DNP | `mark-not-found` (with `:resolved-title`) |
| `position` modifier outside destination | `position-on-target` |
| range with cross-parent endpoints | `range-cross-parent` |

---

## Important gotchas / design decisions

### 1. `phrase` mark is case-sensitive

Roam's Datalog whitelist excludes `clojure.string/lower-case`. We use
`clojure.string/includes?` directly. Case-insensitive search would require
a full-graph pull + client-side filter (O(n) over all blocks). Not
implemented; documented in `docs/COMMAND-SCHEMA.md` §3. Voice transcription
generally produces consistent casing so this rarely bites.

If you need case-insensitivity later, the implementation pattern is:
```clojure
;; Pull all (uid, string) pairs, filter in Clojure
(let [needle (str/lower-case (str value))
      rows   (roam-q graph "[:find ?uid ?s :where [?b :block/uid ?uid] [?b :block/string ?s]]")]
  (->> rows
       (filter (fn [[_uid s]] (str/includes? (str/lower-case (str s)) needle)))
       (mapv (fn [[uid _]] {:uid uid}))))
```

### 2. Pronouns live in `/tmp`, not `__state__`

The plan's "atom + `__state__.pronouns` mirror" is **split**:

- **Atom** (`pronouns-cache`) is per-process — Clojure runtime constraint
- **File** (`${tmpdir}/roam-bridge-pronouns-{graph}.json`) is the
  cross-`bb`-invocation backbone
- **`__state__.pronouns` mirror** is **explicitly Phase G work**, NOT
  Phase C, because the JS extension owns `__state__` writes via its
  2-second poll. Any write from `bridge.clj` would be clobbered.

When Phase G rewrites `agent-bridge.js`, the pronouns map should be
read from the same `/tmp` JSON file (or, cleaner, the JS extension
gains a new `pronouns` Roam block and `bridge.clj` writes there
instead of `/tmp`).

### 3. The `:destination?` flag

Threaded through ctx, consumed by `apply-modifier "position"`. Range
endpoints reset it to `false` before recursing — a range can't smuggle a
`position` modifier in via its anchor or active. The flag is set to
`true` only by `resolve-target` callers that are resolving the
destination side of a `moveToTarget`/`insertNewBlock`/etc. Phase D will
need to plumb that through:

```clojure
(resolve-target (assoc ctx :destination? true) destination-target)
```

### 4. Region map shape

```clojure
{:uid "..."           ;; always present after successful resolve
 :region "main"|"sidebar"  ;; preserved from label marks; other marks may not have it
 :window-id "..."     ;; preserved from cursor mark; rare elsewhere
 :position "start"|"end"  ;; only after position modifier in destination context
 :page-title "..."    ;; on pageTitle/daily marks for diagnostics
 :daily-value <orig>  ;; on daily marks for diagnostics
 }
```

Only `:uid` is guaranteed. Everything else is opportunistic. Dispatch
methods should handle missing keys gracefully (e.g. fall back to
`"main-window"` for missing `:region`).

### 5. Legacy + new coexist

**No legacy public function was modified or deleted in Phases A–C.**

- `select!`, `select-add!`, `select-remove!` (lines ~499–569)
- `delete!` (~576)
- `zoom!`, `zoom-parent!`, `zoom-out!` (~664–731)
- `fold!`, `unfold!`, `fold-children!`, `unfold-children!` (~747–786)
- `open-sidebar!` (~791)
- `new-block!`, `new-sibling!`, `new-child!`, `new-before!`, `new-after!` (~818–893)
- `move!`, `link!`, `transfer!` (~898–995)
- `swap-blocks!` (~1043)
- `nudge!` (~1099)

All still work exactly as before. The Talon side and `bridge.bb` CLI
keep functioning unchanged. Phase D will add new `dispatch` methods
that **duplicate** their behaviour via the AST path, then Phase E will
re-point Talon to the new entry, and Phase D end-game will delete the
legacy fns.

### 6. Wire-level types are strings, AST keys are keywords

Cheshire's `(json/parse-string s true)` gives:
- map keys → keywords (`:type`, `:value`, `:mark`, `:modifiers`)
- string values stay as strings (`"primitive"`, `"label"`, `"A"`)

So:
- `(:type target)` → `"primitive"` (string)
- `defmulti dispatch` keys are strings
- Action names in `action.name` are strings

This is consistent throughout. Don't accidentally use `:primitive`
keyword somewhere — `case` won't match.

### 7. `update-pronouns-after!` is passive for `:source`

```clojure
src (when (contains? source-slot-actions (:name action))
      (when-let [s (:source action)]
        (try (mapv :uid (resolve-target ctx s))
             (catch Exception _ nil))))
```

It tries to resolve the action's `:source` slot and silently swallows
errors. This means **Phase D's `moveToTarget` dispatch will populate
`:source` automatically without any change to this hook**. Just make
sure `moveToTarget`'s action map has a `:source` key.

### 8. `bb` invocations are ephemeral — atoms don't survive

If you find yourself reaching for `def` or `defonce` to hold state in
`bridge.clj`, **stop**. Use the file-mirror pattern (`pronouns-cache` +
`load-pronouns` + `save-pronouns!` + `update-pronouns!`) as the model.

---

## Files modified (cumulative)

```
M  src/agent-bridge.js          (Phase A: version check)
M  bridge.clj                   (Phase A: send-command! version=1)
                                (Phase B: rename resolve-target → resolve-destination-legacy
                                          + ~290 lines new resolver/dispatch at bottom)
                                (Phase C: ~150 lines added — pronouns, mark coverage,
                                          ctx threading, update hook)
M  probe.bb                     (Phase A: version=1)
A  docs/COMMAND-SCHEMA.md       (Phase A: ~400 lines)
M  docs/COMMAND-SCHEMA.md       (Phase C: phrase case-sensitivity note,
                                          pronouns persistence section)
A  docs/REFACTOR-PROGRESS.md    (this file)
```

No git commits yet. Working tree dirty. Run `git status` / `git diff` to
see all changes.

---

## How to pick up Phase D

### Pre-flight checklist

1. **Read** `docs/COMPOSABLE-REFACTOR-PLAN.md` §7 Phase D (steps 9–12).
2. **Read** `docs/COMMAND-SCHEMA.md` §5 (action shapes) and §7 (implicit
   slot semantics).
3. **Read** this file's "Important gotchas" §3 (destination flag) and
   §7 (source pronoun auto-wires).
4. **Confirm** the bridge is loaded in Roam:
   ```bash
   bb -e '(load-file "bridge.clj") (let [b (#'\''user/-bridge "tmem")] (println "labels:" (count (:labels (read-state (:graph b) (:state-uid b))))))'
   ```
   Should print a positive number. If 0, run `bb -e '(load-file "bridge.clj") (hats-on!)'` first.
5. **Confirm** Phase B/C smoke test still works:
   ```bash
   bb -e '(load-file "bridge.clj") (execute! {:version 1 :id "preflight" :action {:name "setSelection" :target {:type "primitive" :mark {:type "label" :value "A"}}}})'
   ```

### Phase D step ordering

Per the plan §7:

1. **Step 9 — single-target shape:** `remove`, `collapse`, `expand`,
   `zoom`, `openInSidebar`, `getText`, `nudge`, `addToSelection`,
   `removeFromSelection`, `getRefs`. Each is ~5 lines: resolve target,
   call the existing `roam-*` helper, return result map.
2. **Step 10 — source+dest shape:** `moveToTarget`, `linkToTarget`,
   `aliasMove`. Reuse `move-uids!` and `link-uids!`. **`destination?`
   flag must be true** when resolving the destination's target.
   Implicit source = `selection` pronoun. Implicit destination = "stay
   under current parent, end" (= reorder).
3. **Step 11 — dest-only shape:** `insertNewBlock`. Replaces 5 legacy
   fns. Reuse `create-and-focus-block!`.
4. **Step 12 — two-target shape:** `swap`, `swapContent`. Reuse
   `swap-blocks!` body.

### Recommended pattern for each dispatch method

```clojure
(defmethod dispatch "remove"
  [_ {:keys [target]} {:keys [graph commands-uid] :as ctx}]
  (let [region (resolve-target ctx (or target {:type "implicit"}))
        uids   (mapv :uid region)]
    (when (empty? uids)
      (err "missing-slot" {:action "remove" :reason "no uids resolved"}))
    ;; Use existing send-command! to JS bridge for delete-blocks,
    ;; OR call roam-api directly.
    (send-command! graph commands-uid
      (str "ex-rm-" (System/currentTimeMillis)) "delete-blocks"
      {:uids uids})
    {:uids uids :count (count uids)}))
```

Each dispatch method:
- Gets `target`/`source`/`destination` from action map
- Falls back to `{:type "implicit"}` if absent (per schema §7 implicit
  table)
- Resolves via `resolve-target ctx ...` (or with `:destination? true`
  for destinations)
- Returns `{:uids [...] :count N ...}` so `update-pronouns-after!`
  can mirror to `:that`/`:source` automatically.

### Things to NOT do in Phase D

- **Don't modify legacy public fns** (`select!`, `move!`, etc.) — they
  stay until Phase E re-points Talon.
- **Don't touch `processCommand` in `agent-bridge.js`** — that's Phase G.
- **Don't add new mark/modifier types** — Phase C is feature-complete
  for marks and modifiers.
- **Don't write to `__state__.pronouns`** — JS will clobber. Phase G work.

---

## Quick repo orientation (post-Phase C)

```
/Users/ryan/dev/tmem-roam-ext/
├── AGENT-BRIDGE.md              ← legacy command catalogue (JS side)
├── bridge.clj                   ← bb script. ~1500 lines.
│                                  Top: legacy public fns
│                                  Bottom (~line 1170+): NEW Phase B+C resolver
├── probe.bb                     ← scratch eval helper
├── src/agent-bridge.js          ← JS extension. version-check at top of processCommand.
├── extension.js                 ← built output (loaded by Roam)
├── docs/
│   ├── COMPOSABLE-REFACTOR-PLAN.md  ← North Star
│   ├── COMMAND-SCHEMA.md            ← Wire contract (Phase A)
│   └── REFACTOR-PROGRESS.md         ← this file
├── /tmp/roam-bridge-pronouns-tmem.json  ← runtime pronoun state
```

### nREPL ports (as of session)

- `localhost:52547` (bb, /Users/ryan/dev/tmem-roam-ext)
- `localhost:53530` (bb, /Users/ryan/dev/tmem-roam-ext)
- `localhost:55219` (bb, /Users/ryan/dev/tmem-roam-ext)
- `localhost:52102` (clj, ~/.talon/user)

The bb ports may not be the same on next session — discover via
`clojure-mcp__list_nrepl_ports` or check `.nrepl-port` files.

---

## Open questions / followups not yet addressed

- **`labelsVersion` enforcement.** The schema says senders MAY include
  `labelsVersion` and the receiver MAY reject stale snapshots. Currently
  `bridge.clj` ignores it entirely. Decide in Phase G when the JS-side
  cache is added.
- **Reference / mention modifiers** (`every reference to A`, `every
  block mentioning #tag`). Stubbed as `not-implemented` in Phase C.
  Likely Phase D work since they need ref-traversal queries.
- **Range UX:** if anchor/active resolve to multi-uid regions, we take
  `(first ar)`/`(first br)`. Document or generalise.
- **Pronoun TTL:** no expiry — `:that` survives indefinitely. Probably
  fine. If it becomes confusing, add a 60s window check on read.
- **`labelsVersion` cache (4 snapshots) in JS.** Phase G step 22.

---

*Last updated: 2026-04-25, end of Phase C.*

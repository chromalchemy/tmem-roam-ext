# Composable Refactor — Progress Log

Status snapshot of the staged refactor outlined in
[`COMPOSABLE-REFACTOR-PLAN.md`](./COMPOSABLE-REFACTOR-PLAN.md). Updated
after each phase completion.

| Phase | Status | Date | Notes |
|---|---|---|---|
| A — Schema lock | ✅ done | 2026-04-25 | Wire shape locked at `version: 1`. |
| B — Bridge.clj resolver spine | ✅ done | 2026-04-25 | `resolve-target`/`resolve-mark`/`apply-modifier` + `dispatch` w/ `setSelection`. |
| C — Mark + modifier coverage | ✅ done | 2026-04-25 | All 9 mark kinds + pronouns + phrase. |
| D — Action coverage by shape | ✅ done | 2026-04-26 | All 16 actions across 4 shapes. Legacy fns intact (Phase E re-points Talon). |
| E — Talon surface | ✅ done | 2026-04-26 | `roam_mark`/`roam_modifier`/`roam_target`/`roam_destination` captures + `user.roam_action`/`_pair`/`_dest`/`_swap`/`_nudge` actions. `hats.talon` + `tree_edit.talon` migrated. |
| F — Vocabulary externalisation | ✅ done | 2026-04-26 | 5 `.talon-list` files in `~/.talon/user/roam-vocabulary/` (native Talon auto-load, zero Python). New `{user.roam_action_verb}` list collapses 6 single-target rules in `hats.talon` into 1 generic rule. |
| G — JS extension cleanup | ✅ done | 2026-04-26 | `version` hard-reject (step 21, already done). `labelsVersion` 4-snapshot ring buffer (step 22). Removed `delete-blocks` + `get-view` cases (step 23). Label-mark AST resolver + `select-block` accepts `target` AST (step 24). |
| H — Embedded DSL | ⏳ optional | — | String-form subset for LLM path. |

---

## Architectural pivot — daemon mode (deferred — pure latency knob)

**Status:** considered and deferred. Phase D shipped in the shell-per-
command model. Phase E will too. Daemonize only when latency actually
annoys, not preemptively.

> **TL;DR for future sessions:** The daemon is a 5–10× speedup, nothing
> else. It is **not** required for correctness, **not** unblocking any
> phase, **not** changing the wire contract. The `/tmp` pronoun file
> already bridges state across `bb` invocations. The Phase D dynamic
> `*persist-pronouns?*` flag preserves the daemon path as a one-line
> opt-in, but flipping it isn't urgent. Migration when it happens is
> a one-line Talon change (`bb -e ...` → `clj-nrepl-eval --port ...`)
> plus a `def` → `defonce` audit (~5 lines).

The remainder of this section is preserved for reference but should
**not** be acted on without explicit user direction.

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

| File | Touched in | Surface (post-Phase D) |
|---|---|---|
| `src/agent-bridge.js` | A | `processCommand` validates envelope `version`. Missing → warn-and-continue (transitional grace). Mismatched → hard reject `unknown-version`. Otherwise unchanged from pre-A. |
| `bridge.clj` | A, B, C, D | Legacy public API (`select!`, `move!`, `transfer!`, `swap-blocks!`, `nudge!`, etc.) **still works untouched**. New AST resolver + dispatch + `execute!` lives at the bottom of the file. ~2160 lines total (~480 added in Phase D). The two surfaces coexist. |
| `probe.bb` | A | One-line update: emits `version: 1`. |

### What's NEW in `bridge.clj` (bottom of file, after `nudge!`)

```
;; ═══ Phase B+C: Composable resolver spine ═══

;; Pronoun persistence
pronouns-file, load-pronouns, save-pronouns!,
pronouns-cache (atom), get-pronouns, update-pronouns!,
*persist-pronouns?* (^:dynamic, default true; daemon-mode flips it false)

;; Resolver
resolve-mark*    (defmulti, dispatch on :type string)
  + methods: label uid cursor selection pageTitle daily that source phrase placeholder :default
resolve-mark     (public wrapper)

apply-modifier   (defmulti, dispatch on :type string)
  + methods: containing every ordinal relative head tail position :default
apply-modifiers  (private — folds modifier list)

resolve-target   (defn — primitive | list | range | implicit)

;; ═══ Phase D: Implicit-slot + destination helpers ═══

resolve-target-implicit       (per-action implicit fallback for target slot)
resolve-source-implicit       (per-action implicit fallback for source slot)
resolve-destination           (insertionMode + position → {:parent-uid :order …})
resolve-destination-implicit  (per-action implicit fallback for destination slot)
pick-window-id                (cursor :window-id > sidebar > main)

src-uid-maps                  (uids → move-uids! shape)
dest-tgt-shape                (resolved-dest → legacy move-uids! shape)
swap-uids!                    (port of swap-blocks! body, takes uids)

;; ═══ Phase D: Dispatch methods (16 total) ═══

dispatch (defmulti):
  setSelection  addToSelection  removeFromSelection
  remove  collapse  expand  zoom  openInSidebar
  getText  getRefs  nudge
  moveToTarget  aliasMove  linkToTarget
  insertNewBlock
  swap  swapContent
  :default → unknown-action

update-pronouns-after!  (now prefers result :source-uids over AST re-resolve)
execute!                (PUBLIC entry point — takes the v1 envelope)
execute-from-file!      (Phase E PUBLIC entry — slurps JSON envelope from
                         a /tmp file, calls execute!, deletes file on success.
                         Avoids shell-quoting hell when Talon embeds payloads
                         containing apostrophes or embedded quotes.)

;; Helpers (legacy)
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

### Implemented action surface (Phase D)

All 16 actions across 4 shapes — full target/source/destination AST.

| Shape | Actions |
|---|---|
| Single-target (§5.1) | `setSelection` ✅ • `addToSelection` ✅ • `removeFromSelection` ✅ • `remove` ✅ • `collapse` ✅ • `expand` ✅ • `zoom` ✅ • `openInSidebar` ✅ • `getText` ✅ • `getRefs` ✅ • `nudge` ✅ |
| Source+destination (§5.2) | `moveToTarget` ✅ • `linkToTarget` ✅ • `aliasMove` ✅ |
| Destination-only (§5.3) | `insertNewBlock` ✅ |
| Two-target (§5.4) | `swap` ✅ • `swapContent` ✅ |
| Scope (§5.5) | not yet — `setNavMode`/`foldEveryAtDepth` (Phase E may not need) |
| Pass-through (§5.6) | not yet — `executeRoamCommand`/`eval` (likely Phase G/H) |

All legacy public fns (`select!`, `move!`, `transfer!`, `swap-blocks!`,
…) **still work untouched**. Phase E re-points Talon to the new entry
(✅ done as of 2026-04-26). Legacy fns can be deleted in Phase F or G
once voice surface is fully verified by daily use.

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

### Phase F vocabulary externalisation (verified 2026-04-26)

| Test | Verification | Result |
|---|---|---|
| List load (5 .talon-list files) | Talon auto-loads from `roam-vocabulary/*.talon-list`; counted entries per list | ✓ pronoun=8, action_verb=12, containing=4, every=14, ordinal=4 |
| Legacy mapping preserved | Read `fold` from `roam-actions.csv` after load | ✓ resolves to `collapse` (intentional cross-mapping) |
| Auto-add (lossy edit) | Stripped 10 of 12 rows from `roam-actions.csv`, called populate, checked CSV file | ✓ 8 default rows re-appended; `openInSidebar`/`expand`/`collapse`/`zoom` IDs all restored with their default spoken forms |
| Auto-add (missing file) | `rm roam-actions.csv`, populate, check | ✓ file recreated with all 12 default rows; in-memory list size = 12 |
| Bridge round-trip: chuck → remove | `/tmp/smoke-a.json` with `{action:remove, target:label A}` → `execute-from-file!` | ✓ `:deleted [R2ANBS2_6]`, file auto-deleted |
| Bridge round-trip: take + every:child | `/tmp/smoke-b.json` setSelection w/ modifier chain | ✓ `:uids [...] :count 1` |
| Bridge round-trip: zoom A | `/tmp/smoke-c.json` zoom + label | ✓ `:uid B0KYo_F-7 :count 1` |
| Bridge round-trip: fold A (legacy mapping) | `/tmp/smoke-d.json` collapse + label (Talon CSV pre-resolves `fold`→`collapse`) | ✓ `:count 1` |

Talon-engine grammar verification (voice → capture → envelope) deferred
to user voice-test alongside Phase E smoke rules.

### Phase E Talon surface (verified 2026-04-26 via simulated envelopes)

Talon was not running during the session, so voice paths were verified
by **simulating envelopes** that match what each new capture/action
would produce, then dispatching through `execute-from-file!`. All green:

| Spoken form | Envelope shape | Result |
|---|---|---|
| `take A` | setSelection + label | `{:uids [J58KBAAEZ] :count 1}` |
| `take every child of A` | setSelection + label + every:child | `{:uids [...] :count 5}` |
| `take parent of A` | setSelection + label + containing:parent | `{:uids [page-uid] :count 1}` |
| `take that` | setSelection + that pronoun | resolves last result |
| `zoom A` / `fold A` / `unfold A` | zoom/collapse/expand + label | each `{:uids [...] :count 1}` |
| `new top child of A` | insertNewBlock + label + position:start | `{:uid "nb-..." :destination {...}}` |
| `new block before C` | insertNewBlock + insertionMode:before + label | success + cleanup verified |
| `move A to first` | moveToTarget + src=A + dest={parent of A, position:start} | reorder works |
| `nudge A down` | nudge + label + direction | `{:uid ... :direction down}` |
| insert→remove round-trip | insertNewBlock then remove | both succeed; pronoun roundtrip works |

Voice-path verification (next session): a temporary `phase_e_smoke.talon`
file was added with `phase smoke take/fold/unfold/zoom/insert/remove`
rules. User speaks each phrase to confirm Talon-side capture parses
match what bb-shell smoke confirmed. Delete the file once voice flow
is confirmed stable.

### Phase D actions (verified 2026-04-26)

End-to-end tests, all green, against bridge with `hats-on!` (29
labels) on graph `tmem`:

```clojure
;; Single-target read-only
(execute! {... :action {:name "getText" :target {:type "primitive" :mark {:type "label" :value "A"}}}})
;; → {:uids [...] :texts [{:uid "..." :string "..."}] :count 1}

(execute! {... :action {:name "getRefs" :target {:type "primitive" :mark {:type "label" :value "A"}}}})
;; → {:uids [...] :refs [{:uid "..." :string "..." :target "..."}] :count N}

;; Single-target benign mutation (reversible)
(execute! {... :action {:name "collapse" :target ...}})  ;; → {:uids [...] :count 1}
(execute! {... :action {:name "expand"   :target ...}})
(execute! {... :action {:name "zoom"     :target ...}})  ;; navigates main window
(execute! {... :action {:name "openInSidebar" :target ...}}) ;; opens sidebar pane

;; Selection mutation
(execute! {... :action {:name "addToSelection"      :target ...}})  ;; combines with current
(execute! {... :action {:name "removeFromSelection" :target ...}})  ;; subtracts

;; Insert / move / alias / link / remove round-trip
(let [r (execute! {... :action {:name "insertNewBlock" :string "test"
                                :destination {:insertionMode "to"
                                              :target {:type "primitive"
                                                       :mark {:type "label" :value "A"}
                                                       :modifiers [{:type "position" :at "end"}]}}}})
      uid (:uid r)]
  (execute! {... :action {:name "moveToTarget"
                          :source {:type "primitive" :mark {:type "uid" :value uid}}
                          :destination {:insertionMode "to"
                                        :target {:type "primitive"
                                                 :mark {:type "uid" :value "DEST"}
                                                 :modifiers [{:type "position" :at "end"}]}}}})
  ;; alternative: linkToTarget creates ((uid)) ref, aliasMove leaves alias behind
  (execute! {... :action {:name "remove" :target {:type "primitive" :mark {:type "uid" :value uid}}}}))

;; Two-target swap (sibling positions)
(execute! {... :action {:name "swap"
                        :target1 {:type "primitive" :mark {:type "uid" :value "A"}}
                        :target2 {:type "primitive" :mark {:type "uid" :value "B"}}}})
;; → {:uids [a b] :mode :swapped|:nested-content|:nested-positional :count 2}

;; Pronoun round-trip across actions
(execute! {... :action {:name "insertNewBlock" :string "x" :destination ...}})
(execute! {... :action {:name "setSelection" :target {:type "primitive" :mark {:type "that"}}}}) ;; selects new block
(execute! {... :action {:name "moveToTarget" :source ... :destination ...}})
(execute! {... :action {:name "setSelection" :target {:type "primitive" :mark {:type "source"}}}}) ;; selects moved
```

### Phase B–C verifications (still passing)

These all **pass** as of 2026-04-25 with bridge extension loaded and
`hats-on!` active (29 labels):

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
**Phase D added** `^:dynamic *persist-pronouns?*` (default `true`) so
the file-mirror is toggleable: in daemon mode (Phase E) flip it to
`false` and the atom alone is the source of truth, with periodic /
shutdown snapshots.

### 9. `:source` pronoun must be captured **pre-action**

The `update-pronouns-after!` hook used to silently re-resolve the
action's `:source` AST after dispatch. That fails for modifier-based
sources — `{:every :child of B}` resolves to *current* children of B,
which post-`moveToTarget` is no longer the moved blocks.

**Phase D fix:** dispatch methods for `moveToTarget`/`linkToTarget`/
`aliasMove` capture source uids *pre-action* and return them in the
result map under `:source-uids`. `update-pronouns-after!` now prefers
that captured value, falling back to AST re-resolve only for stable
mark types (label / uid). Schema §8 update rules now match
implementation: "source uids (resolved pre-action)".

### 10. Stale labels in `__state__` (testing gotcha)

While verifying Phase D's destructive actions (move, alias, remove),
saw label `B` resolve to a deleted prior test block (`nb-3545cc89-`).
Root cause: nav-mode auto-relabels visible blocks, but a freshly-
deleted block's label entry can persist in `__state__` until the next
JS poll. Workaround for testing: use `uid` marks (which always pass
through unchanged) when round-tripping through insert → mutate →
remove. Production voice flow doesn't hit this because labels stay
fresh between utterances.

### 11. `swapContent` on non-nested = position swap (legacy semantics)

`swap-blocks!` only honours the `:content` flag for **nested** pairs
(parent-child or deeper). For sibling pairs, both `swap` and
`swapContent` perform a position swap. Schema §5.4 says swapContent
"swaps the two blocks' string content (children stay put)" without
qualifying nested-only. Phase D dispatch matches legacy. If the schema
needs to diverge, add a sibling-content branch in `swap-uids!`.

### 13. Phase E: Talon `roam_target` modifier order is reversed for AST

Spoken form reads outside-in: "parent of every child of A". The AST
modifier list applies left-to-right against the mark, so it reads
inside-out: `[{every:child}, {containing:parent}]` with `mark=A`.

The `roam_target` Talon capture **collects modifiers in spoken order
then `list(reversed(...))`** to produce the AST list. If you add a new
modifier or change capture grammar, preserve this reversal — bridge.clj's
resolver applies modifiers strictly left-to-right starting from the
mark's region.

### 14. Phase E: cursor-anchored destinations need fresh `__state__`

`(insert | new) top block` produces an envelope with target =
`{cursor + containing:page + position:start}`. If `__state__.focused.block-uid`
points to a deleted/stale uid (e.g. immediately after running automated
tests that just deleted blocks), `containing:page` may fail to resolve a
parent UID and the create-block call errors with `"Parent entity doesn't
exist"`.

In real voice flow this isn't a problem — the JS extension polls every
~2s and the user has multi-second gaps between voice commands. For
automated CI / smoke tests, prefer label-anchored destinations
(`{label:A + position:start}`) over cursor-anchored ones.

### 15. Phase E: legacy `roam_action` Talon list renamed

The Talon list `roam_action` (move/link/alias verb keywords for the old
`transfer!` Clojure fn) was renamed to `roam_transfer_verb` in Phase E
to free the name for the new Python action `user.roam_action(name, target)`.
The legacy Clojure `transfer!` fn itself is **untouched**; only the Talon
list and capture were renamed.

### 16. Phase E: `roam_destination` capture replaces a legacy capture

The legacy `roam_destination` Talon capture (which returned a Clojure
kv-string fragment like `:label :A` or `:page "Tasks"`) was renamed to
`roam_destination_legacy` and is **no longer wired into any active
Talon rule**. The new `roam_destination` capture returns a Python dict
matching the schema §6 destination AST. The legacy capture lingers in
`roam_tmem_ext.py` as dead code — safe to delete in Phase F.

### 12. `insertNewBlock` order coercion

Schema destination §6 produces `{:order 0 | "last"}`. Roam's
`data.block.create` API accepts either int or `"last"` string.
`insertNewBlock` dispatches both correctly via a small `cond` inside
the method. Don't try to send `:first`/`:last` keywords to the API —
they're internal to legacy `create-and-focus-block!`.

### 17. Phase F: list declarations live in `.talon-list` files

Each `.talon-list` file in `roam-vocabulary/` declares its own list via
the `list: user.roam_*` header. Talon auto-loads these natively — no
Python `mod.list()` or `ctx.lists[...]` assignment needed. The captures
referencing `{user.roam_pronoun}` etc. live in `roam_tmem_ext.py`.
Talon resolves list names globally, so cross-file references work.
**Only one source may declare a given list name** — if you also declare
it in Python, behaviour is undefined.

### 18. Phase F: `.talon-list` files are hot-reloaded by Talon

Talon watches `.talon-list` files and reloads them on save. No Python
`app.register("ready", ...)` dance needed. Lists are available as soon
as Talon finishes loading the file.

### 19. Phase F: no auto-add safety net (by design)

The original CSV loader had auto-add-on-missing logic. With native
`.talon-list` files this is gone — if you delete a line, the spoken
form is gone until you re-add it. This is acceptable because the files
are version-controlled and you're the sole user.

### 20. Phase F: action-verb generic rule and rule-specificity

`{user.roam_action_verb} <user.roam_target>` in `hats.talon` is the
most general rule covering the 6 single-target verbs. Compound rules
(`take A and B`, `mark A done`, `take A classic`, `(zoom | load) (out
| top)`, etc.) win via Talon's specificity ranking. **Watch out** when
adding a new compound rule whose first word is also a CSV action verb
— Talon will pick the more specific rule, but if they tie on
specificity the behaviour is engine-defined. Run the generic rule's
spoken form first to confirm dispatch.

### 21. TalonScript bodies cannot contain inline dict/list literals

TalonScript (the body language of `.talon` files) is **not Python**.
Its argument grammar accepts only `STRING | LONG_STRING | NAME | NUMBER
| BOOLEAN`. An inline `{...}` or `[...]` literal raises a Lark
`UnexpectedToken` at file-load time and the rule is silently skipped:

```
ERROR Failed to parse TalonScript in "..." for "(phase | face) smoke take that"
   user.roam_action("setSelection", {"type":"primitive","mark":{"type":"that"}})
                                    ^ Expected: BOOLEAN, LONG_STRING, NAME, NUMBER, STRING
```

The file as a whole still loads — just with the broken rules dropped —
which makes this hard to spot: `phase smoke take A` works, `phase
smoke take that` fails *silently* with no voice acknowledgment.

**Discovery path**: `tail -f ~/.talon/talon.log` after a save catches
the parse error immediately.

**Fix**: never hand-build dict/list literals in `.talon` bodies. Either
(a) use the production captures (`<user.roam_target>`,
`<user.roam_destination>`) which build the dict in Python and pass it
through transparently, or (b) call a Python helper action that builds
the dict from positional args.

This bit Phase E's smoke harness — 4 of 8 rules used inline dicts and
all 4 silently failed. Caught during voice verification at end of
Phase F. The smoke file (now deleted) was rewritten using captures
only and all 8 rules passed.

---

## Files modified (cumulative)

```
M  src/agent-bridge.js          (Phase A: version check)
M  bridge.clj                   (Phase A: send-command! version=1)
                                (Phase B: rename resolve-target → resolve-destination-legacy
                                          + ~290 lines new resolver/dispatch at bottom)
                                (Phase C: ~150 lines added — pronouns, mark coverage,
                                          ctx threading, update hook)
                                (Phase D: ~480 lines added — implicit/destination
                                          helpers, 16 dispatch methods, swap-uids!,
                                          *persist-pronouns?*, pronoun-after upgrade)
                                (Phase E: ~15 lines added — execute-from-file! helper)
M  probe.bb                     (Phase A: version=1)
A  docs/COMMAND-SCHEMA.md       (Phase A: ~400 lines)
M  docs/COMMAND-SCHEMA.md       (Phase C: phrase case-sensitivity note,
                                          pronouns persistence section)
A  docs/REFACTOR-PROGRESS.md    (this file)

# Phase E (Talon side, in /Users/ryan/.talon/user/ryan/roam/)
M  roam_tmem_ext.py             (renamed roam_action list → roam_transfer_verb,
                                 renamed roam_destination capture → _legacy,
                                 added ~140 lines: roam_pronoun/insertion_mode/
                                 containing/every/ordinal/position lists, mark/
                                 modifier/target/destination captures, 5 Python
                                 actions: roam_action, roam_action_pair,
                                 roam_action_dest, roam_swap, roam_nudge, plus
                                 _write_envelope/_execute_envelope helpers)
M  hats.talon                   (rewrote 30+ rules across select/fold/zoom/sidebar/
                                 transfer/swap/nudge/delete sections to use
                                 user.roam_action(name, target) and friends.
                                 Legacy edit-mode rules preserved; legacy bridge
                                 utility rules (hats-on!/hats-off!) unchanged.)
M  tree_edit.talon              (12 new-block roam_fn rules → 12 inline-dict
                                 rules calling user.roam_action_dest. Spoken
                                 forms preserved; only the wire layer changed.)
A→D phase_e_smoke.talon       (temporary voice-test harness — added Phase E,
                                 deleted post-Phase-F after voice verification.
                                 Not committed at any point. See gotcha §21
                                 for the inline-dict-literal lesson it taught.)

# Phase F (Talon side, in /Users/ryan/.talon/user/)
A  roam-vocabulary/                         (NEW directory)
A  roam-vocabulary/roam_pronoun.talon-list          (8 rows)
A  roam-vocabulary/roam_action_verb.talon-list      (12 rows)
A  roam-vocabulary/roam_containing_scope.talon-list (4 rows)
A  roam-vocabulary/roam_every_scope.talon-list      (14 rows)
A  roam-vocabulary/roam_ordinal_scope.talon-list    (4 rows)
D  ryan/roam/roam_csv.py        (DELETED — replaced by native .talon-list files)
M  ryan/roam/roam_tmem_ext.py   (-41 lines: removed inline ctx.lists assignments
                                 + mod.list declarations — now declared in
                                 .talon-list file headers. Captures unchanged.)
M  ryan/roam/hats.talon         (-2 lines net, +19/-21 footprint: collapsed 6
                                 single-target action rules into one generic
                                 `{user.roam_action_verb} <user.roam_target>`
                                 rule. Compound rules unchanged.)
```

# Phase G (JS extension, in /Users/ryan/dev/tmem-roam-ext/)
M  src/agent-bridge.js          (Phase G: +labelsVersion ring buffer, +resolveTarget
                                 AST resolver, +select-block accepts target AST,
                                 -delete-blocks case, -get-view case. Net ~-30 LoC.)
M  extension.js                 (rebuilt from webpack)
```

`bridge.clj` end-state: ~2175 lines (no Phase F or G changes to bridge.clj).

Phases A+B+C committed in `b1ff03d`. Phases D+E committed in `c384049`
(tmem-roam-ext) and `dc9e69d` (~/.talon/user). Phase F+G to be committed.

---

## Phase G — completed (2026-04-26)

### What was done

1. **Step 21 (version check):** Already hard-rejecting missing/mismatched
   versions since Phase A tightening. No change needed.
2. **Step 22 (labelsVersion cache):** Added 4-snapshot ring buffer.
   `snapshotLabels()` called every time labels update. State JSON now
   includes `labelsVersion` field. Commands with a stale `labelsVersion`
   get rejected with `{error: "stale-labels", current: <ts>}`.
3. **Step 23 (remove dead cases):** Deleted `delete-blocks` and `get-view`
   switch cases. The new dispatch "remove" in bridge.clj already uses
   Local API directly. `get-view` data was always available via __state__.
4. **Step 24 (AST resolver):** Added `resolveTarget(target)` function
   (handles `primitive` label/uid marks and `list` targets). `select-block`
   now accepts `args.target` as alternative to `args.uids`.

JS extension: 9 commands (was 11). ~40 net lines removed.

### What remains for future cleanup

- Legacy `roam_destination_legacy` / `roam_source` / `roam_source_base`
  captures in `roam_tmem_ext.py` are dead code (safe to delete).
- Legacy ~25 bridge.clj public fns become deletion candidates after ≥1
  week of daily verified voice usage.
- Daemon mode: revisit when latency annoys.

---

## Quick repo orientation (post-Phase G)

```
/Users/ryan/dev/tmem-roam-ext/
├── AGENT-BRIDGE.md              ← legacy command catalogue (JS side)
├── bridge.clj                   ← bb script. ~2175 lines.
│                                  Top (line 1–1170): legacy public fns
│                                  Bottom (~line 1170+): Phase B–D resolver
│                                                        + 16 dispatch methods
│                                  Bottom-most (~line 2117+): execute-from-file!
│                                                        (Phase E entry point)
├── probe.bb                     ← scratch eval helper
├── src/agent-bridge.js          ← JS extension. 9 commands. labelsVersion cache +
│                                  resolveTarget AST resolver. Phase G cleanup done.
├── extension.js                 ← built output (loaded by Roam)
├── docs/
│   ├── COMPOSABLE-REFACTOR-PLAN.md  ← North Star
│   ├── COMMAND-SCHEMA.md            ← Wire contract (Phase A)
│   └── REFACTOR-PROGRESS.md         ← this file
├── /tmp/roam-bridge-pronouns-tmem.json  ← runtime pronoun state
└── /tmp/roam-bridge-cmd-*.json          ← Phase E voice-command envelopes
                                          (one per command, deleted post-execute)

# Talon side (Phase E + F)
/Users/ryan/.talon/user/
├── roam-vocabulary/             ← Phase F: native .talon-list vocabulary
│   ├── roam-pronouns.csv        ← 8 rows → user.roam_pronoun
│   ├── roam-actions.csv         ← 12 rows → user.roam_action_verb
│   └── roam-scopes.csv          ← 22 rows → user.roam_{containing,every,ordinal}_scope
│                                  (Modifier kind column routes rows to lists)
└── ryan/roam/
    │                              (roam_csv.py DELETED — .talon-list files are native)
    ├── roam_tmem_ext.py         ← Phase E captures + Python actions.
    ├── hats.talon               ← Phase E migration + Phase F generic rule
    │                              `{user.roam_action_verb} <user.roam_target>`.
    ├── tree_edit.talon          ← migrated: 12 new-block rules → roam_action_dest
    ├── block_edit.talon         ← unchanged (text-editing within block, not
    │                              action surface)
    └── (other .talon files)     ← unchanged (keystroke-only, no roam_fn calls)
                                   (phase_e_smoke.talon deleted post-Phase F
                                   voice verification — see gotcha §21)
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
  block mentioning #tag`). Stubbed as `not-implemented` in Phase C,
  **still stubbed** post-Phase D since no Talon rule today requests
  them. Add when E surfaces a spoken form for "every reference to A"
  — `getRefs` action already does the underlying ref-traversal query
  and can be lifted into a modifier in ~10 lines.
- **Range UX:** if anchor/active resolve to multi-uid regions, we take
  `(first ar)`/`(first br)`. Document or generalise.
- **Pronoun TTL:** no expiry — `:that` survives indefinitely. Probably
  fine. If it becomes confusing, add a 60s window check on read.
- **`labelsVersion` cache (4 snapshots) in JS.** Phase G step 22.

---

*Last updated: 2026-04-26, Phase G complete (JS extension cleanup: labelsVersion
cache, removed delete-blocks/get-view, added AST resolver + target support for
select-block). Phase F migrated from CSV to native .talon-list files.*

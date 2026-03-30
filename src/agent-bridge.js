/**
 * agent-bridge.js — Roam Depot extension
 *
 * Bridges an external MCP agent (via Roam Local API) to the in-browser
 * roamAlphaAPI surface. Provides:
 *
 *   1. Block annotation overlays (visual index badges)
 *   2. Proactive view-state reporting
 *   3. Arbitrary JS eval
 *   4. Toast notifications
 *
 * ── Protocol ──────────────────────────────────────────────────────────
 *
 * Control page: "roam-agent/bridge"
 *
 *   __commands__
 *     └─ {"id":"cmd-001","type":"annotate","args":{...}}   ← agent writes
 *         └─ {"id":"cmd-001","status":"done","result":{}}  ← bridge writes
 *
 *   __state__
 *     └─ {"ts":1234,"main":{...},"sidebar":[...],"focused":...}
 *
 * Command types:
 *   nav-mode    — {scope?: "main"|"sidebar"|"all"}  ← persistent auto-labelling
 *   nav-off     — {}                                 ← turn off auto-labelling
 *   annotate    — {blocks: [{uid, label, intent?}]}
 *   clear       — {}
 *   get-view    — {}
 *   scan-blocks — {scope?: "main"|"sidebar"|"all", include_text?: bool}
 *   eval        — {code: "..."}
 *   select-block— {uid|uids, window_id?, mode?: "focus"|"edit"}  ← highlight or edit block(s)
 *   notify      — {message: "...", intent?: "info"|"warning"|"error"|"success"}
 *
 * ─────────────────────────────────────────────────────────────────────
 */

import badgeCSS from "./agent-bridge.css";

// ── Constants ────────────────────────────────────────────────────────

const BRIDGE_PAGE = "roam-agent/bridge";
const COMMANDS_HEADING = "__commands__";
const STATE_HEADING = "__state__";
const BADGE_CLASS = "agent-badge";
const ANNOTATED_CLASS = "agent-annotated";
const POLL_INTERVAL_MS = 2000; // view-state reporting cadence

// ── State ────────────────────────────────────────────────────────────

let bridgePageUid = null;
let commandsBlockUid = null;
let stateBlockUid = null;
let styleEl = null;
let blockObserver = null;
let pullWatchCallback = null;
let stateInterval = null;
let currentAnnotations = []; // [{uid, label, intent}]
let processedCommandIds = new Set();
let navModeActive = false;
let navModeScope = "all";
let renderingInProgress = false; // suppress observer during our own DOM writes

// ── Helpers ──────────────────────────────────────────────────────────

function generateUID() {
  return window.roamAlphaAPI.util.generateUID();
}

/**
 * Generate short alphabetic labels: A, B, ... Z, AA, AB, ... AZ, BA, ...
 * Like Vimium hint labels, but pure alpha for readability.
 */
function indexToLabel(i) {
  let label = "";
  let n = i;
  do {
    label = String.fromCharCode(65 + (n % 26)) + label;
    n = Math.floor(n / 26) - 1;
  } while (n >= 0);
  return label;
}

function getPageUid(title) {
  const result = window.roamAlphaAPI.q(
    `[:find ?uid :where [?e :node/title "${title}"] [?e :block/uid ?uid]]`
  );
  return result?.[0]?.[0] || null;
}

function getChildByString(parentUid, str) {
  const result = window.roamAlphaAPI.q(
    `[:find ?uid ?s :where
      [?p :block/uid "${parentUid}"]
      [?p :block/children ?c]
      [?c :block/uid ?uid]
      [?c :block/string ?s]
      [(clojure.string/starts-with? ?s "${str}")]
    ]`
  );
  return result?.[0]?.[0] || null;
}

function getChildren(parentUid) {
  const result = window.roamAlphaAPI.q(
    `[:find ?uid ?s ?order :where
      [?p :block/uid "${parentUid}"]
      [?p :block/children ?c]
      [?c :block/uid ?uid]
      [?c :block/string ?s]
      [?c :block/order ?order]
    ]`
  );
  return (result || [])
    .map(([uid, string, order]) => ({ uid, string, order }))
    .sort((a, b) => a.order - b.order);
}

async function ensureBlock(parentUid, text) {
  let uid = getChildByString(parentUid, text);
  if (!uid) {
    uid = generateUID();
    await window.roamAlphaAPI.data.block.create({
      location: { "parent-uid": parentUid, order: "last" },
      block: { uid, string: text },
    });
  }
  return uid;
}

async function ensureBridgePage() {
  bridgePageUid = getPageUid(BRIDGE_PAGE);
  if (!bridgePageUid) {
    bridgePageUid = generateUID();
    await window.roamAlphaAPI.data.page.create({
      page: { title: BRIDGE_PAGE, uid: bridgePageUid },
    });
  }
  commandsBlockUid = await ensureBlock(bridgePageUid, COMMANDS_HEADING);
  stateBlockUid = await ensureBlock(bridgePageUid, STATE_HEADING);
}

// ── CSS Injection ────────────────────────────────────────────────────

function injectStyles() {
  styleEl = document.createElement("style");
  styleEl.id = "agent-bridge-styles";
  // css-loader returns an object with a `toString()` or is a string
  styleEl.textContent =
    typeof badgeCSS === "string"
      ? badgeCSS
      : badgeCSS?.toString?.() || badgeCSS?.[0]?.[1] || "";
  document.head.appendChild(styleEl);
}

function removeStyles() {
  styleEl?.remove();
  styleEl = null;
}

// ── Annotation Rendering ─────────────────────────────────────────────

function clearAllAnnotations() {
  renderingInProgress = true;
  document.querySelectorAll(`.${BADGE_CLASS}`).forEach((el) => el.remove());
  document
    .querySelectorAll(`.${ANNOTATED_CLASS}`)
    .forEach((el) => el.classList.remove(ANNOTATED_CLASS));
  currentAnnotations = [];
  renderingInProgress = false;
}

function renderAnnotations(blocks) {
  renderingInProgress = true;
  const newBlocks = blocks || [];
  const newUids = new Set(newBlocks.map((b) => b.uid));

  // Remove badges for blocks no longer in the new set
  document.querySelectorAll(`.${BADGE_CLASS}`).forEach((el) => {
    if (!newUids.has(el.dataset.agentUid)) {
      const container = el.closest(`.${ANNOTATED_CLASS}`);
      el.remove();
      if (container && !container.querySelector(`.${BADGE_CLASS}`)) {
        container.classList.remove(ANNOTATED_CLASS);
      }
    }
  });

  // Update labels on existing badges if they changed
  const existingByUid = {};
  document.querySelectorAll(`.${BADGE_CLASS}`).forEach((el) => {
    const uid = el.dataset.agentUid;
    if (!existingByUid[uid]) existingByUid[uid] = [];
    existingByUid[uid].push(el);
  });

  for (const { uid, label, intent } of newBlocks) {
    const existing = existingByUid[uid];
    if (existing) {
      // Update label text if changed
      for (const el of existing) {
        if (el.textContent !== label) el.textContent = label;
        const cls = `${BADGE_CLASS} ${BADGE_CLASS}--${intent || "info"}`;
        if (el.className !== cls) el.className = cls;
      }
    }
  }

  currentAnnotations = newBlocks;
  // Add badges for new blocks not yet in the DOM
  applyAnnotationsToDOM();
  renderingInProgress = false;
}

function applyAnnotationsToDOM() {
  renderingInProgress = true;
  for (const { uid, label, intent } of currentAnnotations) {
    const blockEls = document.querySelectorAll(
      `.roam-block-container[data-block-uid="${uid}"]`
    );
    for (const blockEl of blockEls) {
      if (blockEl.querySelector(`.${BADGE_CLASS}`)) continue; // already rendered

      blockEl.classList.add(ANNOTATED_CLASS);

      const badge = document.createElement("div");
      badge.className = `${BADGE_CLASS} ${BADGE_CLASS}--${intent || "info"}`;
      badge.textContent = label;
      badge.dataset.agentUid = uid;

      blockEl.insertBefore(badge, blockEl.firstChild);
    }
  }
  renderingInProgress = false;
}

function clearSelectHighlight() {
  document
    .querySelectorAll(".agent-select-highlight")
    .forEach((el) => el.classList.remove("agent-select-highlight"));
  selectedBlockUids = [];
}

// ── Block Scanning (navigator-style) ─────────────────────────────────

/**
 * Scan DOM for visible block containers. Returns an array of
 * {uid, label, text?, pageTitle?} in document order.
 *
 * scope: "main" | "sidebar" | "all" (default: "all")
 * includeText: whether to pull the block string from Datascript
 */
function scanVisibleBlocks(scope = "all", includeText = true) {
  const results = [];
  const seenUids = new Set(); // deduplicate: same block gets one label

  // Determine root elements to scan
  const roots = [];
  if (scope === "main" || scope === "all") {
    const main = document.querySelector(".roam-body-main .roam-article");
    if (main) roots.push({ el: main, region: "main" });
  }
  if (scope === "sidebar" || scope === "all") {
    // #right-sidebar is the documented sidebar container
    const sidebar = document.getElementById("right-sidebar");
    if (sidebar) roots.push({ el: sidebar, region: "sidebar" });
  }

  for (const { el: root, region } of roots) {
    const containers = root.querySelectorAll(
      ".roam-block-container[data-block-uid]"
    );
    for (const container of containers) {
      const uid = container.getAttribute("data-block-uid");
      if (!uid) continue;

      // Deduplicate: if this uid was already labelled (e.g. same block in
      // main + sidebar), skip — it gets the same badge via applyAnnotationsToDOM
      if (seenUids.has(uid)) continue;
      seenUids.add(uid);

      // Skip blocks not visible in the viewport
      if (container.offsetParent === null) continue;

      const entry = { uid, region };

      if (includeText) {
        const pulled = window.roamAlphaAPI.pull(
          "[:block/string :block/heading :node/title]",
          [":block/uid", uid]
        );
        entry.text = pulled?.[":block/string"] ?? pulled?.[":node/title"] ?? "";
      }

      const pageTitle = container.getAttribute("data-page-title");
      if (pageTitle) entry.page = pageTitle;

      results.push(entry);
    }
  }

  // Assign labels in document order
  results.forEach((entry, i) => {
    entry.label = indexToLabel(i);
  });

  return results;
}

// ── Label→UID mapping (kept in sync with annotations) ────────────────

// Active label mapping, exported to state writer
let activeLabelMap = {}; // {"A": {uid: "uid1", region: "main"}, ...}
let selectedBlockUids = []; // UIDs of blocks highlighted via select-block focus mode

function updateLabelMap(scannedBlocks) {
  activeLabelMap = {};
  for (const { label, uid, region } of scannedBlocks) {
    activeLabelMap[label] = { uid, region: region || "main" };
  }
}

function clearLabelMap() {
  activeLabelMap = {};
}

// ── Nav Mode (auto-rescan) ───────────────────────────────────────────

/**
 * Run a full rescan: scan visible blocks, render badges, update label map,
 * and force a state write so __state__.labels is fresh.
 */
function navRescan() {
  const scanned = scanVisibleBlocks(navModeScope, true);
  const annotationBlocks = scanned.map(({ uid, label }) => ({
    uid,
    label,
    intent: "nav",
  }));
  renderAnnotations(annotationBlocks);
  updateLabelMap(scanned);
  // Force state write so labels are immediately available via Local API
  lastStateJson = null;
  writeViewState();
}

// Track the last view fingerprint so we only rescan on actual changes
let lastNavViewKey = null;

/**
 * Fingerprint the visible block set — cheap DOM scan, no Datascript.
 * Captures: current page, sidebar state, and the set of visible block uids.
 * Any new/removed/reordered block triggers a rescan.
 */
function viewFingerprint(state) {
  const sidebarEl = document.getElementById("right-sidebar");
  const sidebarOpen = sidebarEl
    ? !sidebarEl.classList.contains("closed") && sidebarEl.offsetWidth > 0
    : false;

  // Collect visible block uids from the DOM (fast — no API calls)
  const uids = [];
  const containers = document.querySelectorAll(
    ".roam-block-container[data-block-uid]"
  );
  for (const c of containers) {
    if (c.offsetParent !== null) {
      uids.push(c.getAttribute("data-block-uid"));
    }
  }

  return JSON.stringify({
    main: state?.main?.uid,
    sidebarOpen,
    sidebar: (state?.sidebar || []).map((w) => w?.["block-uid"] || w?.uid),
    blocks: uids,
  });
}

async function startNavMode(scope) {
  navModeScope = scope || "all";
  navModeActive = true;
  navRescan();
  // Capture initial view fingerprint
  const state = await captureViewState();
  lastNavViewKey = viewFingerprint(state);
  console.log(`[agent-bridge] Nav mode ON (scope: ${navModeScope})`);
}

function stopNavMode() {
  navModeActive = false;
  lastNavViewKey = null;
  clearAllAnnotations();
  clearLabelMap();
  lastStateJson = null;
  writeViewState();
  console.log("[agent-bridge] Nav mode OFF");
}

// Re-attach badges when Roam re-renders blocks (virtual list recycling).
// Never triggers a rescan — only re-applies existing annotations.
function startBlockObserver() {
  blockObserver = new MutationObserver(() => {
    if (renderingInProgress) return;
    if (currentAnnotations.length > 0) {
      requestAnimationFrame(applyAnnotationsToDOM);
    }
  });

  // Observe both main and sidebar for block re-renders
  const main = document.querySelector(".roam-body-main") || document.body;
  blockObserver.observe(main, { childList: true, subtree: true });

  const sidebar = document.getElementById("right-sidebar");
  if (sidebar) {
    blockObserver.observe(sidebar, { childList: true, subtree: true });
  }
}

function stopBlockObserver() {
  blockObserver?.disconnect();
  blockObserver = null;
}

// ── View State Reporting ─────────────────────────────────────────────

async function captureViewState() {
  const [mainView, sidebarWindows, focused] = await Promise.all([
    window.roamAlphaAPI.ui.mainWindow.getOpenView(),
    window.roamAlphaAPI.ui.rightSidebar.getWindows(),
    window.roamAlphaAPI.ui.getFocusedBlock(),
  ]);
  const state = {
    ts: Date.now(),
    main: mainView,
    sidebar: sidebarWindows || [],
    focused: focused || null,
  };

  // Include active label map when annotations are present
  const labelKeys = Object.keys(activeLabelMap);
  if (labelKeys.length > 0) {
    state.labels = activeLabelMap; // {"A": {uid, region}, ...}
  }

  if (selectedBlockUids.length > 0) {
    state.selected = selectedBlockUids;
  }

  return state;
}

let lastStateJson = null; // track previous write to avoid churn

async function writeViewState() {
  if (!stateBlockUid) return;
  try {
    const state = await captureViewState();
    const json = JSON.stringify(state);

    // In nav-mode, check if view changed → rescan
    if (navModeActive) {
      const vfp = viewFingerprint(state);
      if (vfp !== lastNavViewKey) {
        lastNavViewKey = vfp;
        navRescan();
        // Re-capture state after rescan so labels are fresh
        const updated = await captureViewState();
        const updatedJson = JSON.stringify(updated);
        const updatedComparable = JSON.stringify({ ...updated, ts: 0 });
        lastStateJson = updatedComparable;
        const children = getChildren(stateBlockUid);
        if (children.length > 0) {
          await window.roamAlphaAPI.data.block.update({
            block: { uid: children[0].uid, string: updatedJson },
          });
        }
        return;
      }
    }

    // Skip write if nothing changed (ignore ts field for comparison)
    const comparable = JSON.stringify({ ...state, ts: 0 });
    if (comparable === lastStateJson) return;
    lastStateJson = comparable;

    // Replace the single child of __state__, or create one
    const children = getChildren(stateBlockUid);
    if (children.length > 0) {
      await window.roamAlphaAPI.data.block.update({
        block: { uid: children[0].uid, string: json },
      });
      // Clean up any extra children
      for (let i = 1; i < children.length; i++) {
        await window.roamAlphaAPI.data.block.delete({
          block: { uid: children[i].uid },
        });
      }
    } else {
      await window.roamAlphaAPI.data.block.create({
        location: { "parent-uid": stateBlockUid, order: 0 },
        block: { uid: generateUID(), string: json },
      });
    }
  } catch (e) {
    console.error("[agent-bridge] writeViewState error:", e);
  }
}

function startStatePolling() {
  // Write initial state immediately
  writeViewState();
  stateInterval = setInterval(writeViewState, POLL_INTERVAL_MS);
}

function stopStatePolling() {
  clearInterval(stateInterval);
  stateInterval = null;
}

// ── CLJS Interop: Block Selection ─────────────────────────────────────

// Cache resolved $APP symbols (they're stable within a single Roam session).
let _cljs = null;

/**
 * Dynamically resolve munged $APP symbols by their CLJS string value.
 * Closure Compiler renames these each Roam release, so we can't hardcode them.
 *
 * We need:
 *   - setSelectedKw: the :relemma.routes.app.events/set-selected keyword
 *   - derefFn:       cljs.core/-deref (the .J method on the IDeref protocol obj)
 *   - PVec:          cljs.core/PersistentVector constructor
 *   - emptyNode:     PersistentVector.EMPTY_NODE
 */
function resolveCljsSymbols() {
  if (_cljs) return _cljs;
  if (typeof $APP === "undefined") return null;

  let setSelectedKw = null;
  let PVec = null;
  let emptyNode = null;
  let derefObj = null;

  const keys = Object.getOwnPropertyNames($APP);
  for (const k of keys) {
    try {
      const v = $APP[k];
      if (!v) continue;

      // Keywords have a toString() like ":namespace/name"
      if (
        !setSelectedKw &&
        typeof v === "object" &&
        typeof v.toString === "function"
      ) {
        const s = v.toString();
        if (s === ":relemma.routes.app.events/set-selected") {
          setSelectedKw = v;
          continue;
        }
      }

      // PersistentVector: constructor function with EMPTY_NODE static property
      // and fromArray static method
      if (
        !PVec &&
        typeof v === "function" &&
        v.EMPTY_NODE &&
        v.prototype?.cljs$core$IVector$
      ) {
        PVec = v;
        emptyNode = v.EMPTY_NODE;
        continue;
      }

      // IDeref protocol object: has a .J method (the -deref impl)
      // It's an object (not a function) with a .J that is a function
      if (
        !derefObj &&
        typeof v === "object" &&
        typeof v.J === "function" &&
        !v.EMPTY_NODE
      ) {
        // Heuristic: deref objects are small protocol implementations
        const objKeys = Object.keys(v);
        if (objKeys.length <= 3 && objKeys.includes("J")) {
          derefObj = v;
        }
      }
    } catch (_) {
      // Skip any properties that throw on access
    }
  }

  if (setSelectedKw && PVec && emptyNode && derefObj) {
    _cljs = { setSelectedKw, PVec, emptyNode, derefObj };
    console.log("[agent-bridge] CLJS symbols resolved for block selection");
    return _cljs;
  }
  return null;
}

/**
 * Select (highlight) a block without entering edit mode.
 * Uses Roam's internal re-frame dispatch extracted from the textarea's
 * onBlur React prop closure.
 *
 * @param {HTMLTextAreaElement} textarea - the focused textarea element
 * @param {string} blockUid - the block uid to select
 * @returns {boolean} true if the CLJS interop path succeeded
 */
function selectBlockViaInternals(textarea, blockUid) {
  const cljs = resolveCljsSymbols();
  if (!cljs) return false;

  const propsKey = Object.keys(textarea).find((k) =>
    k.startsWith("__reactProps$")
  );
  const props = propsKey ? textarea[propsKey] : null;
  if (!props?.onBlur) return false;

  // Intercept cljs.core/-deref to capture the re-frame dispatch fn
  let dispatchFn = null;
  const origDeref = cljs.derefObj.J;

  cljs.derefObj.J = function (atom) {
    const fn = origDeref.call(cljs.derefObj, atom);
    if (!dispatchFn) dispatchFn = fn;
    cljs.derefObj.J = origDeref; // restore immediately
    return fn;
  };

  // Normal onBlur — saves edits and exits edit mode
  props.onBlur();
  cljs.derefObj.J = origDeref; // restore (safety)

  if (!dispatchFn) return false;

  // Dispatch set-selected after blur settles
  setTimeout(() => {
    try {
      // Build CLJS vector: [set-selected-keyword, block-uid]
      const vec = new cljs.PVec(
        null, 2, 5, cljs.emptyNode,
        [cljs.setSelectedKw, blockUid], null
      );
      if (dispatchFn.J) dispatchFn.J(vec);
      else dispatchFn(vec);
    } catch (e) {
      console.warn("[agent-bridge] set-selected dispatch failed:", e);
    }
  }, 100);

  return true;
}

// ── Command Processing ───────────────────────────────────────────────

async function writeResponse(commandBlockUid, id, status, result) {
  const response = JSON.stringify({ id, status, result: result ?? null });
  await window.roamAlphaAPI.data.block.create({
    location: { "parent-uid": commandBlockUid, order: 0 },
    block: { uid: generateUID(), string: response },
  });
}

async function processCommand(commandBlockUid, cmd) {
  const { id, type, args } = cmd;

  if (processedCommandIds.has(id)) return;

  // Skip commands that already have a response child — they were processed
  // in a previous session. Without this check, PullWatch fires on startup
  // and re-executes ALL old commands (including dangerous eval commands).
  const existingChildren = getChildren(commandBlockUid);
  if (existingChildren.length > 0) {
    processedCommandIds.add(id);
    return;
  }

  processedCommandIds.add(id);

  // Cap the processed set to prevent unbounded growth
  if (processedCommandIds.size > 500) {
    const arr = [...processedCommandIds];
    processedCommandIds = new Set(arr.slice(-250));
  }

  try {
    switch (type) {
      case "annotate": {
        const blocks = args?.blocks || [];
        renderAnnotations(blocks);
        updateLabelMap(blocks);
        await writeResponse(commandBlockUid, id, "done", {
          count: currentAnnotations.length,
        });
        break;
      }

      case "clear": {
        clearAllAnnotations();
        clearLabelMap();
        await writeResponse(commandBlockUid, id, "done", {});
        break;
      }

      case "get-view": {
        const state = await captureViewState();
        await writeResponse(commandBlockUid, id, "done", state);
        break;
      }

      case "nav-mode": {
        startNavMode(args?.scope);
        await writeResponse(commandBlockUid, id, "done", {
          active: true,
          scope: navModeScope,
          count: Object.keys(activeLabelMap).length,
          labels: activeLabelMap,
        });
        break;
      }

      case "nav-off": {
        stopNavMode();
        await writeResponse(commandBlockUid, id, "done", { active: false });
        break;
      }

      case "select-block": {
        // Accept single uid or array of uids
        const uids = args?.uids || (args?.uid ? [args.uid] : []);
        const windowId = args?.window_id || "main-window";
        const mode = args?.mode || "focus"; // "focus" = highlight, "edit" = text input

        if (uids.length === 0) {
          await writeResponse(commandBlockUid, id, "error", {
            error: "No uid(s) provided",
          });
          break;
        }

        if (mode === "edit") {
          // Edit mode: focus the first block's textarea (can only edit one).
          await window.roamAlphaAPI.ui.setBlockFocusAndSelection({
            location: { "block-uid": uids[0], "window-id": windowId },
          });
        } else {
          // Focus mode: highlight block(s) WITHOUT entering edit mode.
          // Find blocks in the DOM directly (nav-mode blocks are always
          // visible since they were scanned), scroll to them, and apply
          // a persistent highlight.

          // First: dismiss any block currently in edit mode.
          if (document.activeElement?.tagName === "TEXTAREA") {
            const title = document.querySelector(
              ".rm-title-display, .roam-article .rm-title-display"
            );
            if (title) {
              title.click();
            } else {
              const article = document.querySelector(
                ".roam-body-main .roam-article"
              );
              if (article) article.click();
            }
            await new Promise((r) => setTimeout(r, 50));
          }

          // Clear any previous selection highlight
          clearSelectHighlight();
          selectedBlockUids = [...uids];

          const root =
            windowId === "main-window"
              ? document.querySelector(".roam-body-main")
              : document.getElementById("right-sidebar");

          let scrollTarget = null;
          for (const blockUid of uids) {
            const selector = `.roam-block-container[data-block-uid="${blockUid}"]`;
            const blockEl = root?.querySelector(selector);
            if (blockEl) {
              blockEl.classList.add("agent-select-highlight");
              if (!scrollTarget) scrollTarget = blockEl;
            }
          }

          // Scroll the first highlighted block into view
          if (scrollTarget) {
            scrollTarget.scrollIntoView({
              block: "nearest",
              behavior: "smooth",
            });
          }

          // Clear highlights on next user interaction
          const clearOnInteract = () => {
            clearSelectHighlight();
            lastStateJson = null; // force state write to clear selected
            document.removeEventListener("click", clearOnInteract, true);
            document.removeEventListener("keydown", clearOnInteract, true);
          };
          document.addEventListener("click", clearOnInteract, true);
          document.addEventListener("keydown", clearOnInteract, true);

          // Force immediate state write so selected uids are available
          lastStateJson = null;
          writeViewState();
        }

        await writeResponse(commandBlockUid, id, "done", {
          uids,
          mode,
          window_id: windowId,
        });
        break;
      }

      case "scan-blocks": {
        const scope = args?.scope || "all";
        const includeText = args?.include_text !== false; // default true
        const scanned = scanVisibleBlocks(scope, includeText);

        // Render navigator-style badges on all scanned blocks
        const annotationBlocks = scanned.map(({ uid, label }) => ({
          uid,
          label,
          intent: "nav",
        }));
        renderAnnotations(annotationBlocks);
        updateLabelMap(scanned);

        // Build the response: full mapping with optional text
        const mapping = scanned.map(({ uid, label, text, page, region }) => {
          const entry = { label, uid, region };
          if (includeText) entry.text = text;
          if (page) entry.page = page;
          return entry;
        });

        await writeResponse(commandBlockUid, id, "done", {
          count: mapping.length,
          mapping,
        });
        break;
      }

      case "eval": {
        const code = args?.code;
        if (!code) {
          await writeResponse(commandBlockUid, id, "error", {
            error: "No code provided",
          });
          break;
        }
        try {
          // Use Function constructor to avoid direct eval CSP issues
          // The function has access to roamAlphaAPI via window
          const fn = new Function("roamAlphaAPI", code);
          const evalResult = await fn(window.roamAlphaAPI);
          await writeResponse(commandBlockUid, id, "done", {
            value:
              typeof evalResult === "undefined"
                ? "undefined"
                : JSON.parse(JSON.stringify(evalResult)),
          });
        } catch (evalErr) {
          await writeResponse(commandBlockUid, id, "error", {
            error: evalErr.message,
            stack: evalErr.stack?.split("\n").slice(0, 3).join("\n"),
          });
        }
        break;
      }

      case "notify": {
        const message = args?.message || "Agent notification";
        const intent = args?.intent || "info"; // info, warning, error, success
        // Roam's blueprint toast
        const toastEl = document.querySelector(".bp3-toast-container");
        if (toastEl && window.blueprintjs?.core?.Toaster) {
          // If blueprint is available
          window.blueprintjs.core.Toaster.create({}).show({
            message,
            intent,
            timeout: 4000,
          });
        } else {
          // Fallback: create a simple toast
          showFallbackToast(message, intent);
        }
        await writeResponse(commandBlockUid, id, "done", {});
        break;
      }

      default: {
        await writeResponse(commandBlockUid, id, "error", {
          error: `Unknown command type: ${type}`,
        });
      }
    }
  } catch (e) {
    console.error(`[agent-bridge] Error processing command ${id}:`, e);
    try {
      await writeResponse(commandBlockUid, id, "error", {
        error: e.message,
      });
    } catch (_) {
      // If even writing the error fails, just log
    }
  }
}

function showFallbackToast(message, intent) {
  const colours = {
    info: "#4a69bd",
    warning: "#f6b93b",
    error: "#e55039",
    success: "#27ae60",
  };
  const toast = document.createElement("div");
  Object.assign(toast.style, {
    position: "fixed",
    top: "12px",
    right: "12px",
    zIndex: "99999",
    padding: "10px 18px",
    borderRadius: "6px",
    background: colours[intent] || colours.info,
    color: "#fff",
    fontWeight: "600",
    fontSize: "14px",
    boxShadow: "0 2px 8px rgba(0,0,0,0.2)",
    transition: "opacity 0.3s",
    opacity: "1",
  });
  toast.textContent = message;
  document.body.appendChild(toast);
  setTimeout(() => {
    toast.style.opacity = "0";
    setTimeout(() => toast.remove(), 300);
  }, 4000);
}

// ── PullWatch: React to new commands ─────────────────────────────────

function startCommandWatch() {
  if (!commandsBlockUid) return;

  pullWatchCallback = (_before, after) => {
    // after is the new state of the __commands__ block and its children
    const children = after?.[":block/children"] || [];
    for (const child of children) {
      const str = child?.[":block/string"];
      const uid = child?.[":block/uid"];
      if (!str || !uid) continue;
      // Commands are JSON strings
      try {
        const cmd = JSON.parse(str);
        if (cmd.id && cmd.type) {
          processCommand(uid, cmd);
        }
      } catch (_) {
        // Not JSON — ignore (could be the heading text itself or a response)
      }
    }
  };

  window.roamAlphaAPI.data.addPullWatch(
    "[:block/string :block/uid {:block/children [:block/string :block/uid]}]",
    `[:block/uid "${commandsBlockUid}"]`,
    pullWatchCallback
  );
}

function stopCommandWatch() {
  if (!commandsBlockUid || !pullWatchCallback) return;
  window.roamAlphaAPI.data.removePullWatch(
    "[:block/string :block/uid {:block/children [:block/string :block/uid]}]",
    `[:block/uid "${commandsBlockUid}"]`,
    pullWatchCallback
  );
  pullWatchCallback = null;
}

// ── Extension Lifecycle ──────────────────────────────────────────────

export async function onload({ extensionAPI }) {
  console.log("[agent-bridge] Loading...");

  injectStyles();

  await ensureBridgePage();
  startCommandWatch();
  startBlockObserver();
  startStatePolling();

  // Command palette entries
  extensionAPI.ui.commandPalette.addCommand({
    label: "Agent Bridge: Show Status",
    callback: () => {
      const annotationCount = currentAnnotations.length;
      const watching = pullWatchCallback !== null;
      showFallbackToast(
        `Agent Bridge: ${watching ? "active" : "inactive"}, ${annotationCount} annotations, nav: ${navModeActive ? "ON" : "OFF"}`,
        "info"
      );
    },
  });

  extensionAPI.ui.commandPalette.addCommand({
    label: "Agent Bridge: Toggle Nav Mode",
    callback: () => {
      if (navModeActive) {
        stopNavMode();
        showFallbackToast("Nav mode OFF", "info");
      } else {
        startNavMode("all");
        showFallbackToast(`Nav mode ON — ${Object.keys(activeLabelMap).length} blocks labelled`, "success");
      }
    },
  });

  console.log("[agent-bridge] Loaded. Control page:", BRIDGE_PAGE);
}

export function onunload() {
  console.log("[agent-bridge] Unloading...");

  navModeActive = false;
  if (navRescanTimer) clearTimeout(navRescanTimer);
  stopCommandWatch();
  stopBlockObserver();
  stopStatePolling();
  clearAllAnnotations();
  clearLabelMap();
  removeStyles();
  processedCommandIds.clear();
  _cljs = null;

  bridgePageUid = null;
  commandsBlockUid = null;
  stateBlockUid = null;
}

export default { onload, onunload };

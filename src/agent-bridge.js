/**
 * agent-bridge.js — Roam Depot extension
 *
 * Bridges an external MCP agent (via Roam Local API) to the in-browser
 * roamAlphaAPI surface. Provides:
 *
 *   1. Block annotation overlays (labels on native bullets)
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
 *   delete-blocks— {labels?: ["A","B"], uids?: ["uid1"]}        ← delete blocks by label or uid
 *   notify      — {message: "...", intent?: "info"|"warning"|"error"|"success"}
 *
 * ─────────────────────────────────────────────────────────────────────
 */

import badgeCSS from "./agent-bridge.css";

// ── Constants ────────────────────────────────────────────────────────

const BRIDGE_PAGE = "roam-agent/bridge";
const COMMANDS_HEADING = "__commands__";
const STATE_HEADING = "__state__";
const ANNOTATED_CLASS = "agent-annotated";
// Selector for the draggable bullet span (parent of the inner dot)
const BULLET_SEL = ".rm-bullet";
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
let activeClearOnInteract = null; // tracked for cleanup on unload
let selectedBlockUids = []; // UIDs of blocks highlighted via select-block focus mode
let toasterInstance = null; // cached Blueprint Toaster (avoids React root leaks)

// ── Helpers ──────────────────────────────────────────────────────────

/** Validate a Roam block/page UID (alphanumeric, hyphens, underscores). */
function isValidUid(uid) {
  return typeof uid === "string" && /^[\w-]+$/.test(uid);
}

/** Escape a string for safe interpolation into Datalog query strings. */
function escDq(s) {
  return String(s)
    .replace(/\\/g, "\\\\")
    .replace(/"/g, '\\"')
    .replace(/\n/g, "\\n")
    .replace(/\r/g, "\\r")
    .replace(/\t/g, "\\t");
}

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
    `[:find ?uid :where [?e :node/title "${escDq(title)}"] [?e :block/uid ?uid]]`
  );
  return result?.[0]?.[0] || null;
}

function getChildByString(parentUid, str) {
  const result = window.roamAlphaAPI.q(
    `[:find ?uid ?s :where
      [?p :block/uid "${escDq(parentUid)}"]
      [?p :block/children ?c]
      [?c :block/uid ?uid]
      [?c :block/string ?s]
      [(= ?s "${escDq(str)}")]
    ]`
  );
  return result?.[0]?.[0] || null;
}

function getChildren(parentUid) {
  const result = window.roamAlphaAPI.q(
    `[:find ?uid ?s ?order :where
      [?p :block/uid "${escDq(parentUid)}"]
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
//
// Labels are rendered by setting data-agent-label and data-agent-intent
// attributes on .rm-bullet (the draggable span). CSS uses ::before to
// render the label text and hides the inner dot. Since the label is part
// of the draggable element itself, all pointer events (drag, click, menu)
// work naturally.

function clearAllAnnotations() {
  renderingInProgress = true;
  // Remove label attributes from all labeled bullets
  document.querySelectorAll("[data-agent-label]").forEach((el) => {
    el.removeAttribute("data-agent-label");
    el.removeAttribute("data-agent-intent");
  });
  document
    .querySelectorAll(`.${ANNOTATED_CLASS}`)
    .forEach((el) => {
      el.classList.remove(ANNOTATED_CLASS);
      el.removeAttribute("data-agent-intent");
    });
  currentAnnotations = [];
  renderingInProgress = false;
}

function renderAnnotations(blocks = []) {
  renderingInProgress = true;
  const newBlocks = blocks;
  const newUids = new Set(newBlocks.map((b) => b.uid));

  // Build a lookup of new block annotations by uid
  const newByUid = {};
  for (const b of newBlocks) {
    newByUid[b.uid] = b;
  }

  // Remove labels from blocks no longer in the new set
  document.querySelectorAll(`.${ANNOTATED_CLASS}`).forEach((container) => {
    const uid = container.getAttribute("data-block-uid");
    if (!uid || !newUids.has(uid)) {
      container.classList.remove(ANNOTATED_CLASS);
      container.removeAttribute("data-agent-intent");
      const bullet = container.querySelector(BULLET_SEL);
      if (bullet) {
        bullet.removeAttribute("data-agent-label");
        bullet.removeAttribute("data-agent-intent");
      }
    }
  });

  // Update existing labeled bullets if label/intent changed
  document.querySelectorAll("[data-agent-label]").forEach((bullet) => {
    const container = bullet.closest(".roam-block-container");
    const uid = container?.getAttribute("data-block-uid");
    if (uid && newByUid[uid]) {
      const { label, intent } = newByUid[uid];
      if (bullet.dataset.agentLabel !== label) {
        bullet.dataset.agentLabel = label;
      }
      const intentVal = intent || "info";
      if (bullet.dataset.agentIntent !== intentVal) {
        bullet.dataset.agentIntent = intentVal;
      }
      if (container.dataset.agentIntent !== intentVal) {
        container.dataset.agentIntent = intentVal;
      }
    }
  });

  currentAnnotations = newBlocks;
  // Apply labels to blocks not yet labeled in the DOM
  applyAnnotationsToDOM();
  renderingInProgress = false;
}

function applyAnnotationsToDOM() {
  renderingInProgress = true;
  for (const { uid, label, intent } of currentAnnotations) {
    if (!isValidUid(uid)) continue; // skip malformed UIDs
    const blockEls = document.querySelectorAll(
      `.roam-block-container[data-block-uid="${uid}"]`
    );
    for (const blockEl of blockEls) {
      // Find the bullet inner element
      const bullet = blockEl.querySelector(`:scope > .rm-block-main ${BULLET_SEL}`);
      if (!bullet || bullet.hasAttribute("data-agent-label")) continue; // already labeled

      // Set label and intent as data attributes — CSS does the rest
      bullet.dataset.agentLabel = label;
      bullet.dataset.agentIntent = intent || "info";

      // Mark the container for left-border styling
      blockEl.classList.add(ANNOTATED_CLASS);
      blockEl.dataset.agentIntent = intent || "info";
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
      // main + sidebar), skip — it gets labeled via applyAnnotationsToDOM
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
 * Run a full rescan: scan visible blocks, label bullets, update label map.
 * Invalidates lastStateJson so the next poll cycle writes fresh state.
 * Does NOT call writeViewState directly to avoid recursion.
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
  // Invalidate cached state so next poll writes fresh labels
  lastStateJson = null;
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
  // Invalidate so next poll writes state without labels
  lastStateJson = null;
  console.log("[agent-bridge] Nav mode OFF");
}

// Re-apply bullet labels when Roam re-renders blocks (virtual list recycling).
// Never triggers a rescan — only re-applies existing annotations.
let applyPending = false;
function startBlockObserver() {
  blockObserver = new MutationObserver(() => {
    if (renderingInProgress || applyPending) return;
    if (currentAnnotations.length > 0) {
      applyPending = true;
      requestAnimationFrame(() => {
        applyAnnotationsToDOM();
        applyPending = false;
      });
    }
  });

  // Observe both main and sidebar for block re-renders
  const main = document.querySelector(".roam-body-main");
  if (main) {
    blockObserver.observe(main, { childList: true, subtree: true });
  } else {
    console.warn("[agent-bridge] .roam-body-main not found, observer not attached");
  }

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
let stateWriteInProgress = false; // reentrancy guard

async function writeViewState() {
  if (!stateBlockUid || stateWriteInProgress) return;
  stateWriteInProgress = true;
  try {
    // Capture once; in nav-mode, check fingerprint → rescan if changed,
    // then re-capture only when labels were updated.
    let state = await captureViewState();
    if (navModeActive) {
      const vfp = viewFingerprint(state);
      if (vfp !== lastNavViewKey) {
        lastNavViewKey = vfp;
        navRescan(); // updates annotations + labels, invalidates lastStateJson
        state = await captureViewState(); // re-capture with fresh labels
      }
    }

    const json = JSON.stringify(state);

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
  } finally {
    stateWriteInProgress = false;
  }
}

function startStatePolling() {
  // Guard against leaked intervals if called twice
  if (stateInterval) clearInterval(stateInterval);
  // Write initial state immediately
  writeViewState();
  stateInterval = setInterval(writeViewState, POLL_INTERVAL_MS);
}

function stopStatePolling() {
  clearInterval(stateInterval);
  stateInterval = null;
}

// ── Command Processing ───────────────────────────────────────────────

// Serial command queue — prevents interleaving of concurrent async commands
let commandQueue = Promise.resolve();

function enqueueCommand(commandBlockUid, cmd) {
  commandQueue = commandQueue
    .then(() => processCommand(commandBlockUid, cmd))
    .catch((e) =>
      console.error(`[agent-bridge] Unhandled in command ${cmd.id}:`, e)
    );
}

async function writeResponse(commandBlockUid, id, status, result) {
  const response = JSON.stringify({ id, status, result: result ?? null });
  await window.roamAlphaAPI.data.block.create({
    location: { "parent-uid": commandBlockUid, order: 0 },
    block: { uid: generateUID(), string: response },
  });
}

async function processCommand(commandBlockUid, cmd) {
  const { id, type, args, version } = cmd;

  if (processedCommandIds.has(id)) return;
  processedCommandIds.add(id);

  // Cap the processed set to prevent unbounded growth.
  // Tight eviction (500→400) to reduce replay risk for uncleaned commands.
  if (processedCommandIds.size > 500) {
    const arr = [...processedCommandIds];
    processedCommandIds = new Set(arr.slice(-400));
  }

  // --- Phase A: schema lock (envelope version check) ---------------------
  // See docs/COMMAND-SCHEMA.md §1.
  // - Missing version: warn and continue (transitional grace; tightened in
  //   Phase G).
  // - Mismatched version: hard reject.
  if (version === undefined) {
    console.warn(
      `[agent-bridge] command ${id} (type=${type}) is missing "version"; ` +
        `treating as legacy. New clients MUST send {"version":1}. ` +
        `See docs/COMMAND-SCHEMA.md.`
    );
  } else if (version !== 1) {
    await writeResponse(commandBlockUid, id, "error", {
      error: "unknown-version",
      received: version,
      supported: [1],
    });
    return;
  }
  // -----------------------------------------------------------------------

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

      case "clear-selection": {
        clearSelectHighlight();
        lastStateJson = null;
        await writeResponse(commandBlockUid, id, "done", {});
        break;
      }

      case "get-view": {
        const state = await captureViewState();
        await writeResponse(commandBlockUid, id, "done", state);
        break;
      }

      case "nav-mode": {
        await startNavMode(args?.scope);
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
        const uids = (args?.uids || (args?.uid ? [args.uid] : [])).filter(isValidUid);
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

          // Remove any previous interaction listener before adding new one
          if (activeClearOnInteract) {
            document.removeEventListener("click", activeClearOnInteract, true);
            document.removeEventListener("keydown", activeClearOnInteract, true);
          }

          // Clear highlights on next user interaction
          const clearOnInteract = () => {
            clearSelectHighlight();
            lastStateJson = null; // force state write to clear selected
            document.removeEventListener("click", clearOnInteract, true);
            document.removeEventListener("keydown", clearOnInteract, true);
            activeClearOnInteract = null;
          };
          activeClearOnInteract = clearOnInteract;
          document.addEventListener("click", clearOnInteract, true);
          document.addEventListener("keydown", clearOnInteract, true);

          // Force immediate state write so selected uids are available
          lastStateJson = null;
          await writeViewState();
        }

        await writeResponse(commandBlockUid, id, "done", {
          uids,
          mode,
          window_id: windowId,
        });
        break;
      }

      case "delete-blocks": {
        // Accept labels (resolved via activeLabelMap) or direct uids
        const labels = args?.labels || [];
        const directUids = [...(args?.uids || [])].filter(isValidUid);
        const deleted = [];
        const notFound = [];

        // Resolve labels to UIDs
        for (const label of labels) {
          const entry = activeLabelMap[label] || activeLabelMap[label.toUpperCase()];
          if (entry?.uid) {
            directUids.push(entry.uid);
          } else {
            notFound.push(label);
          }
        }

        // Delete each block
        for (const uid of directUids) {
          try {
            await window.roamAlphaAPI.data.block.delete({
              block: { uid },
            });
            deleted.push(uid);
          } catch (delErr) {
            notFound.push(uid);
          }
        }

        await writeResponse(commandBlockUid, id, "done", {
          deleted,
          not_found: notFound,
          count: deleted.length,
        });
        break;
      }

      case "scan-blocks": {
        const scope = args?.scope || "all";
        const includeText = args?.include_text !== false; // default true
        const scanned = scanVisibleBlocks(scope, includeText);

        // Label all scanned blocks with navigator-style labels
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
          // AsyncFunction supports top-level await in code strings
          const AsyncFunction = Object.getPrototypeOf(
            async function () {}
          ).constructor;
          const fn = new AsyncFunction("roamAlphaAPI", code);
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
        // Roam's blueprint toast (module-scoped to avoid global pollution)
        if (window.blueprintjs?.core?.Toaster) {
          if (!toasterInstance) {
            toasterInstance = window.blueprintjs.core.Toaster.create({});
          }
          toasterInstance.show({
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

  // Pre-seed processedCommandIds with all existing commands so the first
  // PullWatch fire doesn't re-process old commands. This replaces the
  // expensive per-command getChildren check.
  const existingCmds = getChildren(commandsBlockUid);
  for (const { string: str } of existingCmds) {
    try {
      const cmd = JSON.parse(str);
      if (cmd.id) processedCommandIds.add(cmd.id);
    } catch (_) {}
  }
  if (existingCmds.length > 0) {
    console.log(
      `[agent-bridge] Pre-seeded ${processedCommandIds.size} existing command IDs`
    );
  }

  pullWatchCallback = (_before, after) => {
    // after is the new state of the __commands__ block and its children
    const children = after?.[":block/children"] || [];
    for (const child of children) {
      const str = child?.[":block/string"];
      const uid = child?.[":block/uid"];
      if (!str || !uid) continue;
      // Skip commands that already have a response child (durable "processed" marker).
      // This prevents replay even after processedCommandIds eviction.
      const grandchildren = child?.[":block/children"] || [];
      if (grandchildren.length > 0) continue;
      // Commands are JSON strings
      try {
        const cmd = JSON.parse(str);
        if (cmd.id && cmd.type) {
          enqueueCommand(uid, cmd);
        }
      } catch (_) {
        // Not JSON — ignore (could be the heading text itself or a response)
      }
    }
  };

  window.roamAlphaAPI.data.addPullWatch(
    "[:block/string :block/uid {:block/children [:block/string :block/uid {:block/children [:block/string :block/uid]}]}]",
    `[:block/uid "${commandsBlockUid}"]`,
    pullWatchCallback
  );
}

function stopCommandWatch() {
  if (!commandsBlockUid || !pullWatchCallback) return;
  window.roamAlphaAPI.data.removePullWatch(
    "[:block/string :block/uid {:block/children [:block/string :block/uid {:block/children [:block/string :block/uid]}]}]",
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
    callback: async () => {
      if (navModeActive) {
        stopNavMode();
        showFallbackToast("Nav mode OFF", "info");
      } else {
        await startNavMode("all");
        showFallbackToast(`Nav mode ON — ${Object.keys(activeLabelMap).length} blocks labelled`, "success");
      }
    },
  });

  console.log("[agent-bridge] Loaded. Control page:", BRIDGE_PAGE);
}

export function onunload() {
  console.log("[agent-bridge] Unloading...");

  navModeActive = false;
  stopCommandWatch();
  stopBlockObserver();
  stopStatePolling();
  clearAllAnnotations();
  clearLabelMap();
  removeStyles();
  processedCommandIds.clear();

  // Clean up tracked interaction listeners
  if (activeClearOnInteract) {
    document.removeEventListener("click", activeClearOnInteract, true);
    document.removeEventListener("keydown", activeClearOnInteract, true);
    activeClearOnInteract = null;
  }

  toasterInstance = null;
  bridgePageUid = null;
  commandsBlockUid = null;
  stateBlockUid = null;
}

export default { onload, onunload };

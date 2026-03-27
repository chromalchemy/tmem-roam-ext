/**
 * Scittle nREPL injection for Roam Research.
 *
 * Loads Scittle inside a hidden about:blank IFRAME using document.write()
 * to isolate its Google Closure globals from Roam's compiled CLJS ($APP).
 *
 * document.write() on about:blank executes inline scripts as part of
 * HTML parsing — no CSP inheritance, no window replacement issues.
 * The bridge, WebSocket monkey-patch, and all Scittle scripts are
 * written as a single HTML document.
 *
 * Usage:
 *   import { injectScittleNrepl, removeScittleNrepl } from "./scittle-nrepl";
 *   await injectScittleNrepl();   // in onload
 *   removeScittleNrepl();         // in onunload
 */

const SCITTLE_VERSION = "0.7.28";
const CDN_BASE = `https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist`;

const IFRAME_ID = "scittle-nrepl-iframe";

/**
 * Inject Scittle + nREPL inside an isolated iframe.
 *
 * @param {number} wsPort  WebSocket port the bb relay listens on (default 1340)
 */
export async function injectScittleNrepl(wsPort = 1340) {
  // Guard: don't double-inject
  if (document.getElementById(IFRAME_ID)) {
    console.log("[scittle-nrepl] already injected, skipping");
    return;
  }

  console.log("[scittle-nrepl] injecting Scittle nREPL (iframe-isolated) into Roam...");

  // 1. Create hidden iframe
  const iframe = document.createElement("iframe");
  iframe.id = IFRAME_ID;
  iframe.style.display = "none";
  iframe.style.width = "0";
  iframe.style.height = "0";
  iframe.style.border = "none";
  document.body.appendChild(iframe);

  const iframeDoc = iframe.contentDocument;

  // 2. Write the entire HTML document via document.write().
  //    Inline scripts execute as part of parsing — bypasses CSP,
  //    and the window object is stable throughout.
  iframeDoc.open();
  iframeDoc.write(`<!DOCTYPE html>
<html>
<head>
<title>Scittle nREPL</title>

<script>
// ——— Bridge: parent Roam API into this iframe ———
try {
  window.roamAlphaAPI = parent.window.roamAlphaAPI;
  console.log("[scittle-nrepl-iframe] roamAlphaAPI bridged");
} catch(e) {
  console.error("[scittle-nrepl-iframe] bridge failed:", e);
}

// ——— nREPL config ———
var SCITTLE_NREPL_WEBSOCKET_PORT = ${wsPort};

// ——— WebSocket monkey-patch ———
// about:blank has empty window.location.hostname, which makes
// scittle.nrepl.js produce "ws://:PORT/_nrepl" (invalid).
// Rewrite empty hosts to localhost.
(function() {
  var _WS = window.WebSocket;
  window.WebSocket = function(url, protocols) {
    if (url && url.indexOf("ws://:") === 0) {
      url = url.replace("ws://:", "ws://localhost:");
    }
    if (url && url.indexOf("wss://:") === 0) {
      url = url.replace("wss://:", "wss://localhost:");
    }
    console.log("[scittle-nrepl-iframe] WebSocket:", url);
    return (protocols !== undefined)
      ? new _WS(url, protocols)
      : new _WS(url);
  };
  window.WebSocket.prototype = _WS.prototype;
  window.WebSocket.CONNECTING = _WS.CONNECTING;
  window.WebSocket.OPEN       = _WS.OPEN;
  window.WebSocket.CLOSING    = _WS.CLOSING;
  window.WebSocket.CLOSED     = _WS.CLOSED;
  console.log("[scittle-nrepl-iframe] WebSocket patched");
})();
<\/script>

<!-- Scittle core -->
<script src="${CDN_BASE}/scittle.js"><\/script>
<!-- Plugins -->
<script src="${CDN_BASE}/scittle.promesa.js"><\/script>
<script src="${CDN_BASE}/scittle.pprint.js"><\/script>
<!-- nREPL client (must be last — connects WebSocket on load) -->
<script src="${CDN_BASE}/scittle.nrepl.js"><\/script>

<!-- Auto-load roam.api namespace -->
<script type="application/x-scittle">
${ROAM_API_CLJS}
<\/script>

</head>
<body></body>
</html>`);
  iframeDoc.close();

  // 3. Wait for iframe to finish loading all scripts
  await new Promise((resolve) => {
    // The iframe is already being parsed by document.write/close.
    // External scripts load async from the iframe's perspective.
    // Poll for scittle readiness.
    let attempts = 0;
    const check = setInterval(() => {
      attempts++;
      const iframeWin = iframe.contentWindow;
      if (iframeWin && iframeWin.scittle && iframeWin.scittle.core) {
        clearInterval(check);
        // Trigger evaluation of the x-scittle tag
        try { iframeWin.scittle.core.eval_script_tags(); } catch(e) {}
        resolve();
      } else if (attempts > 100) {  // 10 seconds
        clearInterval(check);
        console.warn("[scittle-nrepl] timed out waiting for Scittle init");
        resolve();
      }
    }, 100);
  });

  console.log("[scittle-nrepl] ✓ Scittle nREPL ready (isolated iframe)");
  console.log("[scittle-nrepl]   Run: bb roam-nrepl");
  console.log("[scittle-nrepl]   Connect editor to nREPL port 1339 (REPL type: nbb)");
  console.log("[scittle-nrepl]   js/window.roamAlphaAPI is bridged from Roam");
}

/**
 * Remove the Scittle iframe.
 */
export function removeScittleNrepl() {
  const iframe = document.getElementById(IFRAME_ID);
  if (iframe) {
    iframe.remove();
  }
  console.log("[scittle-nrepl] cleaned up");
}

/**
 * Idiomatic CLJS wrapper for roamAlphaAPI, auto-loaded into the
 * Scittle nREPL session.
 */
const ROAM_API_CLJS = `
(ns roam.api
  "Idiomatic ClojureScript wrappers for js/window.roamAlphaAPI.
   Available in the Scittle nREPL session automatically.")

;; ——————————————————————————————————————————————
;; Reads
;; ——————————————————————————————————————————————

(defn q
  "Run a Datalog query against the Roam graph.
   Returns JS arrays — use (js->clj ... :keywordize-keys true) to convert.
   Example: (q \\"[:find ?s :where [?e :node/title ?s]]\\")"
  [query & args]
  (apply js/window.roamAlphaAPI.data.q query args))

(defn pull
  "Pull an entity by pattern + eid.
   Example: (pull \\"[*]\\" [:block/uid \\"abc123\\"])"
  [pattern eid]
  (js/window.roamAlphaAPI.data.pull pattern (clj->js eid)))

(defn pull-async
  "Async pull — returns a Promise.
   Example: (.then (pull-async \\"[*]\\" [:block/uid \\"abc123\\"]) prn)"
  [pattern eid]
  (js/window.roamAlphaAPI.data.async.pull pattern (clj->js eid)))

(defn q-async
  "Async query — returns a Promise."
  [query & args]
  (apply js/window.roamAlphaAPI.data.async.q query args))

(defn search
  "Search pages and blocks by text.
   Example: (search \\"my query\\")"
  [search-str & {:keys [limit] :or {limit 20}}]
  (js/window.roamAlphaAPI.data.search
   (clj->js {:search-str search-str :limit limit})))

;; ——————————————————————————————————————————————
;; Writes (all return Promises)
;; ——————————————————————————————————————————————

(defn create-block
  "Create a block under parent-uid at order.
   Example: (create-block \\"parent-uid\\" \\"Hello world\\" {:order 0})"
  [parent-uid string & {:keys [order uid] :or {order \\"last\\"}}]
  (js/window.roamAlphaAPI.data.block.create
   (clj->js {:location {:parent-uid parent-uid :order order}
              :block (cond-> {:string string}
                       uid (assoc :uid uid))})))

(defn update-block
  "Update a block's string and/or properties.
   Example: (update-block \\"abc123\\" \\"new text\\")"
  [uid string & {:keys [open heading]}]
  (js/window.roamAlphaAPI.data.block.update
   (clj->js {:block (cond-> {:uid uid :string string}
                      (some? open) (assoc :open open)
                      heading (assoc :heading heading))})))

(defn delete-block
  "Delete a block and all its children.
   Example: (delete-block \\"abc123\\")"
  [uid]
  (js/window.roamAlphaAPI.data.block.delete
   (clj->js {:block {:uid uid}})))

(defn move-block
  "Move a block to a new parent at order.
   Example: (move-block \\"block-uid\\" \\"new-parent-uid\\" 0)"
  [uid parent-uid order]
  (js/window.roamAlphaAPI.data.block.move
   (clj->js {:block {:uid uid}
              :location {:parent-uid parent-uid :order order}})))

(defn create-page
  "Create a page with a title.
   Example: (create-page \\"My New Page\\")"
  [title & {:keys [uid]}]
  (js/window.roamAlphaAPI.data.page.create
   (clj->js {:page (cond-> {:title title}
                     uid (assoc :uid uid))})))

(defn delete-page
  "Delete a page by uid."
  [uid]
  (js/window.roamAlphaAPI.data.page.delete
   (clj->js {:page {:uid uid}})))

;; ——————————————————————————————————————————————
;; UI
;; ——————————————————————————————————————————————

(defn open-page
  "Open a page in the main window by title or uid."
  [& {:keys [title uid]}]
  (js/window.roamAlphaAPI.ui.mainWindow.openPage
   (clj->js {:page (cond-> {}
                     title (assoc :title title)
                     uid (assoc :uid uid))})))

(defn open-block
  "Open (zoom into) a block in the main window."
  [uid]
  (js/window.roamAlphaAPI.ui.mainWindow.openBlock
   (clj->js {:block {:uid uid}})))

(defn focused-block
  "Return the currently focused block (or nil)."
  []
  (js->clj (js/window.roamAlphaAPI.ui.getFocusedBlock) :keywordize-keys true))

(defn open-sidebar
  "Open a block/page in the right sidebar."
  [uid & {:keys [type] :or {type "outline"}}]
  (do (js/window.roamAlphaAPI.ui.rightSidebar.open)
      (js/window.roamAlphaAPI.ui.rightSidebar.addWindow
       (clj->js {:window {:type type :block-uid uid}}))))

;; ——————————————————————————————————————————————
;; Utilities
;; ——————————————————————————————————————————————

(defn generate-uid
  "Generate a new Roam block UID."
  []
  (js/window.roamAlphaAPI.util.generateUID))

(defn date->page-title
  "Convert a JS Date to a Roam daily page title string."
  [date]
  (js/window.roamAlphaAPI.util.dateToPageTitle date))

(defn today-title
  "Get today's daily note page title."
  []
  (date->page-title (js/Date.)))

(defn mobile?
  "Is Roam running on mobile?"
  []
  js/window.roamAlphaAPI.platform.isMobile)

;; ——————————————————————————————————————————————
;; Convenience
;; ——————————————————————————————————————————————

(defn block-string
  "Get the :block/string for a uid. Returns a string or nil."
  [uid]
  (some-> (pull "[:block/string]" [:block/uid uid])
          (aget "block/string")))

(defn page-uid
  "Get the uid of a page by title."
  [title]
  (some-> (q (str "[:find ?uid :where [?e :node/title \\"" title "\\"] [?e :block/uid ?uid]]"))
          ffirst))

(defn children
  "Get child blocks of a parent uid, sorted by order."
  [uid]
  (-> (pull "[:block/uid :block/string :block/order {:block/children ...}]"
            [:block/uid uid])
      (js->clj :keywordize-keys true)))

(println "[roam.api] ✓ loaded — (require '[roam.api :as r]) to use")
`;

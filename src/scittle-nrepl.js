/**
 * Scittle nREPL injection for Roam Research.
 *
 * Loads Scittle inside a hidden about:blank IFRAME to isolate its
 * Google Closure globals from Roam's compiled CLJS ($APP).
 *
 * All setup (roamAlphaAPI bridge, WebSocket monkey-patch, nREPL config)
 * is done programmatically from the parent window into iframe.contentWindow
 * BEFORE any scripts are loaded.  This avoids CSP issues with inline scripts.
 *
 * Usage:
 *   import { injectScittleNrepl, removeScittleNrepl } from "./scittle-nrepl";
 *   await injectScittleNrepl();   // in onload
 *   removeScittleNrepl();         // in onunload
 */

const SCITTLE_VERSION = "0.7.28";
const CDN_BASE = `https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist`;

const IFRAME_ID = "scittle-nrepl-iframe";

// Script load order matters: core → plugins → nrepl last
const SCRIPT_SOURCES = [
  `${CDN_BASE}/scittle.js`,
  `${CDN_BASE}/scittle.promesa.js`,
  `${CDN_BASE}/scittle.pprint.js`,
  `${CDN_BASE}/scittle.nrepl.js`,
];

/**
 * Load a <script src="..."> into an iframe document. Returns a Promise.
 */
function loadScriptInIframe(iframeDoc, src) {
  return new Promise((resolve, reject) => {
    const el = iframeDoc.createElement("script");
    el.type = "application/javascript";
    el.src = src;
    el.onload = () => {
      console.log(`[scittle-nrepl] loaded: ${src}`);
      resolve(el);
    };
    el.onerror = (err) => {
      console.error(`[scittle-nrepl] FAILED to load: ${src}`, err);
      reject(err);
    };
    iframeDoc.head.appendChild(el);
  });
}

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

  // 1. Create a hidden about:blank iframe (same origin as parent)
  const iframe = document.createElement("iframe");
  iframe.id = IFRAME_ID;
  iframe.style.display = "none";
  iframe.style.width = "0";
  iframe.style.height = "0";
  iframe.style.border = "none";
  document.body.appendChild(iframe);

  const iframeWin = iframe.contentWindow;
  const iframeDoc = iframe.contentDocument;

  // 2. Bridge: expose parent's roamAlphaAPI on the iframe's window
  //    Scittle CLJS code uses js/window.roamAlphaAPI
  try {
    iframeWin.roamAlphaAPI = window.roamAlphaAPI;
    console.log("[scittle-nrepl] roamAlphaAPI bridged to iframe");
  } catch (e) {
    console.error("[scittle-nrepl] Cannot bridge roamAlphaAPI:", e);
  }

  // 3. Set nREPL WebSocket config globals
  iframeWin.SCITTLE_NREPL_WEBSOCKET_PORT = wsPort;
  iframeWin.SCITTLE_NREPL_WEBSOCKET_HOST = "localhost";

  // 4. Monkey-patch WebSocket in the iframe context
  //    about:blank iframes have empty window.location.hostname,
  //    so scittle.nrepl.js would produce "ws://:1340/_nrepl" (invalid).
  //    We intercept and rewrite to ws://localhost:PORT/_nrepl.
  const _OrigWS = iframeWin.WebSocket;
  iframeWin.WebSocket = function (url, protocols) {
    if (url && url.indexOf("ws://:") === 0) {
      url = url.replace("ws://:", "ws://localhost:");
    }
    if (url && url.indexOf("wss://:") === 0) {
      url = url.replace("wss://:", "wss://localhost:");
    }
    console.log("[scittle-nrepl] WebSocket connecting to:", url);
    return protocols !== undefined
      ? new _OrigWS(url, protocols)
      : new _OrigWS(url);
  };
  iframeWin.WebSocket.prototype = _OrigWS.prototype;
  iframeWin.WebSocket.CONNECTING = _OrigWS.CONNECTING;
  iframeWin.WebSocket.OPEN = _OrigWS.OPEN;
  iframeWin.WebSocket.CLOSING = _OrigWS.CLOSING;
  iframeWin.WebSocket.CLOSED = _OrigWS.CLOSED;

  // 5. Load Scittle scripts sequentially into the iframe
  for (const src of SCRIPT_SOURCES) {
    await loadScriptInIframe(iframeDoc, src);
  }

  // 6. Inject the roam.api CLJS helper namespace
  const cljsTag = iframeDoc.createElement("script");
  cljsTag.type = "application/x-scittle";
  cljsTag.textContent = ROAM_API_CLJS;
  iframeDoc.head.appendChild(cljsTag);

  // 7. Trigger Scittle to evaluate the CLJS tag
  if (iframeWin.scittle && iframeWin.scittle.core && iframeWin.scittle.core.eval_script_tags) {
    iframeWin.scittle.core.eval_script_tags();
  }

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
 *
 * Inside the iframe, js/window.roamAlphaAPI points to the parent
 * Roam window's API, so all calls work transparently.
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

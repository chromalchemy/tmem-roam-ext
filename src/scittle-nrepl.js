/**
 * Scittle nREPL injection for Roam Research (Electron).
 *
 * Injects Scittle + plugin scripts into the page so that a local
 * bb relay (nREPL ↔ WebSocket) can connect an editor REPL to the
 * running Roam context, with full access to window.roamAlphaAPI.
 *
 * Usage:
 *   import { injectScittleNrepl, removeScittleNrepl } from "./scittle-nrepl";
 *   await injectScittleNrepl();   // in onload
 *   removeScittleNrepl();         // in onunload
 */

const SCITTLE_VERSION = "0.7.28";
const CDN_BASE = `https://cdn.jsdelivr.net/npm/scittle@${SCITTLE_VERSION}/dist`;

// Order matters: core must load first, then plugins, then nrepl last.
const SCRIPT_SOURCES = [
  `${CDN_BASE}/scittle.js`,
  `${CDN_BASE}/scittle.reagent.js`,
  `${CDN_BASE}/scittle.promesa.js`,
  `${CDN_BASE}/scittle.pprint.js`,
  `${CDN_BASE}/scittle.nrepl.js`,
];

const SCITTLE_MARKER = "data-scittle-tmem";

/**
 * Load a single <script> tag and return a Promise that resolves on load.
 */
function loadScript(src) {
  return new Promise((resolve, reject) => {
    const el = document.createElement("script");
    el.type = "application/javascript";
    el.src = src;
    el.setAttribute(SCITTLE_MARKER, "true");
    el.onload = () => {
      console.log(`[scittle-nrepl] loaded: ${src}`);
      resolve(el);
    };
    el.onerror = (err) => {
      console.error(`[scittle-nrepl] FAILED to load: ${src}`, err);
      reject(err);
    };
    document.head.appendChild(el);
  });
}

/**
 * Inject Scittle + nREPL into the current page.
 *
 * @param {number} wsPort  WebSocket port the bb relay listens on (default 1340)
 */
export async function injectScittleNrepl(wsPort = 1340) {
  // Guard: don't double-inject
  if (document.querySelector(`script[${SCITTLE_MARKER}]`)) {
    console.log("[scittle-nrepl] already injected, skipping");
    return;
  }

  console.log("[scittle-nrepl] injecting Scittle nREPL into Roam...");

  // 1. Set the websocket port BEFORE loading scittle.nrepl.js
  window.SCITTLE_NREPL_WEBSOCKET_PORT = wsPort;

  // 2. Load scripts sequentially (order matters)
  for (const src of SCRIPT_SOURCES) {
    await loadScript(src);
  }

  // 3. Register a scittle tag with the roam-api helper so it's
  //    available in the nREPL session immediately
  const helperTag = document.createElement("script");
  helperTag.type = "application/x-scittle";
  helperTag.setAttribute(SCITTLE_MARKER, "true");
  helperTag.textContent = ROAM_API_CLJS;
  document.head.appendChild(helperTag);

  // 4. Trigger scittle to evaluate the new tag
  if (window.scittle && window.scittle.core && window.scittle.core.eval_script_tags) {
    window.scittle.core.eval_script_tags();
  }

  console.log("[scittle-nrepl] ✓ Scittle nREPL ready");
  console.log("[scittle-nrepl]   Run: bb roam-nrepl");
  console.log("[scittle-nrepl]   Connect editor to nREPL port 1339 (REPL type: nbb)");
}

/**
 * Remove all injected Scittle script tags.
 */
export function removeScittleNrepl() {
  document.querySelectorAll(`script[${SCITTLE_MARKER}]`).forEach((el) => el.remove());
  delete window.SCITTLE_NREPL_WEBSOCKET_PORT;
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

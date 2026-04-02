#!/usr/bin/env bb

;; bridge.bb — Agent bridge client.
;;
;; The extension handles all scanning, labelling, and refreshing.
;; This client just reads __state__.labels and acts on blocks by label.
;;
;; Usage:
;;   bb bridge --on               # turn on nav-mode labels
;;   bb bridge --off              # turn off nav-mode labels
;;   bb bridge --label A          # act on block labelled A
;;   bb bridge --labels           # print current label→uid mapping

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[babashka.cli :as cli]
         '[babashka.fs :as fs])

;; ── Config ───────────────────────────────────────────────────────────

(def default-graph "tmem")
(def api-base "http://localhost:3333/api")

(def load-token
  "Read API token from ~/.roam-tools.json. Memoized per graph."
  (memoize
    (fn [graph]
      (let [path (str (fs/home) "/.roam-tools.json")]
        (when (fs/exists? path)
          (-> (slurp path) (json/parse-string true) :graphs
              (->> (filter #(= (:name %) graph)) first :token)))))))

(defn esc-dq
  "Escape a string for safe interpolation into Datalog query strings."
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")
      (str/replace "\n" "\\n")
      (str/replace "\r" "\\r")
      (str/replace "\t" "\\t")))

;; ── Roam Local API ───────────────────────────────────────────────────

(defn roam-api [graph action & args]
  (let [token   (load-token graph)
        headers (cond-> {"Content-Type" "application/json"}
                  token (assoc "Authorization" (str "Bearer " token)))
        resp    (http/post (str api-base "/" graph)
                           {:headers headers :throw false
                            :body (json/generate-string
                                    {:action action :args (vec args)})})
        data    (json/parse-string (:body resp) true)]
    (when-not (:success data)
      (throw (ex-info (str "API error: " (:error data)) {:action action})))
    (:result data)))

(defn roam-q [graph query] (roam-api graph "data.q" query))

(defn roam-update-block [graph uid text]
  (roam-api graph "data.block.update" {"block" {"uid" uid "string" text}}))

(defn roam-set-block-open [graph uid open?]
  (roam-api graph "data.block.update" {"block" {"uid" uid "open" open?}}))

(defn roam-create-block [graph parent-uid text]
  (roam-api graph "data.block.create"
            {"location" {"parent-uid" parent-uid "order" "last"}
             "block"    {"string" text}}))

(defn roam-move-block [graph uid parent-uid order]
  (roam-api graph "data.block.move"
            {"location" {"parent-uid" parent-uid "order" order}
             "block"    {"uid" uid}}))

(defn get-page-uid [graph title]
  (ffirst (roam-q graph
            (str "[:find ?uid :where [?p :node/title \"" (esc-dq title) "\"] [?p :block/uid ?uid]]"))))

(defn get-parent-uid [graph uid]
  (ffirst (roam-q graph
            (str "[:find ?pu :where
                   [?p :block/children ?b]
                   [?b :block/uid \"" (esc-dq uid) "\"]
                   [?p :block/uid ?pu]]"))))

(defn get-block-order [graph uid]
  (ffirst (roam-q graph
            (str "[:find ?order :where
                   [?b :block/uid \"" (esc-dq uid) "\"]
                   [?b :block/order ?order]]"))))

;; ── Bridge helpers ───────────────────────────────────────────────────

(defn find-bridge-uids [graph]
  (let [rows (roam-q graph
               "[:find ?uid ?s :where
                 [?p :node/title \"roam-agent/bridge\"]
                 [?p :block/children ?c]
                 [?c :block/uid ?uid]
                 [?c :block/string ?s]]")]
    (reduce (fn [m [uid s]]
              (cond
                (str/starts-with? s "__commands__") (assoc m :commands-uid uid)
                (str/starts-with? s "__state__")    (assoc m :state-uid uid)
                :else m))
            {} rows)))

(defn read-state [graph state-uid]
  (when-let [s (ffirst
                 (roam-q graph
                   (str "[:find ?s :where
                          [?p :block/uid \"" (esc-dq state-uid) "\"]
                          [?p :block/children ?c]
                          [?c :block/string ?s]]")))]
    (json/parse-string s true)))

(defn send-command! [graph commands-uid cmd-id cmd-type args]
  (roam-create-block graph commands-uid
    (json/generate-string {:id cmd-id :type cmd-type :args args}))
  (loop [i 0]
    (when (>= i 60)
      (throw (ex-info "Bridge timeout" {:cmd-id cmd-id})))
    (Thread/sleep 50)
    ;; Scoped query: only search children of the commands block
    (let [rows (roam-q graph
                 (str "[:find ?s :where
                        [?p :block/uid \"" (esc-dq commands-uid) "\"]
                        [?p :block/children ?cmd]
                        [?cmd :block/string ?cs]
                        [(clojure.string/includes? ?cs \"" (esc-dq cmd-id) "\")]
                        [?cmd :block/children ?r]
                        [?r :block/string ?s]]"))
          resp (->> rows
                    (map (fn [[s]] (try (json/parse-string s true)
                                        (catch Exception _ nil))))
                    (filter #(= (:id %) cmd-id))
                    first)]
      (if resp
        (do
          (when (= (:status resp) "error")
            (throw (ex-info (str "Bridge error: " (get-in resp [:result :error]))
                            {:cmd-id cmd-id :resp resp})))
          resp)
        (recur (inc i))))))

;; ── Actions ──────────────────────────────────────────────────────────

(defn nav-on! [graph commands-uid scope]
  (let [resp (send-command! graph commands-uid
               (str "nav-" (System/currentTimeMillis)) "nav-mode"
               {:scope (or scope "all")})]
    (println (str "✅ Nav mode ON — "
                  (count (get-in resp [:result :labels])) " blocks labelled"))
    (:result resp)))

(defn nav-off! [graph commands-uid]
  (send-command! graph commands-uid
    (str "navoff-" (System/currentTimeMillis)) "nav-off" {})
  (println "❌ Nav mode OFF"))

(defn print-labels [state]
  (if-let [labels (:labels state)]
    (do
      (println (str "  " (count labels) " labelled blocks:"))
      (doseq [[lk v] (sort-by (comp str key) labels)]
        (let [uid (if (map? v) (:uid v) v)
              region (if (map? v) (:region v) "")]
          (println (str "    " (name lk) " → " uid
                        (when (= region "sidebar") " [sidebar]"))))))
    (println "  No labels active. Send --on first.")))

(defn get-block-string [graph uid]
  (ffirst (roam-q graph
            (str "[:find ?s :where [?b :block/uid \"" (esc-dq uid) "\"] [?b :block/string ?s]]"))))

(defn resolve-uid [state label]
  (let [v (get (:labels state) (keyword (str/upper-case label)))]
    (if (map? v) (:uid v) v)))

(defn act-on-label! [graph state label]
  (let [uid (resolve-uid state label)]
    (if-not uid
      (do (println (str "⚠️  Label " (str/upper-case label) " not found."))
          (when-let [lbls (:labels state)]
            (println (str "   Available: " (str/join ", " (sort (map name (keys lbls))))))))
      (let [text (or (get-block-string graph uid) "")
            ts   (.format (java.time.LocalTime/now)
                   (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
            new  (str text " ✅ [" ts "]")]
        (println (str "🎯 " (str/upper-case label) " → " uid " → \"" text "\""))
        (roam-update-block graph uid new)
        (println (str "✏️  → \"" new "\""))))))

(defn ensure-labels!
  "Return state with labels, auto-enabling nav-mode if needed."
  [graph commands-uid state-uid scope]
  (let [state (read-state graph state-uid)]
    (if (:labels state)
      state
      ;; nav-on! response includes labels — use directly, no re-read needed
      (let [result (nav-on! graph commands-uid scope)]
        {:labels (:labels result)}))))

(defn extract-window-uid
  "Extract the page/block uid from a sidebar window-id string.
   e.g. 'sidebar-outline-03-26-2026' → '03-26-2026'
        'sidebar-block-J3n66dJ3t' → 'J3n66dJ3t'"
  [window-id]
  (when window-id
    (let [parts (str/split window-id #"-" 3)]
      (when (>= (count parts) 3) (nth parts 2)))))

(defn find-sidebar-windows
  "Find all sidebar window-ids that contain a given block uid.
   Returns a vec of window-id strings, ordered by sidebar window order."
  [graph state uid]
  (let [sidebar (sort-by :order (:sidebar state))]
    (->> sidebar
         (keep (fn [w]
                 (let [root-uid (or (:block-uid w)
                                    (extract-window-uid (:window-id w)))]
                   ;; Check if uid IS the root or is a descendant
                   (when root-uid
                     (if (= root-uid uid)
                       (:window-id w)
                       ;; Walk ancestors
                       (loop [cur uid depth 0]
                         (when (and cur (< depth 20))
                           (let [parent (ffirst
                                          (roam-q graph
                                            (str "[:find ?pu :where
                                                   [?p :block/children ?b]
                                                   [?b :block/uid \"" (esc-dq cur) "\"]
                                                   [?p :block/uid ?pu]]")))]
                             (cond
                               (nil? parent) nil
                               (= parent root-uid) (:window-id w)
                               :else (recur parent (inc depth)))))))))))
         vec)))

(defn select-block!
  "Select block(s) by label. Accepts a single label or comma-separated labels.
   Options:
   :sidebar - if truthy, select in sidebar instead of main view
              if a number, select nth sidebar instance (1-based)
   :edit    - if truthy, focus block text for editing (cursor in textarea)
              otherwise, highlight/select the block(s) without entering edit mode"
  [graph commands-uid state label-str {:keys [sidebar edit]}]
  (let [label-keys (map str/trim (str/split (str/upper-case label-str) #","))
        resolved (keep (fn [lbl]
                         (when-let [uid (resolve-uid state lbl)]
                           {:label lbl :uid uid
                            :text (or (get-block-string graph uid) "")}))
                       label-keys)
        missing  (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) label-keys)]

    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " missing)))
      (when-let [lbls (:labels state)]
        (println (str "   Available: " (str/join ", " (sort (map name (keys lbls))))))))

    (when (seq resolved)
      (let [uids (mapv :uid resolved)
            ;; For sidebar, resolve window-id from first block
            first-uid (first uids)
            wid  (if sidebar
                   (let [sw (find-sidebar-windows graph state first-uid)
                         n  (if (number? sidebar) (dec sidebar) 0)]
                     (if (seq sw)
                       (if (< n (count sw))
                         (nth sw n)
                         (do (println (str "⚠️  Only " (count sw)
                                           " sidebar instance(s), requested #" (inc n)))
                             (last sw)))
                       (do (println "⚠️  Block not found in any sidebar pane, using main")
                           "main-window")))
                   "main-window")
            mode (if edit "edit" "focus")]
        (send-command! graph commands-uid
          (str "sel-" (System/currentTimeMillis)) "select-block"
          {:uids uids :window_id wid :mode mode})
        (doseq [{:keys [label uid text]} resolved]
          (println (str (if edit "✏️  " "🎯 ")
                        label " → " uid
                        (if edit " editing" " selected")
                        (if (= wid "main-window") "" (str " [" wid "]"))))
          (println (str "   \"" text "\"")))))))

(defn- get-current-selection
  "Get the current selection from the bridge via get-view (always fresh)."
  [graph commands-uid]
  (let [resp (send-command! graph commands-uid
               (str "gv-" (System/currentTimeMillis)) "get-view" {})]
    (vec (or (get-in resp [:result :selected]) []))))

(defn select-add!
  "Add block(s) to the current selection without clearing existing."
  [graph commands-uid state label-str]
  (let [label-keys (map str/trim (str/split (str/upper-case label-str) #","))
        current    (get-current-selection graph commands-uid)
        new-uids   (keep (fn [lbl] (resolve-uid state lbl)) label-keys)
        combined   (vec (distinct (concat current new-uids)))]
    (when (seq new-uids)
      (send-command! graph commands-uid
        (str "sel-" (System/currentTimeMillis)) "select-block"
        {:uids combined :window_id "main-window" :mode "focus"})
      (println (str "🎯 Added " (count new-uids) " → " (count combined) " total selected")))))

(defn select-remove!
  "Remove block(s) from the current selection."
  [graph commands-uid state label-str]
  (let [label-keys  (map str/trim (str/split (str/upper-case label-str) #","))
        current     (get-current-selection graph commands-uid)
        remove-uids (set (keep (fn [lbl] (resolve-uid state lbl)) label-keys))
        remaining   (vec (remove remove-uids current))]
    (if (seq remaining)
      (do
        (send-command! graph commands-uid
          (str "sel-" (System/currentTimeMillis)) "select-block"
          {:uids remaining :window_id "main-window" :mode "focus"})
        (println (str "🎯 Removed " (count remove-uids) " → " (count remaining) " remaining")))
      (do
        (send-command! graph commands-uid
          (str "clrsel-" (System/currentTimeMillis)) "clear-selection" {})
        (println "🎯 Selection cleared")))))

(defn move-blocks!
  "Move block(s) by label under a target parent block.
   source-str: comma-separated labels of blocks to move
   target: {:label \"D\"}, {:uid \"abc123\"}, {:page \"Title\"},
           {:before \"B\"}, or {:after \"B\"}
   order: \"first\" or \"last\" (default \"last\", ignored for before/after)"
  [graph state source-str target order]
  (let [src-labels (map str/trim (str/split (str/upper-case source-str) #","))
        ;; For --before/--after, resolve the anchor block to find parent + order
        anchor-label (or (:before target) (:after target))
        anchor-uid   (when anchor-label (resolve-uid state anchor-label))
        anchor-parent (when anchor-uid (get-parent-uid graph anchor-uid))
        anchor-order  (when anchor-uid (get-block-order graph anchor-uid))
        ;; Resolve target UID: for before/after use the anchor's parent
        tgt-uid    (or (:uid target)
                       (when (:label target) (resolve-uid state (:label target)))
                       (when (:page target) (get-page-uid graph (:page target)))
                       anchor-parent)
        tgt-name   (or (:label target) (:page target) (:uid target)
                       (when (:before target) (str "before " (:before target)))
                       (when (:after target) (str "after " (:after target))))
        ;; For before/after, compute numeric order; otherwise use first/last
        order      (cond
                     (:before target) anchor-order
                     (:after target)  (when anchor-order (inc anchor-order))
                     :else            (or order "last"))
        resolved   (keep (fn [lbl]
                           (when-let [uid (resolve-uid state lbl)]
                             {:label lbl :uid uid
                              :text (or (get-block-string graph uid) "")}))
                         src-labels)
        missing    (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) src-labels)]

    ;; Validate anchor exists for before/after
    (when (and anchor-label (not anchor-uid))
      (throw (ex-info (str "Anchor label " anchor-label " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))

    (when-not tgt-uid
      (throw (ex-info (str "Target " tgt-name " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))

    (when (seq missing)
      (println (str "⚠️  Source label(s) not found: " (str/join ", " missing))))

    (when (seq resolved)
      (let [tgt-text (or (get-block-string graph tgt-uid) "")]
        (println (str "📦 Moving " (count resolved) " block(s) " tgt-name))
        (println (str "   target: \"" tgt-text "\"")))
      (doseq [{:keys [label uid text]} resolved]
        (roam-move-block graph uid tgt-uid order)
        (println (str "   ✅ " label " → " uid " moved"))
        (println (str "      \"" text "\""))))))

(defn move-selected!
  "Move the currently selected (highlighted) blocks to a target parent.
   Reads selected UIDs from __state__.selected, moves them, then re-selects.
   target: {:label \"D\"} or {:uid \"abc123\"}
   order: \"first\" or \"last\" (default \"last\")"
  [graph commands-uid state target order]
  (let [selected (:selected state)
        tgt-uid  (or (:uid target)
                     (when (:label target) (resolve-uid state (:label target)))
                     (when (:page target) (get-page-uid graph (:page target))))
        tgt-name (or (:label target) (:page target) (:uid target))
        order    (or order "last")]

    (when-not (seq selected)
      (throw (ex-info "No blocks currently selected. Use --select first." {})))

    (when-not tgt-uid
      (throw (ex-info (str "Target " tgt-name " not found") {})))

    (let [tgt-text (or (get-block-string graph tgt-uid) "")]
      (println (str "📦 Moving " (count selected) " selected block(s) under "
                    tgt-name " → " tgt-uid " (" order ")"))
      (println (str "   target: \"" tgt-text "\"")))

    (doseq [uid selected]
      (let [text (or (get-block-string graph uid) "")]
        (roam-move-block graph uid tgt-uid order)
        (println (str "   ✅ " uid " moved"))
        (println (str "      \"" text "\""))))

    ;; Re-select the moved blocks at their new location
    (Thread/sleep 200) ;; let Roam process the moves
    (send-command! graph commands-uid
      (str "sel-" (System/currentTimeMillis)) "select-block"
      {:uids (vec selected) :window_id "main-window" :mode "focus"})
    (println (str "🎯 " (count selected) " block(s) re-selected"))))

(defn delete-blocks!
  "Delete block(s) by label. Accepts comma-separated labels.
   Sends delete-blocks command to the bridge extension."
  [graph commands-uid state label-str]
  (let [label-keys (map str/trim (str/split (str/upper-case label-str) #","))
        resolved   (keep (fn [lbl]
                           (when-let [uid (resolve-uid state lbl)]
                             {:label lbl :uid uid
                              :text (or (get-block-string graph uid) "")}))
                         label-keys)
        missing    (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) label-keys)]

    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " missing)))
      (when-let [lbls (:labels state)]
        (println (str "   Available: " (str/join ", " (sort (map name (keys lbls))))))))

    (when (seq resolved)
      (let [labels-to-delete (mapv :label resolved)
            resp (send-command! graph commands-uid
                   (str "del-" (System/currentTimeMillis)) "delete-blocks"
                   {:labels labels-to-delete})]
        (doseq [{:keys [label uid text]} resolved]
          (println (str "   🗑️  " label " → " uid " deleted"))
          (println (str "      \"" text "\"")))
        (println (str "✅ " (get-in resp [:result :count]) " block(s) deleted"))))))

(defn reorder!
  "Move block(s) to first or last child of their current parent.
   label-str: comma-separated labels
   order: \"first\" or \"last\""
  [graph state label-str order]
  (let [label-keys (map str/trim (str/split (str/upper-case label-str) #","))
        order      (or order "last")
        resolved   (keep (fn [lbl]
                           (when-let [uid (resolve-uid state lbl)]
                             {:label lbl :uid uid
                              :text (or (get-block-string graph uid) "")}))
                         label-keys)
        missing    (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) label-keys)]

    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " missing))))

    (doseq [{:keys [label uid text]} resolved]
      (let [parent (get-parent-uid graph uid)]
        (when-not parent
          (throw (ex-info (str "Cannot find parent of block " label " (" uid ")") {})))
        (roam-move-block graph uid parent order)
        (println (str "   ✅ " label " → " uid " moved to " order))
        (println (str "      \"" text "\""))))))

(defn zoom!
  "Zoom into a block by label (open it as the main view)."
  [graph state label]
  (let [uid (resolve-uid state (str/trim label))]
    (when-not uid
      (throw (ex-info (str "Label " (str/upper-case label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [text (or (get-block-string graph uid) "")]
      (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" uid}})
      (println (str "🔎 " (str/upper-case label) " → " uid " zoomed"))
      (println (str "   \"" text "\"")))))

(defn zoom-parent!
  "Zoom into the parent of a block by label."
  [graph state label]
  (let [uid (resolve-uid state (str/trim label))]
    (when-not uid
      (throw (ex-info (str "Label " (str/upper-case label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [parent (get-parent-uid graph uid)]
      (when-not parent
        (throw (ex-info (str "Block " (str/upper-case label) " has no parent") {})))
      (let [text (or (get-block-string graph parent) "")]
        (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" parent}})
        (println (str "🔎 parent of " (str/upper-case label) " → " parent " zoomed"))
        (println (str "   \"" text "\""))))))

(defn- create-and-focus-block!
  "Create an empty block under parent-uid and focus it for editing.
   order: \"first\", \"last\", or a numeric position."
  [graph parent-uid order label]
  (let [new-uid   (str "nb-" (subs (str (java.util.UUID/randomUUID)) 0 9))
        api-order (cond (= order "first") 0
                        (= order "last")  "last"
                        :else              order)]
    (roam-api graph "data.block.create"
              {"location" {"parent-uid" parent-uid "order" api-order}
               "block"    {"uid" new-uid "string" ""}})
    (roam-api graph "ui.setBlockFocusAndSelection"
              {"location" {"block-uid" new-uid "window-id" "main-window"}})
    (println (str "✏️  New block " new-uid " created (" order ") under " label))
    (println "   Ready for input.")))

(defn new-block!
  "Create a new block on a page and focus it for editing.
   page-title: page to create under (uses current page if nil/empty)
   order: \"first\" or \"last\" (default \"first\")"
  [graph state-uid page-title order]
  (let [order    (or order "first")
        page-uid (if (not-empty page-title)
                   (let [uid (get-page-uid graph page-title)]
                     (when-not uid
                       (throw (ex-info (str "Page \"" page-title "\" not found") {})))
                     uid)
                   (let [state (read-state graph state-uid)]
                     (or (get-in state [:main :uid])
                         (throw (ex-info "Cannot determine current page" {})))))]
    ;; Navigate to the page if a title was specified
    (when (not-empty page-title)
      (roam-api graph "ui.mainWindow.openPage" {"page" {"uid" page-uid}}))
    (create-and-focus-block! graph page-uid order (or page-title "current page"))))

(defn new-sibling!
  "Create a new sibling block in the current parent context.
   Reads the focused block from state, finds its parent, and creates
   a new block at the top or bottom of that parent's children.
   Falls back to current view root if no block is focused."
  [graph state-uid order]
  (let [order  (or order "first")
        state  (read-state graph state-uid)
        ;; Try focused block first, fall back to main view uid
        focus-uid (get-in state [:focused :block-uid])
        parent-uid (if focus-uid
                     (or (get-parent-uid graph focus-uid)
                         (get-in state [:main :uid]))
                     (get-in state [:main :uid]))]
    (when-not parent-uid
      (throw (ex-info "Cannot determine current parent" {})))
    (create-and-focus-block! graph parent-uid order
                             (if focus-uid
                               (str "parent of " focus-uid)
                               "current page"))))

(defn new-before-after!
  "Create a new empty block before or after a labeled block, and focus it.
   position: :before or :after"
  [graph state label position]
  (let [uid (resolve-uid state (str/trim label))]
    (when-not uid
      (throw (ex-info (str "Label " (str/upper-case label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [parent (get-parent-uid graph uid)
          order  (get-block-order graph uid)]
      (when-not parent
        (throw (ex-info (str "Cannot find parent of block " (str/upper-case label)) {})))
      (let [insert-order (if (= position :before) order (inc order))]
        (create-and-focus-block! graph parent insert-order
                                 (str (name position) " " (str/upper-case label)))))))

(defn new-child!
  "Create a new child block under a block targeted by label or UID.
   Tries label resolution first, falls back to raw UID.
   order: \"first\" or \"last\" (default \"first\")"
  [graph state ref order]
  (when (empty? ref)
    (throw (ex-info "Label or block UID required for --new-child" {})))
  (let [order      (or order "first")
        ;; Try label resolution first, fall back to raw UID
        label-uid  (when state (resolve-uid state ref))
        parent-uid (or label-uid ref)
        label      (if label-uid
                     (str (str/upper-case ref) " → " parent-uid)
                     parent-uid)]
    (create-and-focus-block! graph parent-uid order label)))

(defn open-in-sidebar!
  "Open a block by label in the right sidebar."
  [graph state label]
  (let [uid (resolve-uid state (str/trim label))]
    (when-not uid
      (throw (ex-info (str "Label " (str/upper-case label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [text (or (get-block-string graph uid) "")]
      (roam-api graph "ui.rightSidebar.addWindow"
                {"window" {"type" "outline" "block-uid" uid}})
      (println (str "📌 " (str/upper-case label) " → " uid " opened in sidebar"))
      (println (str "   \"" text "\"")))))

(defn zoom-out!
  "Zoom out to parent of the current root block in main view."
  [graph state-uid]
  (let [state     (read-state graph state-uid)
        current   (get-in state [:main :uid])]
    (when-not current
      (throw (ex-info "Cannot determine current view" {})))
    (let [parent (get-parent-uid graph current)]
      (when-not parent
        (throw (ex-info "Already at page level — cannot zoom out further" {})))
      (let [text (or (get-block-string graph parent) "")]
        (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" parent}})
        (println (str "🔎 zoomed out → " parent))
        (println (str "   \"" text "\""))))))

(defn fold!
  "Fold (collapse) or unfold (expand) block(s) by label.
   label-str: comma-separated labels
   open?: false to fold, true to unfold"
  [graph state label-str open?]
  (let [label-keys (map str/trim (str/split (str/upper-case label-str) #","))
        resolved   (keep (fn [lbl]
                           (when-let [uid (resolve-uid state lbl)]
                             {:label lbl :uid uid
                              :text (or (get-block-string graph uid) "")}))
                         label-keys)
        missing    (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) label-keys)
        verb       (if open? "unfolded" "folded")]

    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " missing))))

    (doseq [{:keys [label uid text]} resolved]
      (roam-set-block-open graph uid open?)
      (println (str "   " (if open? "📂" "📁") " " label " → " uid " " verb))
      (println (str "      \"" text "\"")))))

;; ── Main ─────────────────────────────────────────────────────────────

(def cli-spec
  {:graph   {:desc "Roam graph name"   :default "tmem"}
   :on      {:desc "Turn on nav-mode"  :coerce :boolean}
   :off     {:desc "Turn off nav-mode" :coerce :boolean}
   :labels  {:desc "Print current label map" :coerce :boolean}
   :label   {:desc "Act on block by label character"}
   :select  {:desc "Select (highlight) block by label character"}
   :select-add {:desc "Add block(s) to current selection"}
   :select-remove {:desc "Remove block(s) from current selection"}
   :e       {:desc "Edit mode: focus block text for typing" :coerce :boolean}
   :edit    {:desc "Edit mode: focus block text for typing" :coerce :boolean}
   :delete  {:desc "Delete block(s) by label (comma-separated)"}
   :new-block {:desc "Create new block on page (title), or current page if empty"}
   :new-block-last {:desc "Create new block at bottom of current page" :coerce :boolean}
   :new-sibling {:desc "New block in current parent (top/bottom)" :coerce :boolean}
   :new-before {:desc "New block before a labeled block"}
   :new-after {:desc "New block after a labeled block"}
   :new-child {:desc "Create new child block under a block UID"}
   :open-sidebar {:desc "Open a block by label in the right sidebar"}
   :zoom    {:desc "Zoom into a block by label"}
   :zoom-out {:desc "Zoom out to parent of current view" :coerce :boolean}
   :zoom-parent {:desc "Zoom into parent of a block by label"}
   :fold    {:desc "Fold (collapse) block(s) by label"}
   :unfold  {:desc "Unfold (expand) block(s) by label"}
   :reorder {:desc "Move block(s) to first/last within current parent"}
   :move    {:desc "Move block(s) by label (comma-separated)"}
   :move-selected {:desc "Move currently selected blocks" :coerce :boolean}
   :to      {:desc "Target parent block label for --move/--move-selected"}
   :ref     {:desc "Target parent block UID for --move/--move-selected"}
   :page    {:desc "Target page by title for --move/--move-selected"}
   :before  {:desc "Move before this label (sibling placement)"}
   :after   {:desc "Move after this label (sibling placement)"}
   :first   {:desc "Insert as first child (default: last)" :coerce :boolean}
   :last    {:desc "Insert as last child" :coerce :boolean}
   :s       {:desc "Select in sidebar (optionally nth: -s 2)"}
   :sidebar {:desc "Select in sidebar (optionally nth: --sidebar 2)"}
   :scope   {:desc "Nav scope: main|sidebar|all" :default "all"}})

(let [opts    (cli/parse-opts *command-line-args* {:spec cli-spec})
      {:keys [graph on off labels label select scope move to delete ref page reorder before after fold unfold zoom]} opts
      move-order (if (:first opts) "first" "last")
      move-target (cond
                    to   {:label (str/upper-case (str/trim to))}
                    (not-empty ref)    {:uid (str/trim ref)}
                    (not-empty page)   {:page (str/trim page)}
                    (not-empty before) {:before (str/upper-case (str/trim before))}
                    (not-empty after)  {:after (str/upper-case (str/trim after))}
                    :else nil)
      ;; -s and --sidebar are aliases; -s takes priority
      ;; value can be: true (bare flag), or a number string
      sb-raw  (or (:s opts) (:sidebar opts))
      sb      (cond
                (nil? sb-raw)    nil
                (true? sb-raw)   1
                (string? sb-raw) (or (parse-long sb-raw) 1)
                (number? sb-raw) sb-raw
                :else            1)
      ;; -e and --edit are aliases
      edit?   (or (:e opts) (:edit opts))
      graph   (or graph default-graph)
      {:keys [commands-uid state-uid]} (find-bridge-uids graph)]

  (when-not commands-uid
    (println "❌ Bridge not loaded.")
    (System/exit 1))

  (try
    (cond
      on     (nav-on! graph commands-uid scope)
      off    (nav-off! graph commands-uid)
      labels (print-labels (ensure-labels! graph commands-uid state-uid scope))
      select (select-block! graph commands-uid
                            (ensure-labels! graph commands-uid state-uid scope) select
                            {:sidebar sb :edit edit?})
      (:select-add opts) (select-add! graph commands-uid
                                      (ensure-labels! graph commands-uid state-uid scope)
                                      (:select-add opts))
      (:select-remove opts) (select-remove! graph commands-uid
                                            (ensure-labels! graph commands-uid state-uid scope)
                                            (:select-remove opts))
      delete (delete-blocks! graph commands-uid
                             (ensure-labels! graph commands-uid state-uid scope) delete)
      (contains? opts :new-block) (let [v (:new-block opts)]
                                   (new-block! graph state-uid (when (string? v) v)
                                               (if (:last opts) "last" "first")))
      (:new-block-last opts) (new-block! graph state-uid nil "last")
      (:new-sibling opts) (new-sibling! graph state-uid (if (:last opts) "last" "first"))
      (not-empty (:new-before opts)) (new-before-after! graph
                                                (ensure-labels! graph commands-uid state-uid scope)
                                                (:new-before opts) :before)
      (not-empty (:new-after opts)) (new-before-after! graph
                                                (ensure-labels! graph commands-uid state-uid scope)
                                                (:new-after opts) :after)
      (not-empty (:new-child opts)) (new-child! graph
                                                (ensure-labels! graph commands-uid state-uid scope)
                                                (:new-child opts)
                                                (if (:last opts) "last" "first"))
      (:open-sidebar opts) (open-in-sidebar! graph (ensure-labels! graph commands-uid state-uid scope) (:open-sidebar opts))
      zoom   (zoom! graph (ensure-labels! graph commands-uid state-uid scope) zoom)
      (:zoom-out opts) (zoom-out! graph state-uid)
      (:zoom-parent opts) (zoom-parent! graph (ensure-labels! graph commands-uid state-uid scope) (:zoom-parent opts))
      fold   (fold! graph (ensure-labels! graph commands-uid state-uid scope) fold false)
      unfold (fold! graph (ensure-labels! graph commands-uid state-uid scope) unfold true)
      reorder (reorder! graph (ensure-labels! graph commands-uid state-uid scope)
                        reorder move-order)
      move   (if-not move-target
               (println "⚠️  --move requires --to <label>, --ref <uid>, or --page <title>")
               (move-blocks! graph (ensure-labels! graph commands-uid state-uid scope)
                             move move-target move-order))
      (:move-selected opts)
             (if-not move-target
               (println "⚠️  --move-selected requires --to <label>, --ref <uid>, or --page <title>")
               (move-selected! graph commands-uid
                               (ensure-labels! graph commands-uid state-uid scope)
                               move-target move-order))
      label  (act-on-label! graph (ensure-labels! graph commands-uid state-uid scope) label)
      :else  (do (println "Usage:")
                 (println "  bb bridge --on              # turn on nav labels")
                 (println "  bb bridge --off             # turn off nav labels")
                 (println "  bb bridge --labels          # show label→uid map")
                 (println "  bb bridge --select A        # highlight block A")
                 (println "  bb bridge --select A,B,C    # highlight multiple blocks")
                 (println "  bb bridge --select A -e     # focus block A for editing")
                 (println "  bb bridge --select A -s     # highlight block A in sidebar")
                                  (println "  bb bridge --select A -s -e    # edit block A in sidebar")
                                  (println "  bb bridge --select-add A,B    # add to current selection")
                                  (println "  bb bridge --select-remove A   # remove from selection")
                 (println "  bb bridge --new-block 'Page'  # new block at top of page, focused")
                 (println "  bb bridge --new-block         # new block at top of current page")
                 (println "  bb bridge --new-block --last  # new block at bottom of current page")
                 (println "  bb bridge --new-block-last    # shorthand for above")
                 (println "  bb bridge --new-sibling       # new block at top of current parent")
                 (println "  bb bridge --new-sibling --last # new block at bottom of current parent")
                 (println "  bb bridge --new-before A      # new block before block A, focused")
                 (println "  bb bridge --new-after A       # new block after block A, focused")
                 (println "  bb bridge --new-child A       # new child at top of labeled block")
                 (println "  bb bridge --new-child uid     # new child at top of block by UID")
                 (println "  bb bridge --new-child A --last # new child at bottom")
                 (println "  bb bridge --open-sidebar A   # open block A in right sidebar")
                 (println "  bb bridge --zoom A           # zoom into block A")
                 (println "  bb bridge --zoom-out         # zoom out to parent of current view")
                 (println "  bb bridge --zoom-parent A    # zoom into parent of block A")
                 (println "  bb bridge --fold A           # collapse block A")
                 (println "  bb bridge --unfold A,B      # expand blocks A and B")
                 (println "  bb bridge --delete A        # delete block A")
                 (println "  bb bridge --delete A,B,C    # delete multiple blocks")
                 (println "  bb bridge --reorder A            # move to last child of parent")
                 (println "  bb bridge --reorder A --first    # move to first child of parent")
                 (println "  bb bridge --move A --to D       # move under label D (last child)")
                 (println "  bb bridge --move A --ref uid    # move under block UID")
                 (println "  bb bridge --move A --page 'P'   # move to top-level of page P")
                 (println "  bb bridge --move A --before B   # move A before sibling B")
                 (println "  bb bridge --move A --after B    # move A after sibling B")
                 (println "  bb bridge --move A --to D --first # as first child")
                 (println "  bb bridge --move-selected --to D  # move selected blocks")
                 (println "  bb bridge --label A         # act on block A")))
    (catch clojure.lang.ExceptionInfo e
      (println (str "⚠️  " (ex-message e)))
      (when-let [avail (:available (ex-data e))]
        (println (str "   Available: " (str/join ", " avail))))
      (System/exit 1))))

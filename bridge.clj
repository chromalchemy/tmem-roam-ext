#!/usr/bin/env bb

;; bridge.bb — Roam bridge library.
;;
;; Callable directly via bb eval:
;;   bb -e '(load-file "bridge.clj") (select! [:A] {:edit true})'
;;   bb -e '(load-file "bridge.clj") (move! {:labels [:A]} {:label :D})'
;;
;; All public functions handle their own setup (graph, bridge uids, state).

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[clojure.string :as str]
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

;; ── Internal helpers ─────────────────────────────────────────────────

(defn nav-on!
  "Send nav-mode ON command. Internal — used by ensure-labels! and hats-on!."
  [graph commands-uid scope]
  (let [resp (send-command! graph commands-uid
               (str "nav-" (System/currentTimeMillis)) "nav-mode"
               {:scope (or scope "all")})]
    (println (str "✅ Nav mode ON — "
                  (count (get-in resp [:result :labels])) " blocks labelled"))
    (:result resp)))

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
  (let [k (keyword (str/upper-case (name label)))
        v (get (:labels state) k)]
    (if (map? v) (:uid v) v)))

(defn resolve-labels
  "Resolve a vector of keyword labels to [{:label :uid :text}].
   Labels are case-insensitive keywords, e.g. [:A :b :NL]."
  [graph state labels]
  (let [labels   (mapv #(keyword (str/upper-case (name %))) labels)
        resolved (keep (fn [lbl]
                         (when-let [uid (resolve-uid state lbl)]
                           {:label lbl :uid uid
                            :text (or (get-block-string graph uid) "")}))
                       labels)
        missing  (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) labels)]
    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " (map name missing))))
      (when-let [lbls (:labels state)]
        (println (str "   Available: " (str/join ", " (sort (map name (keys lbls))))))))
    {:resolved (vec resolved) :missing (vec missing)}))

(defn is-descendant?
  "Check if target-uid is a descendant of source-uid (or is source-uid itself).
   Walks up the tree from target. Returns true if moving source under target
   would create a cycle."
  [graph source-uid target-uid]
  (loop [cur target-uid depth 0]
    (cond
      (nil? cur)              false
      (= cur source-uid)      true
      (>= depth 50)           false ;; safety limit
      :else (recur (get-parent-uid graph cur) (inc depth)))))

(defn resolve-target
  "Resolve a target map to {:uid, :name, :order, :anchor-uid}.
   target: {:label :D}, {:uid \"...\"}, {:page \"...\"}, {:before :B}, {:after :B}
   default-order: :first or :last"
  [graph state target default-order]
  (let [anchor-label  (or (:before target) (:after target))
        anchor-uid    (when anchor-label (resolve-uid state anchor-label))
        anchor-parent (when anchor-uid (get-parent-uid graph anchor-uid))
        anchor-order  (when anchor-uid (get-block-order graph anchor-uid))]
    (when (and anchor-label (not anchor-uid))
      (throw (ex-info (str "Anchor label " (name anchor-label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [uid  (or (:uid target)
                   (when (:label target) (resolve-uid state (:label target)))
                   (when (:page target) (get-page-uid graph (:page target)))
                   anchor-parent)
          tgt-name (or (some-> (:label target) name)
                       (:page target) (:uid target)
                       (when (:before target) (str "before " (name (:before target))))
                       (when (:after target) (str "after " (name (:after target)))))
          order (cond
                  (:before target) anchor-order
                  (:after target)  (when anchor-order (inc anchor-order))
                  :else            (or default-order :last))]
      (when-not uid
        (throw (ex-info (str "Target " tgt-name " not found")
                        {:available (when-let [lbls (:labels state)]
                                      (sort (map name (keys lbls))))})))
      {:uid uid :name tgt-name :order order :anchor-uid anchor-uid})))

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

(defn- get-current-selection
  "Get the current selection from the bridge via get-view (always fresh)."
  [graph commands-uid]
  (let [resp (send-command! graph commands-uid
               (str "gv-" (System/currentTimeMillis)) "get-view" {})]
    (vec (or (get-in resp [:result :selected]) []))))

;; ── Core move/link operations (shared by all source types) ───────

(defn move-uids!
  "Core move operation. Moves a vec of {:uid :label :text} blocks to a target.
   Handles cycle checks, positional ordering, and alias backfill."
  [graph uids tgt alias?]
  (let [tgt-text (or (get-block-string graph (:uid tgt)) "")]
    (println (str "📦 Moving " (count uids) " block(s) → " (:name tgt)
                  (when alias? " (with alias)")))
    (println (str "   target: \"" tgt-text "\"")))
  (doseq [[idx {:keys [label uid text]}] (map-indexed vector uids)]
    (let [cycle-target (or (:anchor-uid tgt) (:uid tgt))
          skip? (is-descendant? graph uid cycle-target)
          effective-order (if (number? (:order tgt))
                            (+ (:order tgt) idx)
                            (:order tgt))
          orig-parent (when (and alias? (not skip?)) (get-parent-uid graph uid))
          orig-order  (when (and alias? (not skip?)) (get-block-order graph uid))]
      (if skip?
        (do (println (str "   ⛔ " (or label uid) " SKIPPED — target is a descendant"))
            (println "      Moving a block under its own subtree would corrupt the graph."))
        (do
          (roam-move-block graph uid (:uid tgt) effective-order)
          (when (and alias? orig-parent)
            (roam-api graph "data.block.create"
                      {"location" {"parent-uid" orig-parent "order" (or orig-order "last")}
                       "block"    {"string" (str "((" uid "))")}})
            (println (str "   🔗 alias ((" uid ")) left at original location")))
          (println (str "   ✅ " (or label uid) " moved"))
          (println (str "      \"" text "\"")))))))

(defn link-uids!
  "Core link operation. Creates ((uid)) refs at target for a vec of {:uid :label :text}."
  [graph uids tgt]
  (let [tgt-text (or (get-block-string graph (:uid tgt)) "")]
    (println (str "🔗 Linking " (count uids) " block(s) → " (:name tgt)))
    (println (str "   target: \"" tgt-text "\"")))
  (doseq [[idx {:keys [label uid text]}] (map-indexed vector uids)]
    (let [effective-order (if (number? (:order tgt))
                            (+ (:order tgt) idx)
                            (:order tgt))]
      (roam-api graph "data.block.create"
                {"location" {"parent-uid" (:uid tgt) "order" effective-order}
                 "block"    {"string" (str "((" uid "))")}})
      (println (str "   🔗 " (or label uid) " → ((" uid ")) created"))
      (println (str "      \"" text "\"")))))

(defn- resolve-source-uids
  "Resolve source blocks from labels, selection, or direct UID.
   Returns a vec of {:uid :label :text} maps."
  [graph state {:keys [labels selected source-uid]}]
  (cond
    labels   (:resolved (resolve-labels graph state labels))
    selected (mapv (fn [uid] {:uid uid :label nil
                              :text (or (get-block-string graph uid) "")})
                   selected)
    source-uid [{:uid source-uid :label nil
                 :text (or (get-block-string graph source-uid) "")}]))

(defn do-move!
  "Unified move: resolve sources and target, then move.
   source: {:labels [:A :B]} or {:selected [...]} or {:source-uid \"abc\"}
   target-map, order, alias? as before."
  [graph state source target-map order alias?]
  (let [uids (resolve-source-uids graph state source)
        tgt  (resolve-target graph state target-map order)]
    (when (seq uids)
      (move-uids! graph uids tgt alias?))))

(defn do-link!
  "Unified link: resolve sources and target, then create refs."
  [graph state source target-map order]
  (let [uids (resolve-source-uids graph state source)
        tgt  (resolve-target graph state target-map order)]
    (when (seq uids)
      (link-uids! graph uids tgt))))

(defn do-move-selected!
  "Move selected blocks with re-selection after move."
  [graph commands-uid state target-map order alias?]
  (let [selected (:selected state)]
    (when-not (seq selected)
      (throw (ex-info "No blocks currently selected. Use --select first." {})))
    (do-move! graph state {:selected selected} target-map order alias?)
    ;; Re-select the moved blocks at their new location
    (Thread/sleep 200)
    (send-command! graph commands-uid
      (str "sel-" (System/currentTimeMillis)) "select-block"
      {:uids (vec selected) :window_id "main-window" :mode "focus"})
    (println (str "🎯 " (count selected) " block(s) re-selected"))))

(defn- create-and-focus-block!
  "Create an empty block under parent-uid and focus it for editing.
   order: \"first\", \"last\", or a numeric position."
  [graph parent-uid order label]
  (let [new-uid   (str "nb-" (subs (str (java.util.UUID/randomUUID)) 0 9))
        api-order (cond (= order :first) 0
                        (= order :last)  "last"
                        :else            order)]
    (roam-api graph "data.block.create"
              {"location" {"parent-uid" parent-uid "order" api-order}
               "block"    {"uid" new-uid "string" ""}})
    (roam-api graph "ui.setBlockFocusAndSelection"
              {"location" {"block-uid" new-uid "window-id" "main-window"}})
    (println (str "✏️  New block " new-uid " created (" order ") under " label))
    (println "   Ready for input.")))

;; ── Bridge setup (memoized per invocation) ───────────────────────────

(def ^:private -bridge
  "Resolve graph + bridge uids once per bb process."
  (memoize
    (fn [graph]
      (let [{:keys [commands-uid state-uid]} (find-bridge-uids graph)]
        (when-not commands-uid
          (throw (ex-info "Bridge not loaded" {:graph graph})))
        {:graph graph :commands-uid commands-uid :state-uid state-uid}))))

(defn- ctx
  "Get bridge context, with labels auto-enabled."
  ([] (ctx {}))
  ([{:keys [graph scope] :or {graph default-graph scope "all"}}]
   (let [{:keys [commands-uid state-uid] :as b} (-bridge graph)
         state (ensure-labels! graph commands-uid state-uid scope)]
     (assoc b :state state))))

;; ── Public API ───────────────────────────────────────────────────────
;; All functions below are the intended call surface from Python/Talon.
;; Each handles its own setup via (ctx) or (-bridge).

(defn hats-on!
  "Turn on nav-mode labels."
  ([] (hats-on! {}))
  ([{:keys [graph scope] :or {graph default-graph scope "all"}}]
   (let [{:keys [commands-uid]} (-bridge graph)]
     (nav-on! graph commands-uid scope))))

(defn hats-off!
  "Turn off nav-mode labels."
  ([] (hats-off! {}))
  ([{:keys [graph] :or {graph default-graph}}]
   (let [{:keys [commands-uid]} (-bridge graph)]
     (send-command! graph commands-uid
       (str "navoff-" (System/currentTimeMillis)) "nav-off" {})
     (println "❌ Nav mode OFF"))))

(defn select!
  "Select block(s) by label. opts: {:edit true, :sidebar true/n}"
  ([labels] (select! labels {}))
  ([labels {:keys [sidebar edit]}]
   (let [{:keys [graph commands-uid state]} (ctx)
         {:keys [resolved]} (resolve-labels graph state labels)]
     (when (seq resolved)
       (let [uids (mapv :uid resolved)
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
           (println (str "   \"" text "\""))))))))

(defn select-add!
  "Add block(s) to current selection. labels: vector of keywords, e.g. [:A :B]"
  [labels]
  (let [{:keys [graph commands-uid state]} (ctx)
        current    (get-current-selection graph commands-uid)
        new-uids   (keep #(resolve-uid state %) labels)
        combined   (vec (distinct (concat current new-uids)))]
    (when (seq new-uids)
      (send-command! graph commands-uid
        (str "sel-" (System/currentTimeMillis)) "select-block"
        {:uids combined :window_id "main-window" :mode "focus"})
      (println (str "🎯 Added " (count new-uids) " → " (count combined) " total selected")))))

(defn select-remove!
  "Remove block(s) from current selection. labels: vector of keywords, e.g. [:A :B]"
  [labels]
  (let [{:keys [graph commands-uid state]} (ctx)
        current     (get-current-selection graph commands-uid)
        remove-uids (set (keep #(resolve-uid state %) labels))
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

(defn delete!
  "Delete block(s) by label. labels: vector of keywords, e.g. [:A :B]"
  [labels]
  (let [{:keys [graph commands-uid state]} (ctx)
        {:keys [resolved]} (resolve-labels graph state labels)]
    (when (seq resolved)
      (let [labels-to-delete (mapv (comp name :label) resolved)
            resp (send-command! graph commands-uid
                   (str "del-" (System/currentTimeMillis)) "delete-blocks"
                   {:labels labels-to-delete})]
        (doseq [{:keys [label uid text]} resolved]
          (println (str "   🗑️  " label " → " uid " deleted"))
          (println (str "      \"" text "\"")))
        (println (str "✅ " (get-in resp [:result :count]) " block(s) deleted"))))))

(defn reorder!
  "Move block(s) to first/last within their current parent.
   labels: vector of keywords, e.g. [:A :B]
   order: :first or :last"
  ([labels] (reorder! labels :last))
  ([labels order]
   (let [{:keys [graph state]} (ctx)
         order (name (or order :last))
         {:keys [resolved]} (resolve-labels graph state labels)]
     (doseq [{:keys [label uid text]} resolved]
       (let [parent (get-parent-uid graph uid)]
         (when-not parent
           (throw (ex-info (str "Cannot find parent of block " label " (" uid ")") {})))
         (roam-move-block graph uid parent order)
         (println (str "   ✅ " label " → " uid " moved to " order))
         (println (str "      \"" text "\"")))))))

(comment
  (reorder! [:C] :first))

(defn zoom!
  "Zoom into a block by label keyword."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [text (or (get-block-string graph uid) "")]
      (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" uid}})
      (println (str "🔎 " (name label) " → " uid " zoomed"))
      (println (str "   \"" text "\"")))))

(defn zoom-parent!
  "Zoom into the parent of a block by label keyword."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [parent (get-parent-uid graph uid)]
      (when-not parent
        (throw (ex-info (str "Block " (name label) " has no parent") {})))
      (let [text (or (get-block-string graph parent) "")]
        (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" parent}})
        (println (str "🔎 parent of " (name label) " → " parent " zoomed"))
        (println (str "   \"" text "\""))))))

(defn zoom-out!
  "Zoom out to parent of current view."
  []
  (let [{:keys [graph state-uid]} (-bridge default-graph)
        state     (read-state graph state-uid)
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
  "Fold (collapse) block(s) by label."
  [labels]
  (let [{:keys [graph state]} (ctx)
        {:keys [resolved]} (resolve-labels graph state labels)]
    (doseq [{:keys [label uid text]} resolved]
      (roam-set-block-open graph uid false)
      (println (str "   📁 " label " → " uid " folded"))
      (println (str "      \"" text "\"")))))

(defn unfold!
  "Unfold (expand) block(s) by label."
  [labels]
  (let [{:keys [graph state]} (ctx)
        {:keys [resolved]} (resolve-labels graph state labels)]
    (doseq [{:keys [label uid text]} resolved]
      (roam-set-block-open graph uid true)
      (println (str "   📂 " label " → " uid " unfolded"))
      (println (str "      \"" text "\"")))))

(defn open-sidebar!
  "Open a block by label keyword in the right sidebar."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [text (or (get-block-string graph uid) "")]
      (roam-api graph "ui.rightSidebar.addWindow"
                {"window" {"type" "outline" "block-uid" uid}})
      (println (str "📌 " (name label) " → " uid " opened in sidebar"))
      (println (str "   \"" text "\"")))))

(defn new-block!
  "Create a new block on a page (or current page) and focus it.
   opts: {:page \"title\", :order :first/:last}"
  ([] (new-block! {}))
  ([{:keys [page order] :or {order :first}}]
   (let [{:keys [graph state-uid]} (-bridge default-graph)
         order    (or order :first)
         page-uid (if (not-empty page)
                    (let [uid (get-page-uid graph page)]
                      (when-not uid
                        (throw (ex-info (str "Page \"" page "\" not found") {})))
                      uid)
                    (let [state (read-state graph state-uid)]
                      (or (get-in state [:main :uid])
                          (throw (ex-info "Cannot determine current page" {})))))]
     ;; Navigate to the page if a title was specified
     (when (not-empty page)
       (roam-api graph "ui.mainWindow.openPage" {"page" {"uid" page-uid}}))
     (create-and-focus-block! graph page-uid order (or page "current page")))))

(defn new-sibling!
  "Create a new sibling block in the current parent context.
   Falls back to current view root if no block is focused."
  ([] (new-sibling! {}))
  ([{:keys [order] :or {order :first}}]
   (let [{:keys [graph state-uid]} (-bridge default-graph)
         order  (or order :first)
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
                                "current page")))))

(defn new-child!
  "Create a new child block under a block targeted by label keyword or UID string.
   order: :first or :last"
  ([ref] (new-child! ref {}))
  ([ref {:keys [order] :or {order :first}}]
   (let [{:keys [graph state]} (ctx)
         order      (or order :first)
         label-uid  (resolve-uid state ref)
         parent-uid (or label-uid ref)
         label      (if label-uid
                      (str (str/upper-case ref) " → " parent-uid)
                      parent-uid)]
     (create-and-focus-block! graph parent-uid order label))))

(defn new-before!
  "Create a new block before a labeled block and focus it."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [parent (get-parent-uid graph uid)
          order  (get-block-order graph uid)]
      (when-not parent
        (throw (ex-info (str "Cannot find parent of block " (name label)) {})))
      (create-and-focus-block! graph parent order
                               (str "before " (name label))))))

(defn new-after!
  "Create a new block after a labeled block and focus it."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found")
                      {:available (when-let [lbls (:labels state)]
                                    (sort (map name (keys lbls))))})))
    (let [parent (get-parent-uid graph uid)
          order  (get-block-order graph uid)]
      (when-not parent
        (throw (ex-info (str "Cannot find parent of block " (name label)) {})))
      (create-and-focus-block! graph parent (inc order)
                               (str "after " (name label))))))

(defn move!
  "Move block(s) to target.
   source: {:labels [:A :B]} | {:source-uid \"uid\"} | {:selected true}
   target: {:label :D} | {:uid \"...\"} | {:page \"...\"} | {:before :B} | {:after :B}
   opts:   {:order :first/:last, :alias true}"
  ([source target] (move! source target {}))
  ([source target {:keys [order alias] :or {order :last}}]
   (let [{:keys [graph commands-uid state]} (ctx)
         src (if (:selected source)
               {:selected (or (:selected state)
                              (get-current-selection graph commands-uid))}
               source)]
     (if (:selected source)
       (do-move-selected! graph commands-uid state target order alias)
       (do-move! graph state src target order alias)))))

(defn link!
  "Create ((uid)) refs at target. Same source/target shapes as move!."
  ([source target] (link! source target {}))
  ([source target {:keys [order] :or {order :last}}]
   (let [{:keys [graph commands-uid state]} (ctx)
         src (if (:selected source)
               {:selected (or (:selected state)
                              (get-current-selection graph commands-uid))}
               source)]
     (do-link! graph state src target order))))

(defn transfer!
  "Unified move/link entry point for voice commands.
   Single map with source + target + options.

   Source (one required): :labels [:A :B], :source-uid \"uid\", :selected true
   Target (one required): :label :D, :uid \"uid\", :page \"title\"
   Options: :position :first/:last/:before/:after, :mode :link/:alias"
  [{:keys [labels source-uid selected label uid page position mode]}]
  (let [source (cond
                 labels     {:labels labels}
                 source-uid {:source-uid source-uid}
                 selected   {:selected true})
        target (cond
                 label {:label label}
                 uid   {:uid uid}
                 page  {:page page})
        ;; :before/:after positions rewrite the target key
        target (if (#{:before :after} position)
                  (let [[_k v] (first target)]
                    {position v})
                  target)
        order  (when (#{:first :last} position) position)
        opts   (cond-> {}
                  order (assoc :order order)
                  (= mode :alias) (assoc :alias true))]
    (if (= mode :link)
      (link! source target (select-keys opts [:order]))
      (move! source target opts))))

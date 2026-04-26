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
         '[babashka.fs :as fs]
         '[timing.core :as t]
         '[timing.adjusters :as adj])

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

(defn get-children-uids
  "Get direct children uids of a block, ordered by :block/order."
  [graph uid]
  (->> (roam-q graph
         (str "[:find ?cu ?o :where
                [?b :block/uid \"" (esc-dq uid) "\"]
                [?b :block/children ?c]
                [?c :block/uid ?cu]
                [?c :block/order ?o]]"))
       (sort-by second)
       (mapv first)))

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
  ;; Phase A: every command carries :version 1. See docs/COMMAND-SCHEMA.md §1.
  (roam-create-block graph commands-uid
    (json/generate-string {:version 1 :id cmd-id :type cmd-type :args args}))
  (loop [i 0]
    (when (>= i 60)
      (throw (ex-info "Bridge timeout" {:cmd-id cmd-id})))
    (Thread/sleep 50)
    ;; Query for response, also grab command block uid for cleanup
    (let [rows (roam-q graph
                 (str "[:find ?s ?cmd-uid :where
                        [?p :block/uid \"" (esc-dq commands-uid) "\"]
                        [?p :block/children ?cmd]
                        [?cmd :block/uid ?cmd-uid]
                        [?cmd :block/string ?cs]
                        [(clojure.string/includes? ?cs \"" (esc-dq cmd-id) "\")]
                        [?cmd :block/children ?r]
                        [?r :block/string ?s]]"))
          parsed (->> rows
                      (map (fn [[s cmd-uid]]
                             [(try (json/parse-string s true)
                                   (catch Exception _ nil))
                              cmd-uid]))
                      (filter (fn [[r _]] (= (:id r) cmd-id)))
                      first)
          [resp cmd-uid] parsed]
      (if resp
        (do
          ;; Clean up: delete the command block after getting response
          (future
            (try (roam-api graph "data.block.delete"
                           {"block" {"uid" cmd-uid}})
                 (catch Exception _)))
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

(defn resolve-label-region
  "Get the region (:main or :sidebar) for a label from state."
  [state label]
  (let [k (keyword (str/upper-case (name label)))
        v (get (:labels state) k)]
    (when (map? v) (:region v))))

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

(defn resolve-destination-legacy
  "Resolve a destination spec map to {:uid, :name, :order, :anchor-uid}.
   target: {:label :D}, {:uid \"...\"}, {:page \"...\"}, {:before :B}, {:after :B}
   default-order: :first or :last

   Phase B note: renamed from resolve-target to free that name for the new
   AST-based resolver in §2 of docs/COMMAND-SCHEMA.md. Will be removed in
   Phase D once moveToTarget/insertNewBlock take destination AST."
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
   If :parent is true, walks up one level from each resolved block.
   :parent alone (no other source key) uses the current focused block.
   Returns a vec of {:uid :label :text} maps."
  [graph state {:keys [labels selected source-uid parent]}]
  (let [base (cond
               labels     (:resolved (resolve-labels graph state labels))
               selected   (mapv (fn [uid] {:uid uid :label nil
                                           :text (or (get-block-string graph uid) "")})
                                selected)
               source-uid [{:uid source-uid :label nil
                            :text (or (get-block-string graph source-uid) "")}]
               ;; parent alone: start from focused block
               parent     (let [uid (get-in state [:focused :block-uid])]
                            (when-not uid
                              (throw (ex-info "No focused block for parent source" {})))
                            [{:uid uid :label nil
                              :text (or (get-block-string graph uid) "")}]))]
    (if parent
      (vec (keep (fn [{:keys [uid label]}]
                   (when-let [pu (get-parent-uid graph uid)]
                     {:uid pu :label label
                      :text (or (get-block-string graph pu) "")}))
                 base))
      (vec base))))

(defn do-move!
  "Unified move: resolve sources and target, then move.
   source: {:labels [:A :B]} or {:selected [...]} or {:source-uid \"abc\"}
   target-map, order, alias? as before."
  [graph state source target-map order alias?]
  (let [uids (resolve-source-uids graph state source)
        tgt  (resolve-destination-legacy graph state target-map order)]
    (when (seq uids)
      (move-uids! graph uids tgt alias?))))

(defn do-link!
  "Unified link: resolve sources and target, then create refs."
  [graph state source target-map order]
  (let [uids (resolve-source-uids graph state source)
        tgt  (resolve-destination-legacy graph state target-map order)]
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

(defn gc!
  "Delete all command blocks under __commands__ to clean up accumulated cruft."
  []
  (let [{:keys [graph commands-uid]} (-bridge default-graph)
        children (roam-q graph
                   (str "[:find ?uid :where
                          [?p :block/uid \"" (esc-dq commands-uid) "\"]
                          [?p :block/children ?c]
                          [?c :block/uid ?uid]]"))
        uids (mapv first children)]
    (doseq [uid uids]
      (roam-api graph "data.block.delete" {"block" {"uid" uid}}))
    (println (str "🧹 Deleted " (count uids) " command blocks"))))

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
             ;; Auto-detect sidebar from label region, or use explicit :sidebar opt
             in-sidebar? (or sidebar
                             (= "sidebar" (resolve-label-region state (first labels))))
             wid  (if in-sidebar?
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
        combined   (vec (distinct (concat current new-uids)))
        in-sidebar? (= "sidebar" (resolve-label-region state (first labels)))
        wid (if in-sidebar?
              (let [sw (find-sidebar-windows graph state (first new-uids))]
                (if (seq sw) (first sw) "main-window"))
              "main-window")]
    (when (seq new-uids)
      (send-command! graph commands-uid
        (str "sel-" (System/currentTimeMillis)) "select-block"
        {:uids combined :window_id wid :mode "focus"})
      (println (str "🎯 Added " (count new-uids) " → " (count combined) " total selected")))))

(defn select-remove!
  "Remove block(s) from current selection. labels: vector of keywords, e.g. [:A :B]"
  [labels]
  (let [{:keys [graph commands-uid state]} (ctx)
        current     (get-current-selection graph commands-uid)
        remove-uids (set (keep #(resolve-uid state %) labels))
        remaining   (vec (remove remove-uids current))
        in-sidebar? (= "sidebar" (resolve-label-region state (first labels)))
        wid (if in-sidebar?
              (let [uid (first remove-uids)
                    sw  (when uid (find-sidebar-windows graph state uid))]
                (if (seq sw) (first sw) "main-window"))
              "main-window")]
    (if (seq remaining)
      (do
        (send-command! graph commands-uid
          (str "sel-" (System/currentTimeMillis)) "select-block"
          {:uids remaining :window_id wid :mode "focus"})
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


(defn- ordinal-suffix [day]
  (let [d (mod day 100)]
    (cond
      (<= 11 d 13) "th"
      (= 1 (mod d 10)) "st"
      (= 2 (mod d 10)) "nd"
      (= 3 (mod d 10)) "rd"
      :else "th")))

(def ^:private month-names
  ["January" "February" "March" "April" "May" "June"
   "July" "August" "September" "October" "November" "December"])

(defn- today-value
  "Midnight ms-value for today."
  []
  (t/midnight (t/time->value (t/date))))

(defn- roam-daily-title
  "Convert a timing ms-value to Roam's daily note page title.
   e.g. 'April 3rd, 2026'"
  [value]
  (let [month-name (nth month-names (dec (t/month? value)))
        day        (t/day-in-month? value)
        year       (t/year? value)]
    (str month-name " " day (ordinal-suffix day) ", " year)))

(defn- parse-roam-daily-title
  "Parse a Roam daily page title to a timing ms-value, or nil if not a DNP."
  [title]
  (when-let [[_ month day year] (re-matches #"(\w+)\s+(\d+)\w{2},\s+(\d{4})" (str title))]
    (let [month-num (case month
                      "January" 1 "February" 2 "March" 3 "April" 4
                      "May" 5 "June" 6 "July" 7 "August" 8
                      "September" 9 "October" 10 "November" 11 "December" 12
                      nil)]
      (when month-num
        (t/time->value (t/date (Integer/parseInt year) month-num (Integer/parseInt day)))))))

;; Day-of-week: timing uses 1=Monday..7=Sunday (ISO 8601)
(def ^:private dow-map
  {:next-mon 1 :last-mon 1
   :next-tue 2 :last-tue 2
   :next-wed 3 :last-wed 3
   :next-thu 4 :last-thu 4
   :next-fri 5 :last-fri 5
   :next-sat 6 :last-sat 6
   :next-sun 7 :last-sun 7})

(defn- current-dnp-value
  "Get the timing ms-value of the currently viewed daily note page, or nil."
  []
  (let [{:keys [graph state-uid]} (-bridge default-graph)
        state (read-state graph state-uid)
        title (get-in state [:main :title])]
    (when title (parse-roam-daily-title title))))

(defn- resolve-daily-date
  "Resolve a daily value to a timing ms-value (midnight).
   Keywords:  :today :yesterday :tomorrow :next-day :prev-day
   Day of week: :next-mon .. :next-sun, :last-mon .. :last-sun
   Integer:   N days relative to current DNP (falls back to today)
   String:    'MM-DD-YYYY' date format"
  ([daily] (resolve-daily-date daily nil))
  ([daily base-date]
   (let [today (today-value)
         base  (or base-date (current-dnp-value) today)]
     (cond
       (= daily :today)     today
       (= daily :yesterday) (- today (t/days 1))
       (= daily :tomorrow)  (+ today (t/days 1))
       (= daily :next-day)  (+ base (t/days 1))
       (= daily :prev-day)  (- base (t/days 1))
       (and (keyword? daily) (str/starts-with? (name daily) "next-"))
       (adj/next-day-of-week today (get dow-map daily))
       (and (keyword? daily) (str/starts-with? (name daily) "last-"))
       (adj/previous-day-of-week today (get dow-map daily))
       (integer? daily) (+ base (t/days daily))
       (string? daily)  (let [[m d y] (str/split daily #"-")]
                          (t/time->value (t/date (Integer/parseInt y)
                                                 (Integer/parseInt m)
                                                 (Integer/parseInt d))))
       :else (throw (ex-info (str "Invalid :daily value: " daily) {}))))))

(defn zoom!
  "Zoom into a block or page.
   (zoom! :A)              — label shorthand
   (zoom! :label :A)       — by hat label
   (zoom! :uid \"abc\")    — by block UID
   (zoom! :page \"inbox\") — by page name
   (zoom! :daily :today)   — by daily note keyword
   (zoom! :daily 3)        — relative days from current DNP"
  ([target] (zoom! :label target))
  ([key val]
   (let [{:keys [graph state]} (ctx)]
     (case key
       :label (let [uid (resolve-uid state val)]
                (when-not uid
                  (throw (ex-info (str "Label " (name val) " not found") {})))
                (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" uid}})
                (println (str "🔎 " (name val) " → " uid)))
       :uid   (do (roam-api graph "ui.mainWindow.openBlock" {"block" {"uid" val}})
                  (println (str "🔎 → " val)))
       :page  (let [page-uid (get-page-uid graph val)]
                (when-not page-uid
                  (throw (ex-info (str "Page \"" val "\" not found") {})))
                (roam-api graph "ui.mainWindow.openPage" {"page" {"uid" page-uid}})
                (println (str "🔎 → page " val)))
       :daily (let [base (when (integer? val)
                           (let [state-uid (:state-uid (-bridge graph))
                                 cur-state (read-state graph state-uid)
                                 cur-title (get-in cur-state [:main :title])]
                             (when cur-title (parse-roam-daily-title cur-title))))
                    date  (resolve-daily-date val base)
                    title (roam-daily-title date)
                    title (roam-daily-title date)]
                (roam-api graph "ui.mainWindow.openPage" {"page" {"title" title}})
                (println (str "🔎 → " title)))))))

(comment
  (zoom! :daily :today))

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

(defn fold-children!
  "Fold (collapse) all children of a block by label."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found") {})))
    (let [children (get-children-uids graph uid)]
      (doseq [c children]
        (roam-set-block-open graph c false))
      (println (str "📁 Folded " (count children) " children of " (name label))))))

(defn unfold-children!
  "Unfold (expand) all children of a block by label."
  [label]
  (let [{:keys [graph state]} (ctx)
        uid (resolve-uid state label)]
    (when-not uid
      (throw (ex-info (str "Label " (name label) " not found") {})))
    (let [children (get-children-uids graph uid)]
      (doseq [c children]
        (roam-set-block-open graph c true))
      (println (str "📂 Unfolded " (count children) " children of " (name label))))))

(defn open-sidebar!
  "Open a block or page in the right sidebar.
   (open-sidebar! :A)              — label shorthand
   (open-sidebar! :label :A)       — by hat label
   (open-sidebar! :uid \"abc\")    — by block UID
   (open-sidebar! :page \"inbox\") — by page name
   (open-sidebar! :daily :today)   — by daily note"
  ([target] (open-sidebar! :label target))
  ([key val]
   (let [{:keys [graph state]} (ctx)
         page (if (= key :daily)
                (roam-daily-title (resolve-daily-date val))
                (when (= key :page) val))
         key  (if page :page key)
         val  (if page page val)
         resolved-uid (case key
                        :label (let [u (resolve-uid state val)]
                                 (when-not u (throw (ex-info (str "Label " (name val) " not found") {})))
                                 u)
                        :uid   val
                        :page  (let [pu (get-page-uid graph val)]
                                 (when-not pu (throw (ex-info (str "Page \"" val "\" not found") {})))
                                 pu))]
     (roam-api graph "ui.rightSidebar.addWindow"
               {"window" {"type" "outline" "block-uid" resolved-uid}})
     (println (str "📌 " (or (when (= key :label) (name val)) val) " opened in sidebar")))))

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

(defn- reorder-uids!
  "Move resolved source blocks to :first/:last within their current parent."
  [graph source-uids order]
  (let [order (name (or order :last))]
    (doseq [{:keys [label uid text]} source-uids]
      (let [parent (get-parent-uid graph uid)]
        (when-not parent
          (throw (ex-info (str "Cannot find parent of " (or label uid)) {})))
        (roam-move-block graph uid parent order)
        (println (str "   ✅ " (or (some-> label name) uid) " → " order))
        (println (str "      \"" text "\""))))))

(defn transfer!
  "Unified move/link/reorder entry point for voice commands.
   Single map with source + target + options.

   Source (one required): :labels [:A :B], :source-uid \"uid\", :selected true
   Target (optional):     :label :D, :uid \"uid\", :page \"title\"
     No target = reorder within current parent (requires :position :first/:last)
   Options: :position :first/:last/:before/:after
            :action :move (default), :link, :alias"
  [{:keys [labels source-uid selected label uid page daily position action parent]}]
  (let [;; Resolve :daily to a page title
        page (or page (when daily (roam-daily-title (resolve-daily-date daily))))
        source (cond-> (cond
                         labels     {:labels labels}
                         source-uid {:source-uid source-uid}
                         selected   {:selected true}
                         parent     {})
                 parent (assoc :parent true))
        has-target? (or label uid page)
        target (when has-target?
                 (cond
                   label {:label label}
                   uid   {:uid uid}
                   page  {:page page}))]
    (if has-target?
      ;; ── Move/link to target ────────────────────────────────────
      (let [;; :before/:after positions rewrite the target key
            target (if (#{:before :after} position)
                     (let [[_k v] (first target)]
                       {position v})
                     target)
            order  (when (#{:first :last} position) position)
            opts   (cond-> {}
                     order (assoc :order order)
                     (= action :alias) (assoc :alias true))]
        (if (= action :link)
          (link! source target (select-keys opts [:order]))
          (move! source target opts)))
      ;; ── No target: reorder within current parent ───────────────
      (let [{:keys [graph state]} (ctx)
            uids (resolve-source-uids graph state source)]
        (when-not (#{:first :last} position)
          (throw (ex-info "Reorder requires :position :first or :last" {:position position})))
        (reorder-uids! graph uids position)))))

(defn- ancestor-path
  "Return the chain of uids from descendant up to (but not including) ancestor.
   Returns nil if ancestor is not an ancestor of descendant."
  [graph ancestor-uid descendant-uid]
  (loop [cur descendant-uid path [] depth 0]
    (cond
      (nil? cur)             nil
      (= cur ancestor-uid)   path
      (>= depth 50)          nil
      :else (recur (get-parent-uid graph cur) (conj path cur) (inc depth)))))

(defn- swap-nested-positional!
  "Positional nested swap for direct parent→child.
   B bubbles up to A's spot, A sinks into B's old slot.
   A's other children move under B, B's old children move under A.

   Before: P > A > [X, B > [C, D], Y]
   After:  P > B > [X, A > [C, D], Y]"
  [graph anc-uid desc-uid anc-lbl desc-lbl]
  (let [anc-parent    (get-parent-uid graph anc-uid)
        anc-order     (get-block-order graph anc-uid)
        desc-order    (get-block-order graph desc-uid)
        anc-children  (get-children-uids graph anc-uid)
        desc-children (get-children-uids graph desc-uid)]
    ;; 1. Park B's children under A temporarily
    (doseq [c desc-children]
      (roam-move-block graph c anc-uid "last"))
    ;; 2. Move B to A's position (B lifts out with no children)
    (roam-move-block graph desc-uid anc-parent anc-order)
    ;; 3. Move A under B at B's old order
    (roam-move-block graph anc-uid desc-uid desc-order)
    ;; 4. Move A's original non-B children from A to B at their original orders
    (doseq [c anc-children]
      (when (not= c desc-uid)
        (roam-move-block graph c desc-uid (get-block-order graph c))))
    (println (str "🔄 Swapped (nested-positional) " (name anc-lbl) " ↔ " (name desc-lbl)))))

(defn- swap-nested-content!
  "Content nested swap — blocks keep their tree positions,
   strings and children are exchanged."
  [graph anc-uid desc-uid anc-lbl desc-lbl text-a text-b]
  (let [path-child    (last (ancestor-path graph anc-uid desc-uid))
        anc-children  (get-children-uids graph anc-uid)
        desc-children (get-children-uids graph desc-uid)]
    (roam-update-block graph anc-uid text-b)
    (roam-update-block graph desc-uid text-a)
    (doseq [c anc-children]
      (when (not= c path-child)
        (roam-move-block graph c desc-uid "last")))
    (doseq [c desc-children]
      (roam-move-block graph c anc-uid "last"))
    (println (str "🔄 Swapped (nested-content) " (name anc-lbl) " ↔ " (name desc-lbl)))
    (println "   strings + children exchanged")))

(defn swap-blocks!
  "Swap two blocks by label.

   Non-nested: swaps positions (each moves to the other's parent+order).
   Nested direct parent→child: positional swap — descendant bubbles up,
     ancestor sinks down. Use {:content true} for content-only swap.
   Nested deep: content swap (strings + children exchanged)."
  ([label-a label-b] (swap-blocks! label-a label-b {}))
  ([label-a label-b {:keys [content]}]
   (let [{:keys [graph state]} (ctx)
         uid-a (resolve-uid state label-a)
         uid-b (resolve-uid state label-b)]
     (when-not (and uid-a uid-b)
       (throw (ex-info (str "Label not found: "
                            (when-not uid-a (name label-a))
                            (when (and (not uid-a) (not uid-b)) ", ")
                            (when-not uid-b (name label-b))) {})))
     (let [text-a   (get-block-string graph uid-a)
           text-b   (get-block-string graph uid-b)
           path-a-b (ancestor-path graph uid-a uid-b)
           path-b-a (ancestor-path graph uid-b uid-a)
           nested?  (or path-a-b path-b-a)]
       (cond
         ;; ── Non-nested: swap positions ──────────────────────────
         (not nested?)
         (let [parent-a (get-parent-uid graph uid-a)
               parent-b (get-parent-uid graph uid-b)
               order-a  (get-block-order graph uid-a)
               order-b  (get-block-order graph uid-b)]
           (if (= parent-a parent-b)
             (let [[u1 o1 u2 o2] (if (< order-a order-b)
                                   [uid-a order-a uid-b order-b]
                                   [uid-b order-b uid-a order-a])]
               (roam-move-block graph u2 parent-a o1)
               (roam-move-block graph u1 parent-a o2))
             (do
               (roam-move-block graph uid-a parent-b order-b)
               (roam-move-block graph uid-b parent-a order-a)))
           (println (str "🔄 Swapped " (name label-a) " ↔ " (name label-b))))

         ;; ── Nested: content swap (explicit or deep nesting) ─────
         (or content
             ;; Deep nesting: more than 1 hop between them
             (> (count (or path-a-b path-b-a)) 1))
         (let [[anc desc al dl] (if path-a-b
                                  [uid-a uid-b label-a label-b]
                                  [uid-b uid-a label-b label-a])]
           (swap-nested-content! graph anc desc al dl text-a text-b))

         ;; ── Nested direct parent→child: positional swap ─────────
         :else
         (let [[anc desc al dl] (if path-a-b
                                  [uid-a uid-b label-a label-b]
                                  [uid-b uid-a label-b label-a])]
           (swap-nested-positional! graph anc desc al dl)))))))

(defn nudge!
  "Move a block in a relative direction within the outliner.
   label: keyword label, or nil to use focused/selected block.
   direction: :up, :down, :left-above, :left-below, :right, :right-below"
  ([direction] (nudge! nil direction))
  ([label direction]
   (let [{:keys [graph commands-uid state]} (ctx)
         uid (if label
               (let [u (resolve-uid state label)]
                 (when-not u
                   (throw (ex-info (str "Label " (name label) " not found") {})))
                 u)
               (or (get-in state [:focused :block-uid])
                   (first (get-current-selection graph commands-uid))
                   (throw (ex-info "No block focused or selected" {}))))
         block-name (or (some-> label name) uid)
         parent     (get-parent-uid graph uid)
         order      (get-block-order graph uid)
         siblings   (get-children-uids graph parent)
         idx        (.indexOf siblings uid)]
     (case direction
       :up         (if (> idx 0)
                     (do (roam-move-block graph uid parent (dec order))
                         (println (str "⬆ " block-name " moved up")))
                     ;; First child — cross branch: move to parent's prev sibling as last child
                     (let [grandparent (get-parent-uid graph parent)
                           parent-siblings (when grandparent (get-children-uids graph grandparent))
                           parent-idx (when parent-siblings (.indexOf parent-siblings parent))
                           prev-parent (when (and parent-idx (> parent-idx 0))
                                         (nth parent-siblings (dec parent-idx)))]
                       (if prev-parent
                         (do (roam-move-block graph uid prev-parent "last")
                             (println (str "⬆ " block-name " moved up (cross-branch)")))
                         (println (str "⚠️  " block-name " already first")))))
       :down       (if (< idx (dec (count siblings)))
                     (let [next-uid (nth siblings (inc idx))]
                       ;; Move next sibling to our position — pushes us down
                       (roam-move-block graph next-uid parent order)
                       (println (str "⬇ " block-name " moved down")))
                     ;; Last child — cross branch: move to parent's next sibling as first child
                     (let [grandparent (get-parent-uid graph parent)
                           parent-siblings (when grandparent (get-children-uids graph grandparent))
                           parent-idx (when parent-siblings (.indexOf parent-siblings parent))
                           next-parent (when (and parent-idx (< parent-idx (dec (count parent-siblings))))
                                         (nth parent-siblings (inc parent-idx)))]
                       (if next-parent
                         (do (roam-move-block graph uid next-parent 0)
                             (println (str "⬇ " block-name " moved down (cross-branch)")))
                         (println (str "⚠️  " block-name " already last")))))
       :left-above (let [gp (get-parent-uid graph parent)
                         po (get-block-order graph parent)]
                     (when-not gp (throw (ex-info "Already at top level" {})))
                     (roam-move-block graph uid gp po)
                     (println (str "⬅⬆ " block-name " outdented before parent")))
       :left-below (let [gp (get-parent-uid graph parent)
                         po (get-block-order graph parent)]
                     (when-not gp (throw (ex-info "Already at top level" {})))
                     (roam-move-block graph uid gp (inc po))
                     (println (str "⬅⬇ " block-name " outdented after parent")))
       :right      (if (> idx 0)
                     (let [prev (nth siblings (dec idx))]
                       (roam-move-block graph uid prev "last")
                       (println (str "➡ " block-name " indented under previous sibling")))
                     (println (str "⚠️  " block-name " no previous sibling")))
       :right-below (if (< idx (dec (count siblings)))
                      (let [nxt (nth siblings (inc idx))]
                        (roam-move-block graph uid nxt "first")
                        (println (str "➡⬇ " block-name " indented under next sibling")))
                      (println (str "⚠️  " block-name " no next sibling")))))))

;; ════════════════════════════════════════════════════════════════════════
;; ── Phase B+C: Composable resolver spine ────────────────────────────────
;; ════════════════════════════════════════════════════════════════════════
;; AST-based mark / modifier / target resolver per docs/COMMAND-SCHEMA.md.
;; Coexists with the legacy public API (select!, move!, etc.); existing
;; call sites are untouched. Dispatch implements only the setSelection
;; action — full action coverage lands in Phase D.
;;
;; Resolver ctx (single map threaded through every resolve-* / dispatch fn):
;;   {:graph         "tmem"            ;; current graph name
;;    :state         {…parsed __state__…}
;;    :pronouns      {:that {:uids […]} :source {:uids […]}}
;;    :commands-uid  "<uid>"           ;; for dispatch send-command!
;;    :state-uid     "<uid>"
;;    :destination?  false}            ;; gates the position modifier
;;
;; Region map shape: {:uid "..." :region "main"|"sidebar" :window-id "..."}
;; Only :uid is guaranteed; other keys are populated when the resolver
;; can derive them cheaply (e.g. label marks already carry :region).
;; ════════════════════════════════════════════════════════════════════════

(declare resolve-target)

(defn- err
  "Raise a tagged error. Code matches §9 of COMMAND-SCHEMA.md."
  [code data]
  (throw (ex-info code (assoc data :error code))))

;; ── Pronoun persistence (Phase C step 7) ────────────────────────────────
;; bb invocations are ephemeral; pronouns must survive across them so
;; voice flows like "select A" → "now move that to D" work. Backed by a
;; per-graph JSON file in the system tmpdir. The Phase G JS rewrite will
;; eventually mirror these into __state__.pronouns for the agent side.

(defn- pronouns-file [graph]
  (str (System/getProperty "java.io.tmpdir")
       "/roam-bridge-pronouns-" graph ".json"))

(defn- load-pronouns [graph]
  (let [f (pronouns-file graph)]
    (if (fs/exists? f)
      (try (json/parse-string (slurp f) true)
           (catch Exception _ {}))
      {})))

(defn- save-pronouns! [graph p]
  (spit (pronouns-file graph) (json/generate-string p)))

(def ^:private pronouns-cache
  "In-process cache to avoid re-reading the file per ctx call."
  (atom {}))

(defn- get-pronouns [graph]
  (or (get @pronouns-cache graph)
      (let [p (load-pronouns graph)]
        (swap! pronouns-cache assoc graph p)
        p)))

(defn- update-pronouns! [graph f]
  (let [updated (f (get-pronouns graph))]
    (swap! pronouns-cache assoc graph updated)
    (save-pronouns! graph updated)
    updated))

;; ── resolve-mark ─────────────────────────────────────────────────────────

(defmulti resolve-mark*
  "Resolve a mark AST node to a vec of region maps. Dispatch on :type (string).
   ctx: {:graph :state :pronouns} — only the keys a given mark needs."
  (fn [_ctx mark] (:type mark)))

(defmethod resolve-mark* "label"
  [{:keys [state]} {:keys [value]}]
  (let [k (keyword (str/upper-case (str value)))
        v (get-in state [:labels k])]
    (cond
      (nil? v) (err "mark-not-found"
                    {:mark {:type "label" :value value}
                     :available (some->> state :labels keys (map name) sort vec)})
      (map? v) [{:uid (:uid v) :region (:region v)}]
      :else    [{:uid v}])))

(defmethod resolve-mark* "uid"
  [_ctx {:keys [value]}]
  [{:uid value}])

(defmethod resolve-mark* "cursor"
  [{:keys [state]} _]
  (if-let [uid (get-in state [:focused :block-uid])]
    [{:uid uid :window-id (get-in state [:focused :window-id])}]
    (err "mark-not-found" {:mark {:type "cursor"}
                           :reason "no focused block"})))

(defmethod resolve-mark* "selection"
  [{:keys [state]} _]
  (mapv (fn [uid] {:uid uid}) (or (:selected state) [])))

(defmethod resolve-mark* "pageTitle"
  [{:keys [graph]} {:keys [value]}]
  (if-let [uid (get-page-uid graph value)]
    [{:uid uid :region "main" :page-title value}]
    (err "mark-not-found"
         {:mark {:type "pageTitle" :value value}
          :reason "page does not exist"})))

(defn- coerce-daily-value
  "JSON sends strings; coerce 'today'/'next-mon'/etc to keywords,
   bare integers to longs, MM-DD-YYYY strings stay as strings."
  [v]
  (cond
    (or (keyword? v) (integer? v)) v
    (and (string? v) (re-matches #"-?\d+" v)) (Long/parseLong v)
    (and (string? v) (re-matches #"\d{2}-\d{2}-\d{4}" v)) v
    (string? v) (keyword v)
    :else v))

(defmethod resolve-mark* "daily"
  [{:keys [graph]} {:keys [value]}]
  (let [coerced (coerce-daily-value value)
        date    (resolve-daily-date coerced)
        title   (roam-daily-title date)]
    (if-let [uid (get-page-uid graph title)]
      [{:uid uid :region "main" :page-title title :daily-value value}]
      ;; Daily page may not exist yet. Phase D's insertNewBlock can create
      ;; it on demand; for resolve-only marks we error with the title so
      ;; callers know what to do.
      (err "mark-not-found"
           {:mark {:type "daily" :value value}
            :resolved-title title
            :reason "daily note page does not exist yet"}))))

(defmethod resolve-mark* "that"
  [{:keys [pronouns]} _]
  (let [uids (get-in pronouns [:that :uids])]
    (if (seq uids)
      (mapv (fn [uid] {:uid uid}) uids)
      (err "mark-not-found"
           {:mark {:type "that"}
            :reason "no prior command result"}))))

(defmethod resolve-mark* "source"
  [{:keys [pronouns]} _]
  (let [uids (get-in pronouns [:source :uids])]
    (if (seq uids)
      (mapv (fn [uid] {:uid uid}) uids)
      (err "mark-not-found"
           {:mark {:type "source"}
            :reason "no prior move/link source"}))))

(defmethod resolve-mark* "phrase"
  [{:keys [graph]} {:keys [value]}]
  ;; Match is case-SENSITIVE — Roam's Datalog whitelist does not include
  ;; clojure.string/lower-case (only ::includes? and a few predicates).
  ;; If case-insensitive search becomes necessary, the fallback is a full
  ;; pull + client-side filter, which is O(n) over all block strings.
  (let [needle (str value)
        rows   (roam-q graph
                 (str "[:find ?uid
                        :where
                        [?b :block/string ?s]
                        [(clojure.string/includes? ?s \"" (esc-dq needle) "\")]
                        [?b :block/uid ?uid]]"))
        uids   (mapv first rows)]
    (if (seq uids)
      (mapv (fn [uid] {:uid uid}) uids)
      (err "mark-not-found"
           {:mark {:type "phrase" :value value}
            :reason "no blocks contain the phrase (case-sensitive)"}))))

(defmethod resolve-mark* "placeholder" [_ {:keys [index]}]
  (err "not-implemented" {:mark {:type "placeholder" :index index}
                          :phase "H — embedded DSL"}))

(defmethod resolve-mark* :default [_ mark]
  (err "unknown-mark" {:mark mark}))

(defn resolve-mark
  "Public wrapper. ctx is the resolver context map (see header comment)."
  [ctx mark]
  (resolve-mark* ctx mark))

;; ── apply-modifier ───────────────────────────────────────────────────────

(defn- ascend-n
  "Walk n parents up from uid. Returns nil if hitting top before n."
  [graph uid n]
  (loop [cur uid k n]
    (cond
      (nil? cur) nil
      (zero? k)  cur
      :else      (recur (get-parent-uid graph cur) (dec k)))))

(defn- collect-descendants
  "Pre-order descendant uids (excluding the root)."
  [graph uid]
  (let [cs (get-children-uids graph uid)]
    (vec (concat cs (mapcat #(collect-descendants graph %) cs)))))

(defn- ascend-to-page
  "Walk to the page (no-parent) ancestor."
  [graph uid]
  (loop [cur uid depth 0]
    (let [p (get-parent-uid graph cur)]
      (cond
        (nil? p)        cur
        (>= depth 50)   (err "mark-not-found"
                             {:reason "page walk too deep" :uid uid})
        :else           (recur p (inc depth))))))

(defn- ascend-to-top-level
  "Walk up to the depth-1 ancestor (parent is the page)."
  [graph uid]
  (loop [cur uid depth 0]
    (let [p (get-parent-uid graph cur)]
      (cond
        (nil? p)              cur ; cur is itself a page
        (nil? (get-parent-uid graph p)) cur ; parent is a page → cur is top-level
        (>= depth 50)         (err "mark-not-found"
                                   {:reason "topLevel walk too deep" :uid uid})
        :else                 (recur p (inc depth))))))

(defmulti apply-modifier
  "Apply a modifier to a region. ctx is {:graph :state :destination?}."
  (fn [_ctx _region modifier] (:type modifier)))

(defmethod apply-modifier "containing"
  [{:keys [graph]} region {:keys [scope ancestorIndex]}]
  (let [n (or ancestorIndex 1)]
    (case scope
      "parent"   (mapv (fn [{:keys [uid] :as r}]
                         (if-let [a (ascend-n graph uid n)]
                           (assoc r :uid a)
                           (err "mark-not-found"
                                {:modifier {:type "containing" :scope "parent"
                                            :ancestorIndex n}
                                 :uid uid})))
                       region)
      "page"     (mapv (fn [{:keys [uid] :as r}]
                         (assoc r :uid (ascend-to-page graph uid)))
                       region)
      "topLevel" (mapv (fn [{:keys [uid] :as r}]
                         (assoc r :uid (ascend-to-top-level graph uid)))
                       region)
      (err "unknown-scope" {:modifier {:type "containing" :scope scope}}))))

(defmethod apply-modifier "every"
  [{:keys [graph]} region {:keys [scope tag]}]
  (case scope
    "child"      (vec (mapcat (fn [{:keys [uid]}]
                                (map #(hash-map :uid %) (get-children-uids graph uid)))
                              region))
    "descendant" (vec (mapcat (fn [{:keys [uid]}]
                                (map #(hash-map :uid %) (collect-descendants graph uid)))
                              region))
    "sibling"    (vec (mapcat (fn [{:keys [uid]}]
                                (let [p (get-parent-uid graph uid)
                                      sibs (when p (get-children-uids graph p))]
                                  (->> sibs (remove #(= % uid))
                                       (map #(hash-map :uid %)))))
                              region))
    "reference"  (err "not-implemented"
                      {:modifier {:type "every" :scope "reference"} :phase "D"})
    "mention"    (err "not-implemented"
                      {:modifier {:type "every" :scope "mention" :tag tag} :phase "D"})
    (err "unknown-scope" {:modifier {:type "every" :scope scope}})))

(defn- pick-by-index
  "Negative index counts from end. Returns nil if out of bounds."
  [items idx]
  (let [n (count items)
        i (if (neg? idx) (+ n idx) idx)]
    (when (and (<= 0 i) (< i n))
      (nth items i))))

(defmethod apply-modifier "ordinal"
  [{:keys [graph]} region {:keys [scope index]}]
  (case scope
    "child"   (vec (keep (fn [{:keys [uid]}]
                           (when-let [c (pick-by-index (get-children-uids graph uid) index)]
                             {:uid c}))
                         region))
    "sibling" (vec (keep (fn [{:keys [uid]}]
                           (let [p (get-parent-uid graph uid)
                                 sibs (when p (get-children-uids graph p))]
                             (when-let [s (pick-by-index sibs index)]
                               {:uid s})))
                         region))
    (err "unknown-scope" {:modifier {:type "ordinal" :scope scope}})))

(defmethod apply-modifier "relative"
  [{:keys [graph]} region {:keys [scope direction count] :or {count 1}}]
  (case scope
    "sibling" (vec (mapcat (fn [{:keys [uid]}]
                             (let [p (get-parent-uid graph uid)
                                   sibs (when p (get-children-uids graph p))
                                   idx (when sibs (.indexOf sibs uid))
                                   step (if (= direction "backward") -1 1)]
                               (->> (range 1 (inc count))
                                    (keep (fn [k]
                                            (when (and sibs (>= idx 0))
                                              (let [j (+ idx (* step k))]
                                                (when (<= 0 j (dec (clojure.core/count sibs)))
                                                  {:uid (nth sibs j)})))))
                                    vec)))
                           region))
    (err "unknown-scope" {:modifier {:type "relative" :scope scope}})))

(defmethod apply-modifier "head"
  [{:keys [graph]} region {:keys [scope count]}]
  (case scope
    "child" (vec (mapcat (fn [{:keys [uid]}]
                           (->> (get-children-uids graph uid)
                                (take count)
                                (map #(hash-map :uid %))))
                         region))
    (err "unknown-scope" {:modifier {:type "head" :scope scope}})))

(defmethod apply-modifier "tail"
  [{:keys [graph]} region {:keys [scope count]}]
  (case scope
    "child" (vec (mapcat (fn [{:keys [uid]}]
                           (->> (get-children-uids graph uid)
                                (take-last count)
                                (map #(hash-map :uid %))))
                         region))
    (err "unknown-scope" {:modifier {:type "tail" :scope scope}})))

(defmethod apply-modifier "position"
  [{:keys [destination?]} region {:keys [at]}]
  (when-not destination?
    (err "position-on-target" {:modifier {:type "position" :at at}}))
  (when-not (#{"start" "end"} at)
    (err "unknown-scope" {:modifier {:type "position" :at at}}))
  (mapv #(assoc % :position at) region))

(defmethod apply-modifier :default [_ _ modifier]
  (err "unknown-modifier" {:modifier modifier}))

(defn- apply-modifiers [ctx region modifiers]
  (reduce (fn [r m] (apply-modifier ctx r m))
          region
          (or modifiers [])))

;; ── resolve-target ───────────────────────────────────────────────────────

(defn resolve-target
  "Resolve a target AST to a region vec.
   ctx is the resolver context: {:graph :state :pronouns :destination?}.
   Note: :destination? is consumed by apply-modifier (for the position
   modifier gate); resolve-target itself just threads ctx through."
  [{:keys [graph state] :as ctx} target]
  (case (:type target)
    "primitive" (apply-modifiers ctx
                                 (resolve-mark ctx (:mark target))
                                 (:modifiers target))
    "list"      (vec (mapcat #(resolve-target ctx %) (:elements target)))
    "range"     (let [{:keys [anchor active excludeAnchor excludeActive]} target
                      ;; Range endpoints resolve as targets, not destinations.
                      sub-ctx (assoc ctx :destination? false)
                      ar (resolve-target sub-ctx anchor)
                      br (resolve-target sub-ctx active)
                      a-uid (:uid (first ar))
                      b-uid (:uid (first br))
                      ap (get-parent-uid graph a-uid)
                      bp (get-parent-uid graph b-uid)]
                  (when (or (nil? a-uid) (nil? b-uid))
                    (err "mark-not-found"
                         {:reason "range endpoint resolved to nothing"}))
                  (when-not (= ap bp)
                    (err "range-cross-parent"
                         {:anchor a-uid :anchor-parent ap
                          :active b-uid :active-parent bp}))
                  (let [sibs (get-children-uids graph ap)
                        ai (.indexOf sibs a-uid)
                        bi (.indexOf sibs b-uid)
                        [lo hi] (sort [ai bi])
                        lo (if excludeAnchor (inc lo) lo)
                        hi (if excludeActive (dec hi) hi)]
                    (vec (->> sibs
                              (drop lo)
                              (take (inc (- hi lo)))
                              (map #(hash-map :uid %))))))
    "implicit"  (let [sel (or (:selected state) [])
                      focus (get-in state [:focused :block-uid])]
                  (cond
                    (seq sel) (mapv (fn [uid] {:uid uid}) sel)
                    focus     [{:uid focus
                                :window-id (get-in state [:focused :window-id])}]
                    :else     (err "mark-not-found"
                                   {:mark {:type "implicit"}
                                    :reason "no selection or cursor"})))
    (err "unknown-target-type" {:target target})))

;; ── dispatch + execute! ──────────────────────────────────────────────────

(defmulti dispatch
  "Dispatch a parsed action map. ctx is {:graph :commands-uid :state-uid :state}.
   Dispatch key is the action name (string)."
  (fn [name _action _ctx] name))

(defmethod dispatch "setSelection"
  [_ {:keys [target]} {:keys [graph commands-uid state] :as ctx}]
  (when-not target
    (err "missing-slot" {:action "setSelection" :slot "target"}))
  (let [region (resolve-target ctx target)
        uids   (mapv :uid region)
        in-sidebar? (some #(= "sidebar" (:region %)) region)
        wid    (if in-sidebar?
                 (let [sw (find-sidebar-windows graph state (first uids))]
                   (if (seq sw) (first sw) "main-window"))
                 "main-window")]
    (when (empty? uids)
      (err "missing-slot" {:action "setSelection"
                           :reason "target resolved to no uids"}))
    (send-command! graph commands-uid
      (str "ex-sel-" (System/currentTimeMillis))
      "select-block"
      {:uids uids :window_id wid :mode "focus"})
    {:uids uids :window_id wid :count (count uids)}))

(defmethod dispatch :default [name action _ctx]
  (err "unknown-action" {:name name :action action}))

;; ── Pronoun update hooks (Phase C step 7) ───────────────────────────────
;; Actions whose source slot should populate the :source pronoun.
(def ^:private source-slot-actions
  #{"moveToTarget" "linkToTarget" "aliasMove"})

(defn- update-pronouns-after!
  "After a successful dispatch, mirror the operated-on uids into pronouns.
   :that  ← uids the action acted on (returned in the dispatch result)
   :source ← source-slot uids when the action carries a source slot"
  [graph action result ctx]
  (let [now    (System/currentTimeMillis)
        uids   (some-> result :uids vec)
        src    (when (contains? source-slot-actions (:name action))
                 (when-let [s (:source action)]
                   (try (mapv :uid (resolve-target ctx s))
                        (catch Exception _ nil))))]
    (update-pronouns! graph
      (fn [p]
        (cond-> p
          uids (assoc :that {:uids uids :ts now :action (:name action)})
          src  (assoc :source {:uids src :ts now :action (:name action)}))))))

(defn execute!
  "Execute a v1 envelope payload. payload is a parsed map with :version, :id,
   :action keys. Returns the dispatch result map. Throws ex-info with
   :error code on schema violations (see §9 of docs/COMMAND-SCHEMA.md)."
  ([payload] (execute! payload {}))
  ([payload {:keys [graph] :or {graph default-graph}}]
   (let [{:keys [version action]} payload]
     (when-not (= version 1)
       (err "unknown-version" {:received version :supported [1]}))
     (when-not (and (map? action) (string? (:name action)))
       (err "missing-slot" {:reason "action.name (string) required"
                            :action action}))
     (let [{:keys [commands-uid state-uid]} (-bridge graph)
           state    (read-state graph state-uid)
           pronouns (get-pronouns graph)
           ctx      {:graph graph
                     :commands-uid commands-uid
                     :state-uid state-uid
                     :state state
                     :pronouns pronouns
                     :destination? false}
           result   (dispatch (:name action) action ctx)]
       (update-pronouns-after! graph action result ctx)
       result))))

(comment
  ;; Phase B smoke test — round-trip a setSelection envelope to label A.
  ;; Requires nav-mode to be on (run (hats-on!) first).
  (execute! {:version 1
             :id "phaseB-smoke"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "label" :value "A"}}}})

  ;; List with a modifier — select every child of A
  (execute! {:version 1
             :id "phaseB-modifier"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "label" :value "A"}
                               :modifiers [{:type "every" :scope "child"}]}}})

  ;; Phase C — pageTitle mark
  (execute! {:version 1 :id "phaseC-pageTitle"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "pageTitle" :value "roam-agent/bridge"}}}})

  ;; Phase C — daily mark (today)
  (execute! {:version 1 :id "phaseC-daily"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "daily" :value "today"}}}})

  ;; Phase C — phrase mark (fuzzy match)
  (execute! {:version 1 :id "phaseC-phrase"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "phrase" :value "agent-bridge"}}}})

  ;; Phase C — that pronoun (run after any other command)
  (execute! {:version 1 :id "phaseC-that"
             :action {:name "setSelection"
                      :target {:type "primitive"
                               :mark {:type "that"}}}}))

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

(defn load-token [graph]
  (let [path (str (fs/home) "/.roam-tools.json")]
    (when (fs/exists? path)
      (-> (slurp path) (json/parse-string true) :graphs
          (->> (filter #(= (:name %) graph)) first :token)))))

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

(defn roam-create-block [graph parent-uid text]
  (roam-api graph "data.block.create"
            {"location" {"parent-uid" parent-uid "order" "last"}
             "block"    {"string" text}}))

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
                          [?p :block/uid \"" state-uid "\"]
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
    (let [rows (roam-q graph
                 (str "[:find ?s :where
                        [?cmd :block/string ?cs]
                        [(clojure.string/includes? ?cs \"" cmd-id "\")]
                        [?cmd :block/children ?r]
                        [?r :block/string ?s]]"))
          resp (->> rows
                    (map (fn [[s]] (try (json/parse-string s true)
                                        (catch Exception _ nil))))
                    (filter #(= (:id %) cmd-id))
                    first)]
      (or resp (recur (inc i))))))

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

(defn label-uid [labels label-kw]
  (let [v (get labels label-kw)]
    (if (map? v) (:uid v) v)))

(defn label-region [labels label-kw]
  (let [v (get labels label-kw)]
    (if (map? v) (:region v) "main")))

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
            (str "[:find ?s :where [?b :block/uid \"" uid "\"] [?b :block/string ?s]]"))))

(defn act-on-label! [graph state label]
  (let [labels (:labels state)
        uid    (get labels (keyword (str/upper-case label)))]
    (if-not uid
      (do (println (str "⚠️  Label " (str/upper-case label) " not found."))
          (when labels
            (println (str "   Available: " (str/join ", " (sort (map name (keys labels))))))))
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
      (do (nav-on! graph commands-uid scope)
          ;; Re-read state after nav-mode writes labels
          (Thread/sleep 300)
          (read-state graph state-uid)))))

(defn resolve-uid [state label]
  (let [v (get (:labels state) (keyword (str/upper-case label)))]
    (if (map? v) (:uid v) v)))

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
                                                   [?b :block/uid \"" cur "\"]
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
  (let [labels (map str/trim (str/split (str/upper-case label-str) #","))
        resolved (keep (fn [lbl]
                         (when-let [uid (resolve-uid state lbl)]
                           {:label lbl :uid uid
                            :text (or (get-block-string graph uid) "")}))
                       labels)
        missing  (remove (fn [lbl] (some #(= lbl (:label %)) resolved)) labels)]

    (when (seq missing)
      (println (str "⚠️  Label(s) not found: " (str/join ", " missing)))
      (when-let [labels (:labels state)]
        (println (str "   Available: " (str/join ", " (sort (map name (keys labels))))))))

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

;; ── Main ─────────────────────────────────────────────────────────────

(def cli-spec
  {:graph   {:desc "Roam graph name"   :default "tmem"}
   :on      {:desc "Turn on nav-mode"  :coerce :boolean}
   :off     {:desc "Turn off nav-mode" :coerce :boolean}
   :labels  {:desc "Print current label map" :coerce :boolean}
   :label   {:desc "Act on block by label character"}
   :select  {:desc "Select (highlight) block by label character"}
   :e       {:desc "Edit mode: focus block text for typing" :coerce :boolean}
   :edit    {:desc "Edit mode: focus block text for typing" :coerce :boolean}
   :s       {:desc "Select in sidebar (optionally nth: -s 2)"}
   :sidebar {:desc "Select in sidebar (optionally nth: --sidebar 2)"}
   :scope   {:desc "Nav scope: main|sidebar|all" :default "all"}})

(let [opts    (cli/parse-opts *command-line-args* {:spec cli-spec})
      {:keys [graph on off labels label select scope]} opts
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

  (cond
    on     (nav-on! graph commands-uid scope)
    off    (nav-off! graph commands-uid)
    labels (print-labels (ensure-labels! graph commands-uid state-uid scope))
    select (select-block! graph commands-uid
                          (ensure-labels! graph commands-uid state-uid scope) select
                          {:sidebar sb :edit edit?})
    label  (act-on-label! graph (ensure-labels! graph commands-uid state-uid scope) label)
    :else  (do (println "Usage:")
               (println "  bb bridge --on              # turn on nav labels")
               (println "  bb bridge --off             # turn off nav labels")
               (println "  bb bridge --labels          # show label→uid map")
               (println "  bb bridge --select A        # highlight block A")
               (println "  bb bridge --select A,B,C    # highlight multiple blocks")
               (println "  bb bridge --select A -e     # focus block A for editing")
               (println "  bb bridge --select A -s     # highlight block A in sidebar")
               (println "  bb bridge --select A -s -e  # edit block A in sidebar")
               (println "  bb bridge --label A         # act on block A"))))

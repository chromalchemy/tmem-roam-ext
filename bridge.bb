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

(defn find-window-id
  "Determine the correct window-id for a block.
   Uses the label region; for sidebar blocks, finds the matching sidebar window."
  [graph state label-kw uid]
  (let [labels  (:labels state)
        region  (label-region labels label-kw)
        sidebar (:sidebar state)]
    (if (= region "sidebar")
      (or
        ;; Exact match: uid is the root of a sidebar window
        (some #(when (= (:block-uid %) uid) (:window-id %)) sidebar)
        ;; Child block: walk ancestors to find which sidebar window contains it
        (let [sw-uids (set (map :block-uid sidebar))
              sw-map  (into {} (map (juxt :block-uid :window-id) sidebar))]
          (loop [cur-uid uid depth 0]
            (when (and cur-uid (< depth 20))
              (let [parent-uid (ffirst
                                 (roam-q graph
                                   (str "[:find ?pu :where
                                          [?p :block/children ?b]
                                          [?b :block/uid \"" cur-uid "\"]
                                          [?p :block/uid ?pu]]")))]
                (cond
                  (nil? parent-uid) nil
                  (sw-uids parent-uid) (sw-map parent-uid)
                  :else (recur parent-uid (inc depth)))))))
        "main-window")
      "main-window")))

(defn select-block! [graph state label]
  (if-let [uid (resolve-uid state label)]
    (let [lk   (keyword (str/upper-case label))
          wid  (find-window-id graph state lk uid)
          text (or (get-block-string graph uid) "")]
      (roam-api graph "ui.setBlockFocusAndSelection"
                {"location" {"block-uid" uid "window-id" wid}})
      (println (str "🎯 " (str/upper-case label) " → " uid " selected"
                    (when (not= wid "main-window") (str " [" wid "]"))))
      (println (str "   \"" text "\"")))
    (let [labels (:labels state)]
      (println (str "⚠️  Label " (str/upper-case label) " not found."))
      (when (seq labels)
        (println (str "   Available: " (str/join ", " (sort (map name (keys labels))))))))))

;; ── Main ─────────────────────────────────────────────────────────────

(def cli-spec
  {:graph  {:desc "Roam graph name"   :default "tmem"}
   :on     {:desc "Turn on nav-mode"  :coerce :boolean}
   :off    {:desc "Turn off nav-mode" :coerce :boolean}
   :labels {:desc "Print current label map" :coerce :boolean}
   :label  {:desc "Act on block by label character"}
   :select {:desc "Select (focus) block by label character"}
   :scope  {:desc "Nav scope: main|sidebar|all" :default "all"}})

(let [{:keys [graph on off labels label select scope]}
      (cli/parse-opts *command-line-args* {:spec cli-spec})
      graph (or graph default-graph)
      {:keys [commands-uid state-uid]} (find-bridge-uids graph)]

  (when-not commands-uid
    (println "❌ Bridge not loaded.")
    (System/exit 1))

  (cond
    on     (nav-on! graph commands-uid scope)
    off    (nav-off! graph commands-uid)
    labels (print-labels (ensure-labels! graph commands-uid state-uid scope))
    select (select-block! graph (ensure-labels! graph commands-uid state-uid scope) select)
    label  (act-on-label! graph (ensure-labels! graph commands-uid state-uid scope) label)
    :else  (do (println "Usage:")
               (println "  bb bridge --on          # turn on nav labels")
               (println "  bb bridge --off         # turn off nav labels")
               (println "  bb bridge --labels      # show label→uid map")
               (println "  bb bridge --select A    # select (focus) block A")
               (println "  bb bridge --label A     # act on block A"))))

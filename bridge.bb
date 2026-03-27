#!/usr/bin/env bb

;; bridge.bb — Agent bridge client: scan, label, target, watch.
;;
;; Usage: bb bridge            # scan + target A + watch
;;        bb bridge --label C  # target label C
;;        bb bridge --no-watch # scan + target, then exit

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
      (-> (slurp path)
          (json/parse-string true)
          :graphs
          (->> (filter #(= (:name %) graph)) first :token)))))

;; ── Roam Local API client ────────────────────────────────────────────

(defn roam-api [graph action & args]
  (let [url     (str api-base "/" graph)
        token   (load-token graph)
        headers (cond-> {"Content-Type" "application/json"}
                  token (assoc "Authorization" (str "Bearer " token)))
        body    (json/generate-string {:action action :args (vec args)})
        resp    (http/post url {:headers headers :body body
                                :throw false})
        data    (json/parse-string (:body resp) true)]
    (when-not (:success data)
      (throw (ex-info (str "Roam API error: " (:error data))
                      {:action action :response data})))
    (:result data)))

(defn roam-q [graph query]
  (roam-api graph "data.q" query))

(defn roam-update-block [graph uid text]
  (roam-api graph "data.block.update"
            {"block" {"uid" uid "string" text}}))

(defn roam-create-block [graph parent-uid text]
  (roam-api graph "data.block.create"
            {"location" {"parent-uid" parent-uid "order" "last"}
             "block"    {"string" text}}))

(defn roam-delete-block [graph uid]
  (roam-api graph "data.block.delete"
            {"block" {"uid" uid}}))

;; ── Bridge primitives ────────────────────────────────────────────────

(defn find-bridge-uids
  "Returns {:commands-uid ... :state-uid ...} or nil."
  [graph]
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
  (let [rows (roam-q graph
               (str "[:find ?s :where
                      [?p :block/uid \"" state-uid "\"]
                      [?p :block/children ?c]
                      [?c :block/string ?s]]"))]
    (when-let [s (ffirst rows)]
      (json/parse-string s true))))

(defn send-command!
  "Send a bridge command, poll for response. Returns parsed response.
   Polls every 50ms, times out after 3s."
  [graph commands-uid cmd-id cmd-type args]
  (roam-create-block graph commands-uid
    (json/generate-string {:id cmd-id :type cmd-type :args args}))
  (loop [i 0]
    (when (>= i 60)
      (throw (ex-info "Bridge response timeout" {:cmd-id cmd-id})))
    (Thread/sleep 50)
    (let [rows (roam-q graph
                 (str "[:find ?s :where
                        [?cmd :block/string ?cs]
                        [(clojure.string/includes? ?cs \"" cmd-id "\")]
                        [?cmd :block/children ?r]
                        [?r :block/string ?s]]"))
          resp (->> rows
                    (map (fn [[s]] (try (json/parse-string s true) (catch Exception _ nil))))
                    (filter #(= (:id %) cmd-id))
                    first)]
      (or resp (recur (inc i))))))

(defn scan-blocks! [graph commands-uid & {:keys [scope] :or {scope "main"}}]
  (let [cmd-id (str "scan-" (System/currentTimeMillis))]
    (send-command! graph commands-uid cmd-id "scan-blocks"
                   {:scope scope :include_text true})))

(defn clear! [graph commands-uid]
  (let [cmd-id (str "clr-" (System/currentTimeMillis))]
    (send-command! graph commands-uid cmd-id "clear" {})))

(defn cleanup-commands! [graph commands-uid]
  (let [rows (roam-q graph
               (str "[:find ?uid :where
                      [?p :block/uid \"" commands-uid "\"]
                      [?p :block/children ?c]
                      [?c :block/uid ?uid]]"))]
    (doseq [[uid] rows]
      (roam-delete-block graph uid))))

;; ── Display ──────────────────────────────────────────────────────────

(defn print-mapping [mapping]
  (println "  Label │ UID         │ Text")
  (println "  ──────┼─────────────┼──────────────────────────────────────────")
  (doseq [{:keys [label uid text]} mapping]
    (printf "    %-3s │ %-11s │ %s%n"
            label uid (subs (or text "") 0 (min (count (or text "")) 60))))
  (println))

(defn resolve-label [mapping label]
  (->> mapping (filter #(= (:label %) (str/upper-case label))) first))

;; ── Main ─────────────────────────────────────────────────────────────

(defn -main [opts]
  (let [graph   (or (:graph opts) default-graph)
        label   (or (:label opts) "A")
        watch?  (not (:no-watch opts))
        {:keys [commands-uid state-uid]} (find-bridge-uids graph)]

    (when-not commands-uid
      (println "❌ Bridge not loaded. Is agent-bridge extension running?")
      (System/exit 1))

    (println (str "🔍 graph=" graph "  commands=" commands-uid "  target=" label))

    ;; ── 1. Scan ──────────────────────────────────────────────────────
    (let [t0      (System/currentTimeMillis)
          resp    (scan-blocks! graph commands-uid :scope "main")
          elapsed (- (System/currentTimeMillis) t0)
          mapping (:mapping (:result resp))]

      (println (str "📡 Scanned " (count mapping) " blocks in " elapsed "ms"))
      (print-mapping mapping)

      ;; ── 2. Target block by label ───────────────────────────────────
      (if-let [target (resolve-label mapping label)]
        (let [ts  (.format (java.time.LocalTime/now)
                           (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))
              new (str (:text target) " ✅ [" ts "]")]
          (println (str "🎯 " label " → " (:uid target) " → \"" (:text target) "\""))
          (roam-update-block graph (:uid target) new)
          (println (str "✏️  Updated → \"" new "\"")))
        (println (str "⚠️  Label " label " not in scan results")))

      ;; ── 3. Watch loop (re-scan on view change) ────────────────────
      (when watch?
        (println)
        (println "👁  Watching for view changes... (Ctrl-C to stop)")
        (let [last-view (atom (select-keys (read-state graph state-uid)
                                           [:main :sidebar]))]
          (loop []
            (Thread/sleep 1500)
            (let [state (read-state graph state-uid)
                  view  (select-keys state [:main :sidebar])]
              (when (not= view @last-view)
                (reset! last-view view)
                (let [t0      (System/currentTimeMillis)
                      resp    (scan-blocks! graph commands-uid :scope "main")
                      elapsed (- (System/currentTimeMillis) t0)
                      mapping (:mapping (:result resp))]
                  (println (str "🔄 View changed → re-scanned " (count mapping)
                                " blocks in " elapsed "ms"))
                  (print-mapping mapping))))
            (recur)))))))

;; ── Entry point ──────────────────────────────────────────────────────

(def cli-spec {:graph    {:desc "Roam graph name"   :default "tmem"}
               :label    {:desc "Target badge label" :default "A"}
               :no-watch {:desc "Exit after scan+target (no watch loop)"
                          :coerce :boolean}})

(-main (cli/parse-opts *command-line-args* {:spec cli-spec}))

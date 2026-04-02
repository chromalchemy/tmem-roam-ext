#!/usr/bin/env bb
;; probe.bb — send an eval command to agent-bridge and print the result

(require '[babashka.http-client :as http]
         '[cheshire.core :as json]
         '[babashka.fs :as fs]
         '[clojure.string :as str])

(def graph "tmem")
(def api-base "http://localhost:3333/api")

(defn load-token []
  (let [path (str (fs/home) "/.roam-tools.json")]
    (when (fs/exists? path)
      (-> (slurp path) (json/parse-string true) :graphs
          (->> (filter #(= (:name %) graph)) first :token)))))

(defn roam-api [action & args]
  (let [token   (load-token)
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

(defn roam-q [query] (roam-api "data.q" query))

(defn find-commands-uid []
  (ffirst (roam-q
    "[:find ?uid :where
      [?p :node/title \"roam-agent/bridge\"]
      [?p :block/children ?c]
      [?c :block/uid ?uid]
      [?c :block/string ?s]
      [(clojure.string/starts-with? ?s \"__commands__\")]]")))

(defn send-eval! [code]
  (let [cmd-uid (find-commands-uid)
        cmd-id  (str "probe-" (System/currentTimeMillis))
        cmd     (json/generate-string {:id cmd-id :type "eval" :args {:code code}})]
    (roam-api "data.block.create"
              {"location" {"parent-uid" cmd-uid "order" "last"}
               "block"    {"string" cmd}})
    (loop [i 0]
      (when (>= i 40) (throw (ex-info "Timeout" {})))
      (Thread/sleep 100)
      (let [rows (roam-q
                   (str "[:find ?s :where
                          [?cmd :block/string ?cs]
                          [(clojure.string/includes? ?cs \"" cmd-id "\")]
                          [?cmd :block/children ?r]
                          [?r :block/string ?s]]"))
            resp (->> rows
                      (map (fn [[s]] (try (json/parse-string s true) (catch Exception _ nil))))
                      (filter #(= (:id %) cmd-id))
                      first)]
        (or resp (recur (inc i)))))))

(let [code (first *command-line-args*)]
  (when-not code
    (println "Usage: bb probe.bb 'js code that returns a value'")
    (System/exit 1))
  (let [result (send-eval! code)]
    (println (json/generate-string result {:pretty true}))))

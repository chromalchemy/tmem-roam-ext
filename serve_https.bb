#!/usr/bin/env bb

;; Local HTTPS server for Roam extension development.
;; Uses http-kit (HTTP) + socat (TLS termination) with mkcert certs.
;; Usage: bb serve_https.bb [https-port]

(require '[babashka.process :as p]
         '[babashka.http-server :as server]
         '[babashka.fs :as fs])

(def https-port (or (some-> (first *command-line-args*) parse-long) 8889))
(def http-port  (+ https-port 100)) ;; internal, not exposed
(def certs-dir  ".certs")
(def cert-file  (str certs-dir "/localhost+2.pem"))
(def key-file   (str certs-dir "/localhost+2-key.pem"))

;; Preflight checks
(when-not (fs/exists? cert-file)
  (println "❌ Missing TLS certificate. Run:")
  (println "   mkdir -p .certs && cd .certs && mkcert localhost 127.0.0.1 ::1")
  (System/exit 1))

(when-not (fs/which "socat")
  (println "❌ socat not found. Install with: brew install socat")
  (System/exit 1))

;; Start HTTP backend (http-kit)
(server/serve {:port http-port :dir "."
               :headers {"Access-Control-Allow-Origin"  "*"
                         "Access-Control-Allow-Methods" "GET, OPTIONS"
                         "Access-Control-Allow-Headers" "*"
                         "Cache-Control"                "no-cache"}})

;; Start socat TLS proxy  
(def socat-cmd
  (format "socat OPENSSL-LISTEN:%d,reuseaddr,fork,cert=%s,key=%s,verify=0 TCP:127.0.0.1:%d"
          https-port cert-file key-file http-port))

(def socat-proc (p/process {:cmd (clojure.string/split socat-cmd #"\s+")
                            :inherit true}))

(println)
(println (str "🔒 Serving extension at https://localhost:" https-port "/"))
(println (str "   Set Roam developer extension URL to: https://localhost:" https-port "/extension.js"))
(println (str "   (HTTP backend on port " http-port ")"))
(println "   Press Ctrl-C to stop.")
(println)

;; Wait for socat to exit (or Ctrl-C)
(try
  @(p/process socat-proc)
  (catch Exception _
    (println "\nShutting down...")))

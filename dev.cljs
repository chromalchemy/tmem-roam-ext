(ns dev
  "Playground for REPL-driven Roam development.
   Open this file in your editor, connect to nREPL port 1339
   (REPL type: nbb), and evaluate forms with your editor keybinding.

   The roam.api namespace is auto-loaded by the extension.
   Use (require '[roam.api :as r]) to access it.")

;; ——————————————————————————————————————————
;; Quick smoke test — evaluate these one by one
;; ——————————————————————————————————————————

(comment

  ;; 1. Check we're in Roam
  (some? js/window.roamAlphaAPI)
  ;; => true

  ;; 2. Require the auto-loaded API wrapper
  (require '[roam.api :as r])

  ;; 3. Get today's daily note title
  (r/today-title)
  ;; => "March 27th, 2026"

  ;; 4. Query all page titles
  (take 10
    (map first
      (js->clj (r/q "[:find ?title :where [?e :node/title ?title]]"))))

  ;; 5. Pull a specific block
  (js->clj (r/pull "[*]" [:block/uid "some-uid-here"])
           :keywordize-keys true)

  ;; 6. Search for content
  (js->clj (r/search "meeting notes" :limit 5)
           :keywordize-keys true)

  ;; 7. Create a test block on today's daily note
  (r/create-block (r/page-uid (r/today-title))
                  "Hello from Scittle nREPL! 🎉"
                  :order "last")

  ;; 8. Get the focused block
  (r/focused-block)

  ;; 9. Open a page
  (r/open-page :title "roam/render")

  ;; 10. Raw JS interop — anything goes
  (.keys js/Object js/window.roamAlphaAPI)

  ;; 11. Direct Datalog — no wrapper needed
  (js/window.roamAlphaAPI.data.q
   "[:find ?uid ?s
     :where
     [?e :block/uid ?uid]
     [?e :block/string ?s]
     [(clojure.string/includes? ?s \"TODO\")]]")

  ;; 12. DOM manipulation (we're in the browser!)
  (js/document.title)

  ;; 13. Inspect Roam's React tree
  (.. js/document (querySelector ".roam-body-main") -innerHTML (substring 0 200))

  )

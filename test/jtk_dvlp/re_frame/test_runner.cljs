(ns jtk-dvlp.re-frame.test-runner
  "Entry point for the ClojureScript test run under node."
  (:require
   [cljs.test :refer-macros [run-tests]]

   [taoensso.timbre :as log]

   [jtk-dvlp.re-frame.async-coeffects-test]
   [jtk-dvlp.re-frame.async-coeffects.tasks-test]))

(defmethod cljs.test/report [:cljs.test/default :end-run-tests]
  [{:keys [fail error] :as summary}]
  (println "\n" (pr-str summary))
  ;; WATCHOUT: cljs.test does not set an exit code on its own. Without
  ;; this a red suite would still exit 0.
  (when (pos? (+ fail error))
    (set! (.-exitCode js/process) 1)))

(defn -main
  [& _]
  ;; NOTE: re-frame-tasks traces every step through timbre.
  (log/set-min-level! :warn)
  (run-tests
   'jtk-dvlp.re-frame.async-coeffects-test
   'jtk-dvlp.re-frame.async-coeffects.tasks-test))

;; WATCHOUT: The node target only calls `-main` when this is set. Without
;; it the bundle loads every namespace, runs nothing, and exits 0.
(set! *main-cli-fn* -main)

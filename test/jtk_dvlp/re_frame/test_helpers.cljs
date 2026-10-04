(ns jtk-dvlp.re-frame.test-helpers
  "Fixture, recorders and async helpers the acofx tests share."
  (:require
   [cljs.core.async :as core-async]
   [cljs.test :refer-macros [is]]

   [re-frame.core :as rf]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.re-frame.async-coeffects :as acofxs]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recorders

(def !handled
  "What the event handlers under test saw, in order."
  (atom []))

(def !failures
  "What the on-failure events under test got, in order."
  (atom []))

(def !event-errors
  "What event handlers under test threw, in order."
  (atom []))

(defn record-handled!
  [entry]
  (swap! !handled conj entry))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture

(def ^:private !restore-re-frame
  (atom nil))

(defn- record-event-error
  [original-error _re-frame-error]
  (swap! !event-errors conj original-error))

;; NOTE: re-frame's default rethrows, which ends the node process when it
;; happens in the async router. Registered once, before any snapshot of
;; the fixture, so every restore keeps it.
(rf/reg-event-error-handler record-event-error)

(def re-frame-fixture
  "Restores re-frame after each test, clears the recorders and registers
   `::failed` as on-failure event, recording its tag and the exception."
  {:before
   (fn []
     (reset! !restore-re-frame (rf/make-restore-fn))
     (reset! !handled [])
     (reset! !failures [])
     (reset! !event-errors [])
     (acofxs/set-global-on-failure-event nil)
     (rf/reg-event-fx ::failed
       (fn [_ [_ tag ex]]
         (swap! !failures conj [tag ex])
         {})))

   :after
   (fn []
     (acofxs/set-global-on-failure-event nil)
     (@!restore-re-frame))})


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Async

(def ^:private eventually-limit-ms
  "Upper bound to wait for an async outcome. Generous on purpose: the
   acofxs under test finish within a few ms."
  1000)

(def ^:private eventually-poll-ms
  5)

(defn <eventually
  "Yields true as soon as `pred` holds, false after
   `eventually-limit-ms`."
  [pred]
  (async/go-loop [waited-ms 0]
    (cond
      (pred) true
      (> waited-ms eventually-limit-ms) false
      :else (do
              (async/<! (core-async/timeout eventually-poll-ms))
              (recur (+ waited-ms eventually-poll-ms))))))

(defn <settle
  "Gives already dispatched events the time to run, to check that
   something did *not* happen."
  []
  (core-async/timeout 50))

(defn run-async
  "Ends the async test once the go block `?test` is done, also when it
   threw."
  [done ?test]
  (core-async/take!
   ?test
   (fn [result]
     (when (async/exception? result)
       (is (nil? result) "test body threw"))
     (done))))

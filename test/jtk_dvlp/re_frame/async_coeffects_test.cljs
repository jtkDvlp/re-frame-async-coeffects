(ns jtk-dvlp.re-frame.async-coeffects-test
  (:require
   [cljs.core.async :as core-async]
   [cljs.test :refer-macros [deftest is testing async use-fixtures]]

   [re-frame.core :as rf]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.re-frame.async-coeffects :as acofxs]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Fixture and helpers

(def ^:private !restore-re-frame
  (atom nil))

(def ^:private !handled
  "What the event handlers under test saw, in order."
  (atom []))

(def ^:private !failures
  "What the on-failure events under test got, in order."
  (atom []))

(def ^:private !acofx-calls
  (atom 0))

(defn- record-handled!
  [entry]
  (swap! !handled conj entry))

(use-fixtures :each
  {:before
   (fn []
     (reset! !restore-re-frame (rf/make-restore-fn))
     (reset! !handled [])
     (reset! !failures [])
     (reset! !acofx-calls 0)
     (acofxs/set-global-on-failure-event nil)
     (rf/reg-event-fx ::failed
       (fn [_ [_ tag ex]]
         (swap! !failures conj [tag ex])
         {})))

   :after
   (fn []
     (acofxs/set-global-on-failure-event nil)
     (@!restore-re-frame))})

(def ^:private eventually-limit-ms
  "Upper bound to wait for an async outcome. Generous on purpose: the
   acofxs under test finish within a few ms."
  1000)

(def ^:private eventually-poll-ms
  5)

(defn- <eventually
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

(defn- <settle
  "Gives already dispatched events the time to run, to check that
   something did *not* happen."
  []
  (core-async/timeout 50))

(defn- run-async
  "Ends the async test once the go block `?test` is done, also when it
   threw."
  [done ?test]
  (core-async/take!
   ?test
   (fn [result]
     (when (async/exception? result)
       (is (nil? result) "test body threw"))
     (done))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; acofx handlers under test

(defn- reg-test-acofxs!
  []
  (acofxs/reg-acofx ::value
    (fn [_coeffects value]
      (swap! !acofx-calls inc)
      (async/go
        (async/<! (core-async/timeout 1))
        value)))

  (acofxs/reg-acofx ::call-count
    (fn [_coeffects]
      (let [calls (swap! !acofx-calls inc)]
        (async/go calls))))

  (acofxs/reg-acofx ::failing
    (fn [_coeffects & [handler-on-failure]]
      (async/go
        (throw
        (ex-info "acofx under test failed"
                 (cond-> {:code ::boom}
                   handler-on-failure
                   (assoc ::acofxs/on-failure handler-on-failure))))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Injection

(deftest injects-a-single-acofx
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::value [42])]
          (fn [{::keys [value]} _]
            (record-handled! value)
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [42] @!handled))))))

(deftest injects-the-same-acofx-under-different-keys
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofxs
            [::value {:args [:a], :inject-key :a}]
            [::value {:args [:b], :inject-key :b}])]
          (fn [{:keys [a b]} _]
            (record-handled! [a b])
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [[:a :b]] @!handled))))))

(deftest runs-acofxs-concurrently
  ;; Each acofx only finishes once the other one has started. Run one
  ;; after the other, the first would wait for good and fail.
  (async done
    (run-async done
      (async/go
        (let [!started (atom #{})]
          (acofxs/reg-acofx ::rendezvous
            (fn [_coeffects key]
              (swap! !started conj key)
              (async/go
                (when-not (async/<! (<eventually
                                     (fn [] (= 2 (count @!started)))))
                  (throw (ex-info "the other acofx never started" {})))
                key)))

          (rf/reg-event-fx ::event
            [(acofxs/inject-acofxs
              [::rendezvous {:args [:a], :inject-key :a}]
              [::rendezvous {:args [:b], :inject-key :b
                             :on-failure [::failed :b]}])]
            (fn [{:keys [a b]} _]
              (record-handled! [a b])
              {}))

          (rf/dispatch [::event])
          (is (async/<! (<eventually #(seq @!handled))))
          (is (= [[:a :b]] @!handled))
          (is (empty? @!failures)))))))

(deftest runs-the-handler-once-with-the-original-event
  ;; The event is dispatched a second time internally. It has to be the
  ;; untouched original, or `trim-v` would trim it twice.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [rf/trim-v (acofxs/inject-acofx ::value [:v])]
          (fn [{::keys [value]} args]
            (record-handled! [value args])
            {}))

        (rf/dispatch [::event 1 2])
        (is (async/<! (<eventually #(seq @!handled))))
        (async/<! (<settle))
        (is (= [[:v [1 2]]] @!handled))
        (is (= 1 @!acofx-calls))))))

(deftest runs-acofxs-again-for-every-dispatch
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::call-count)]
          (fn [{::keys [call-count]} _]
            (record-handled! call-count)
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(= 1 (count @!handled)))))
        (rf/dispatch [::event])
        (is (async/<! (<eventually #(= 2 (count @!handled)))))
        (is (= [1 2] @!handled))))))

(defn- parked-results
  []
  @@#'acofxs/!results)

(deftest forgets-parked-results-once-the-handler-ran
  ;; Parked results are keyed by dispatch, so a later dispatch never sees
  ;; them. Left behind, they only pile up. `reg-event-db` is the case that
  ;; went missing once: its handler runs as `:db-handler`, not
  ;; `:fx-handler`.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-db ::db-event
          [(acofxs/inject-acofx ::call-count)]
          (fn [db _]
            (record-handled! :db-handler)
            db))

        (rf/reg-event-fx ::fx-event
          [(acofxs/inject-acofx ::call-count)]
          (fn [_ _]
            (record-handled! :fx-handler)
            {}))

        (rf/dispatch [::db-event])
        (rf/dispatch [::fx-event])
        (is (async/<! (<eventually #(= 2 (count @!handled)))))
        (async/<! (<settle))
        (is (empty? (parked-results)))))))

(deftest injects-from-several-interceptors
  ;; The inner interceptor aborts the run in which the outer one already
  ;; found its results. Removing them there would start the outer
  ;; acofxs again, and again, without end.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::value {:args [:outer], :inject-key :a})
           (acofxs/inject-acofx ::value {:args [:inner], :inject-key :b})]
          (fn [{:keys [a b]} _]
            (record-handled! [a b])
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!handled))))
        (async/<! (<settle))
        (is (= [[:outer :inner]] @!handled))
        (is (= 2 @!acofx-calls))))))

(deftest follows-the-event-across-both-runs
  ;; re-frame-tasks 2.x relies on the same `:dispatch-id` in both runs.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (let [!dispatch-ids (atom [])

              spy
              (rf/->interceptor
               :id ::spy
               :after
               (fn [context]
                 (swap! !dispatch-ids conj
                        (get-in context [:acoeffects :dispatch-id]))
                 context))]

          (rf/reg-event-fx ::event
            [spy (acofxs/inject-acofx ::value [:v])]
            (fn [_ _]
              (record-handled! :done)
              {}))

          (rf/dispatch [::event])
          (is (async/<! (<eventually #(seq @!handled))))
          (let [[first-run second-run :as ids] @!dispatch-ids]
            (is (= 2 (count ids)))
            (is (some? first-run))
            (is (= first-run second-run))))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Failure

(defn- <dispatch-failing-event!
  [inject-opts]
  (async/go
    (reg-test-acofxs!)
    (rf/reg-event-fx ::event
      [(acofxs/inject-acofx ::failing inject-opts)]
      (fn [_ _]
        (record-handled! :handler-ran)
        {}))

    (rf/dispatch [::event])
    (async/<! (<eventually #(seq @!failures)))
    (async/<! (<settle))))

(deftest dispatches-the-injections-on-failure
  (async done
    (run-async done
      (async/go
        (acofxs/set-global-on-failure-event [::failed :global])
        (async/<! (<dispatch-failing-event!
                   {:args [[::failed :handler]]
                    :on-failure [::failed :injection]}))

        (is (= [:injection] (map first @!failures)))
        (is (empty? @!handled) "handler ran despite the failure")
        (is (= ::boom (-> @!failures
                          (first)
                          (second)
                          (ex-cause)
                          (ex-data)
                          (:code))))))))

(deftest dispatches-the-handlers-on-failure-without-injections
  (async done
    (run-async done
      (async/go
        (acofxs/set-global-on-failure-event [::failed :global])
        (async/<! (<dispatch-failing-event!
                   {:args [[::failed :handler]]}))

        (is (= [:handler] (map first @!failures)))
        (is (empty? @!handled))))))

(deftest dispatches-the-global-on-failure-last
  ;; The global event is set after the interceptor was created -- as in
  ;; an app, which registers its events at load and configures later.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::failing)]
          (fn [_ _]
            (record-handled! :handler-ran)
            {}))

        (acofxs/set-global-on-failure-event [::failed :global])
        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!failures))))
        (is (= [:global] (map first @!failures)))
        (is (empty? @!handled))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; reg-acofx-by-fx

(defn- reg-fake-request-fx!
  "An effect shaped like `:http-xhrio`: it reports through the events
   under `:on-success` and `:on-failure`."
  []
  (rf/reg-fx ::fake-request
    (fn [{:keys [response error on-success on-failure]}]
      (if error
        (rf/dispatch (conj on-failure error))
        (rf/dispatch (conj on-success response))))))

(deftest injects-an-effect-result
  (async done
    (run-async done
      (async/go
        (reg-fake-request-fx!)
        (acofxs/reg-acofx-by-fx ::request
          {:fx-id ::fake-request
           :initial-args {:response :initial}
           :on-success-key :on-success
           :on-failure-key :on-failure})

        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::request {:inject-key :initial})
           (acofxs/inject-acofx ::request {:args [{:response :given}]
                                           :inject-key :given})]
          (fn [{:keys [initial given]} _]
            (record-handled! [initial given])
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [[:initial :given]] @!handled))))))

(deftest dispatches-the-registered-on-failure-of-an-effect
  (async done
    (run-async done
      (async/go
        (reg-fake-request-fx!)
        (acofxs/reg-acofx-by-fx ::request
          {:fx-id ::fake-request
           :on-success-key :on-success
           :on-failure-key :on-failure
           :on-failure-event [::failed :registered]})

        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::request [{:error {:status 500}}])]
          (fn [_ _]
            (record-handled! :handler-ran)
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!failures))))
        (let [[[tag ex]] @!failures]
          (is (= :registered tag))
          (is (= {:status 500} (-> ex (ex-cause) (ex-data) (:error)))))
        (is (empty? @!handled))))))

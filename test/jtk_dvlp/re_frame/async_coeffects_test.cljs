(ns jtk-dvlp.re-frame.async-coeffects-test
  (:require
   [cljs.core.async :as core-async]
   [cljs.test :refer-macros [deftest is testing async use-fixtures]]

   [re-frame.core :as rf]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.re-frame.async-coeffects :as acofxs]
   [jtk-dvlp.re-frame.test-helpers :as helpers
    :refer [record-handled! !handled !failures <eventually <settle
            run-async]]))


(def ^:private !acofx-calls
  (atom 0))

(use-fixtures :each
  helpers/re-frame-fixture
  {:before #(reset! !acofx-calls 0)})


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
                             :on-failure [::helpers/failed :b]}])]
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

(deftest forgets-parked-results-when-the-handler-throws
  ;; A throwing handler never reaches an `:after`. Results removed only
  ;; there stayed parked for good.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::value [:v])]
          (fn [_ _]
            (throw (ex-info "handler under test failed" {}))))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @helpers/!event-errors))))
        (is (empty? (parked-results)))))))

(deftest keeps-parked-results-across-foreign-aborts
  ;; Another interceptor after the injection may abort the run that
  ;; already got the results -- a `wait-for`, say -- and the event comes
  ;; back later. The results have to be there again, not the acofxs
  ;; started over.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (let [!aborted? (atom false)

              abort-once
              (rf/->interceptor
               :id ::abort-once
               :before
               (fn [context]
                 (if @!aborted?
                   context
                   (let [event (get-in context [:coeffects :original-event])]
                     (reset! !aborted? true)
                     (js/setTimeout #(rf/dispatch event) 10)
                     (update context :queue empty)))))]

          (rf/reg-event-fx ::event
            [(acofxs/inject-acofx ::value [:v]) abort-once]
            (fn [{::keys [value]} _]
              (record-handled! value)
              {}))

          (rf/dispatch [::event])
          (is (async/<! (<eventually #(seq @!handled))))
          (is (= [:v] @!handled))
          (is (= 1 @!acofx-calls) "acofxs started over after the abort")
          (is (empty? (parked-results))))))))

(deftest passes-args-to-plain-acofxs-as-given
  ;; Only `reg-acofx-by-fx` computes function args. Any other handler
  ;; gets a function as the value it is.
  (async done
    (run-async done
      (async/go
        (reg-test-acofxs!)
        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::value [inc])]
          (fn [{::keys [value]} _]
            (record-handled! value)
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [inc] @!handled))))))

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
        (acofxs/set-global-on-failure-event [::helpers/failed :global])
        (async/<! (<dispatch-failing-event!
                   {:args [[::helpers/failed :handler]]
                    :on-failure [::helpers/failed :injection]}))

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
        (acofxs/set-global-on-failure-event [::helpers/failed :global])
        (async/<! (<dispatch-failing-event!
                   {:args [[::helpers/failed :handler]]}))

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

        (acofxs/set-global-on-failure-event [::helpers/failed :global])
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
           :on-failure-event [::helpers/failed :registered]})

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

(deftest computes-the-initial-args-of-an-effect
  (async done
    (run-async done
      (async/go
        (reg-fake-request-fx!)
        (acofxs/reg-acofx-by-fx ::request
          {:fx-id ::fake-request
           :initial-args (fn [_coeffects [_ response]] {:response response})
           :on-success-key :on-success
           :on-failure-key :on-failure})

        (rf/reg-event-fx ::event
          [(acofxs/inject-acofx ::request)]
          (fn [{::keys [request]} _]
            (record-handled! request)
            {}))

        (rf/dispatch [::event :from-event])
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [:from-event] @!handled))))))

(deftest computes-the-injection-args-of-an-effect
  ;; A function given at injection gets the resolved initial args and
  ;; replaces them, so it can derive from the registered configuration --
  ;; down to dropping a key a merge could never remove.
  (async done
    (run-async done
      (async/go
        (reg-fake-request-fx!)
        (let [!initial-args-calls (atom 0)]
          (acofxs/reg-acofx-by-fx ::request
            {:fx-id ::fake-request
             :initial-args (fn [_coeffects [_ response]]
                             (swap! !initial-args-calls inc)
                             {:response response
                              :error {:status 500}})
             :on-success-key :on-success
             :on-failure-key :on-failure
             :on-failure-event [::helpers/failed :request]})

          (rf/reg-event-fx ::event
            [(acofxs/inject-acofx
              ::request
              [(fn [_coeffects [_ suffix] initial-args]
                 (-> initial-args
                     (dissoc :error)
                     (update :response vector suffix)))])]
            (fn [{::keys [request]} _]
              (record-handled! request)
              {}))

          (rf/dispatch [::event :from-event])
          (is (async/<! (<eventually #(seq @!handled))))
          (is (= [[:from-event :from-event]] @!handled))
          (is (empty? @!failures) "the dropped :error key came back")
          (is (= 1 @!initial-args-calls)
              "initial args computed more than once per injection"))))))

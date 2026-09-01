(ns jtk-dvlp.re-frame.async-coeffects
  (:require
   [cljs.core.async :as core-async]
   [re-frame.core :as rf]
   [re-frame.fx :as rf-fx]
   [re-frame.registrar :as rf-registrar]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.async.interop.promise :refer [promise-chan]]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registrar

(def kind :acofx)

(defn reg-acofx
  "TODO"
  [id handler]
  (rf-registrar/register-handler kind id handler))

(rf/reg-fx ::fill-acofx
  (fn [[chan data]]
    (when (some? data)
      (core-async/put! chan data))
    (core-async/close! chan)
    nil))

(rf/reg-event-fx ::resolve-acofx
  (fn [_ [_ result-chan data]]
    {::fill-acofx [result-chan data]}))

(rf/reg-event-fx ::reject-acofx
  (fn [_ [_ result-chan data]]
    ;; TODO: Was passiert mit dem on-failure?
    {::fill-acofx [result-chan on-failure data]}))

(defn reg-acofx-by-fx
  "TODO"
  [id {:keys [fx-id initial-args on-success-key on-failure-key on-failure-event]}]
  (reg-acofx id
    (fn [cofxs inject-args]
      (let [acofx
            (promise-chan)

            fx-hooks
            (cond-> {on-success-key [::resolve-acofx acofx]}
              on-failure-key
              (assoc on-failure-key
                [::reject-acofx acofx on-failure-event]))

            fx-args
            (merge initial-args inject-args fx-hooks)

            fx-handler
            (rf-registrar/get-handler rf-fx/kind fx-id true)]

        (fx-handler fx-args)
        acofx))))

(def ^:private !global-on-failure
  (atom nil))

(defn set-global-on-failure
  "TODO"
  [on-failure]
  (reset! !global-on-failure on-failure))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal Helpers
(rf/reg-fx ::put-on-chan
  (fn [[chan data]]
    (core-async/put! chan data)))

(rf/reg-event-fx ::acofx-by-fx-success
  (fn [_ [_ result data]]
    {::put-on-chan [result-chan data]}))

(defn- normalize-acofx
  [[id acofx]]
  (assoc acofx
    :id id
    :inject-key (:inject-key acofx id)
    :on-failure (:on-failure acofx @global-on-failure)))

(defn- <run-acofx!
  [{:keys [id args] :as acofx}]
  (async/go
    (try
      (let [<handler
            (rf-registrar/get-handler kind id true)

            result
            (async/<! (apply <handler args))]

        (assoc acofx :inject-value result))

      (catch ExceptionInfo e
        (ex-info
         "acofx handler failed"
         {:code :acofx-error
          :acofx acofx}
         e)))))

(defn- <run-acofxs!
  [acofxs]
  (async/go
    (->> acofxs
         (mapv <run-acofx!)
         (core-async/merge)
         (async/reduce conj [])
         (async/<!)
         (map (juxt :inject-key :inject-value))
         (into {}))))

(rf/reg-event-fx ::acofx-by-fx-error
  (fn [_ [_ result data]]
    ;; TODO: Was passiert mit dem on-failure?
    {::put-on-chan [result-chan on-failure data]}))


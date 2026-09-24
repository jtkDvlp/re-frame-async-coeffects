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

;; TODO: Logging ergänzen

(def kind :acofx)

(defn reg-acofx
  "TODO: docs
  Muss immer eine Promise-Channel liefern. Im Fehlerfall eine Exception über den Channel übermitteln. Siehe auch jtk-dvlp.async"
  [id handler]
  (rf-registrar/register-handler kind id handler))

;; TODO: analog zu tasks notieren für die auto-docs
(rf/reg-fx ::fill-fx-acofx
  (fn [[chan data]]
    (when (some? data)
      (core-async/put! chan data))
    (core-async/close! chan)
    nil))

(rf/reg-event-fx ::resolve-fx-acofx
  (fn [_ [_ result-chan data]]
    {::fill-fx-acofx [result-chan data]}))

(defn- fx-acofx-error?
  [ex]
  (and
   (instance? ExceptionInfo ex)
   (some-> ex (ex-data) (:code) (#{::fx-acofx-error}))))

(defn- ex->fx-acofx-on-failure
  [ex]
  (some-> ex (ex-data) (::on-failure)))

(rf/reg-event-fx ::reject-fx-acofx
  (fn [_ [_ result-chan on-failure data]]
    (let [exception
          (ex-info
           "fx-acofx handler failed"
           {:code ::fx-acofx-error
            ::on-failure on-failure}
           data)]

      {::fill-fx-acofx [result-chan exception]})))

(defn reg-acofx-by-fx
  "TODO: docs
   Besonderheit: Als Ergebnis wird immer das erste Argument des on-succes / on-failure übermittelt."
  [id {:keys [fx-id initial-args on-success-key on-failure-key on-failure-event]}]
  (reg-acofx id
    (fn [cofxs inject-args]
      (let [acofx
            (promise-chan)

            fx-hooks
            (cond-> {on-success-key [::resolve-fx-acofx acofx]}
              on-failure-key
              (assoc on-failure-key
                [::reject-fx-acofx acofx on-failure-event]))

            fx-args
            (merge initial-args inject-args fx-hooks)

            fx-handler
            (rf-registrar/get-handler rf-fx/kind fx-id true)]

        (fx-handler fx-args)
        acofx))))

(def ^:private !global-on-failure-event
  (atom nil))

(defn set-global-on-failure-event
  "TODO: docs"
  [on-failure]
  (reset! !global-on-failure-event on-failure))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal Helpers

(defn- normalize-acofx
  [[id acofx]]
  (assoc acofx
    :id id
    :inject-key (:inject-key acofx id)
    ;; WATCHOUT: Das nicht, weil zwischenzeitlich auf das vorhandensein geprüft wird.
    ;; :on-failure (:on-failure acofx @global-on-failure-event)
    ))

(defn- acofx-error?
  [ex]
  (and
   (instance? ExceptionInfo ex)
   (some-> ex (ex-data) (:code) (#{::acofx-error}))))

(defn- ex->acofx-on-failure
  [ex]
  (some-> ex (ex-data) (::on-failure)))

(defn- <run-acofx!
  [{:keys [id args] :as acofx}]
  (async/go
    (try
      (let [<handler
            (rf-registrar/get-handler kind id true)

            result
            (async/<! (apply <handler args))]

        (assoc acofx :inject-value result))

      (catch :default e
        (let [acofx
              (cond-> acofx
                (and
                 ;; TODO: Das ist sehr stark auf fx-acofx bezogen, geht das auch allgemein?!
                 (fx-acofx-error? e)
                 (nil? (:on-failure acofx)))
                (assoc :on-failure (ex->fx-acofx-on-failure e)))]

          (ex-info
           "acofx handler failed"
           {:code ::acofx-error
            ::acofx acofx}
           e))))))

(defn- <run-acofxs!
  [acofxs]
  ;; TODO: fehlerbehandlung und ergebnisbehandlung sollten auf einer ebene stehen, beides nicht in der funktion
  (async/go
    (try
      (try
        (let [result
              (->> acofxs
                   (mapv <run-acofx!)
                   (core-async/merge)
                   (async/reduce conj [])
                   (async/<!)
                   (map (juxt :inject-key :inject-value))
                   (into {}))])

        (catch ExceptionInfo ex
          (if-let [on-failure-event
                   (and
                    (acofx-error? ex)
                    (ex->acofx-on-failure ex))]
            (rf/dispatch (conj on-failure-event ex))
            (throw ex))))

      (catch :default e
        (if-let [on-failure-event @!global-on-failure-event]
          (rf/dispatch (conj on-failure-event e))
          (comment
            ;; TODO: log error
            ))))))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptor

(defn inject-acofxs
  "TODO: docs"
  [& acofxs]
  ;; TODO: acofx ausführen und in atom speichern, dann event erneut triggern und acofx ergebnisse aus atom ziehen und event durchlassen. atom erst bereinigen, wenn event abgeschlossen wurde.
  (rf/->interceptor
   :id :acoeffect

   ;; TODO: task handling berücksichtigen
   :before
   (fn [context]
     (let []))

   :after
   (fn [context])))

(defn inject-acofx
  "TODO: docs"
  {:arglists
   '([id]
     [id [:as args]]
     [id {:keys [args on-failure inject-key]}])}

  ([id]
   (inject-acofx id nil))

  ([id value]
   (let [acofx
         (cond
           (vector? value)
           {:args value}

           :else value)]

     (inject-acofxs [id acofx]))))

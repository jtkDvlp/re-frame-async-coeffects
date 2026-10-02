(ns jtk-dvlp.re-frame.async-coeffects
  "Async coeffects (acofx) for re-frame: input an event needs from the
   outside world that only arrives asynchronously -- a backend request, an
   IPC call, an async browser API.

   Register a handler with `reg-acofx`, or reuse an existing effect with
   `reg-acofx-by-fx`, and inject it into an event with `inject-acofx` or
   `inject-acofxs`. The event handler then runs once, with every acofx
   value in its coeffects, instead of a chain of load, success and
   further events."
  (:require
   [cljs.core.async :as core-async]

   [re-frame.core :as rf]
   [re-frame.fx :as rf-fx]
   [re-frame.loggers :as rf-loggers]
   [re-frame.registrar :as rf-registrar]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.async.interop.promise :refer [promise-chan]]))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registrar

(def kind
  "Registrar kind of acofx handlers, see `reg-acofx`."
  :acofx)

(defn reg-acofx
  "Registers `handler` as async coeffect (acofx) under `id`, for use with
   `inject-acofx` and `inject-acofxs`.

   `handler` is called with the event's `coeffects`, followed by the
   `:args` given at injection. It returns a promise channel carrying the
   value to inject. A failure travels as an exception over that channel,
   see `jtk-dvlp.async`.

   To name the event to dispatch on failure, put it under `::on-failure`
   into the `ex-data` of that exception. An `:on-failure` given at
   injection takes precedence."
  [id handler]
  (rf-registrar/register-handler kind id handler))

(def ^{:private true, :rf/reg-fx ::fill-fx-acofx} fill-fx-acofx-fx
  "re-frame effect to put `data` onto the acofx channel `chan` and close
   it. `nil` only closes, the acofx then injects `nil`."
  (rf/reg-fx ::fill-fx-acofx
    (fn [[chan data]]
      (when (some? data)
        (core-async/put! chan data))
      (core-async/close! chan)
      nil)))

(def ^{:private true, :rf/reg-event ::resolve-fx-acofx} resolve-fx-acofx-event
  "re-frame event hooked into an effect's success key by
   `reg-acofx-by-fx`."
  (rf/reg-event-fx ::resolve-fx-acofx
    (fn [_ [_ result-chan data]]
      {::fill-fx-acofx [result-chan data]})))

(def ^{:private true, :rf/reg-event ::reject-fx-acofx} reject-fx-acofx-event
  "re-frame event hooked into an effect's failure key by
   `reg-acofx-by-fx`. Turns what the effect reports into an exception,
   carrying the `on-failure` event given at registration."
  (rf/reg-event-fx ::reject-fx-acofx
    (fn [_ [_ result-chan on-failure data]]
      (let [exception
            (ex-info
             "fx-acofx handler failed"
             {:code ::fx-acofx-error
              :error data
              ::on-failure on-failure})]

        {::fill-fx-acofx [result-chan exception]}))))

(defn- resolve-initial-args
  [{:keys [event] :as coeffects} initial-args]
  (if (fn? initial-args)
    (initial-args coeffects event)
    initial-args))

(defn- resolve-fx-args
  "The effect's configuration for one injection: `inject-args` merged over
   the `initial-args`, or -- given as function -- whatever it makes of
   them."
  [{:keys [event] :as coeffects} initial-args inject-args]
  (let [initial-args (resolve-initial-args coeffects initial-args)]
    (if (fn? inject-args)
      (inject-args coeffects event initial-args)
      (merge initial-args inject-args))))

(defn- fx-acofx-handler
  [{:keys [fx-id initial-args on-success-key on-failure-key
           on-failure-event]}]
  (fn [coeffects & [inject-args]]
    (let [acofx
          (promise-chan)

          fx-hooks
          (cond-> {on-success-key [::resolve-fx-acofx acofx]}
            on-failure-key
            (assoc on-failure-key
              [::reject-fx-acofx acofx on-failure-event]))

          fx-args
          (-> coeffects
              (resolve-fx-args initial-args inject-args)
              (merge fx-hooks))

          fx-handler
          (rf-registrar/get-handler rf-fx/kind fx-id true)]

      (fx-handler fx-args)
      acofx)))

(defn reg-acofx-by-fx
  "Registers the effect `fx-id` as acofx under `id`, so an effect that
   reports its result through events (e.g. `:http-xhrio`) can be injected
   like any other acofx.

   - `initial-args` is the effect's base configuration: a map, or a
     function of the event's `coeffects` and the event that returns one.
   - `on-success-key` is the effect's key for the success event.
   - `on-failure-key` is the effect's key for the failure event
     (optional; without it a failure is never noticed).
   - `on-failure-event` is the event to dispatch on failure (optional),
     see `inject-acofxs` for the precedence.

   The first `:args` value given at injection configures the effect for
   that injection:

   - a map is merged over `initial-args`,
   - a function is called with the `coeffects`, the event and the
     resolved `initial-args`, and what it returns replaces them -- so it
     can derive from the registered configuration, down to removing
     keys.

   WATCHOUT: Only the first argument the effect hands to its success or
   failure event is taken as result -- that is what `:http-xhrio` and
   most effects use. The failure's argument ends up under `:error` in the
   `ex-data` of the exception."
  {:arglists
   '([id {:keys [fx-id initial-args on-success-key on-failure-key
                 on-failure-event]}])}
  [id options]
  (reg-acofx id (fx-acofx-handler options)))

(defonce ^:private !global-on-failure-event
  (atom nil))

(defn set-global-on-failure-event
  "Sets the event to dispatch when an acofx fails and neither the
   injection nor the handler names one, see `inject-acofxs`. `nil`
   removes it again."
  [on-failure]
  (reset! !global-on-failure-event on-failure))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running acofxs

(defn- normalize-acofx
  [[id acofx]]
  (assoc acofx
    :id id
    :inject-key (:inject-key acofx id)))

(defn- <run-acofx!
  [coeffects {:keys [id args] :as acofx}]
  (async/go
    (try
      (let [<handler
            (rf-registrar/get-handler kind id true)

            result
            (async/<! (apply <handler coeffects args))]

        (assoc acofx :inject-value result))

      (catch :default e
        (ex-info
         "acofx handler failed"
         {:code ::acofx-error
          ::acofx acofx}
         e)))))

(defn- <run-acofxs!
  "Runs all `acofxs` concurrently. Yields a map of inject key to value, or
   the first failure."
  [coeffects acofxs]
  (async/go
    (->> acofxs
         (mapv (partial <run-acofx! coeffects))
         (core-async/merge)
         (async/reduce conj [])
         (async/<!)
         (map (juxt :inject-key :inject-value))
         (into {}))))

(defn- on-failure-event
  "The event to dispatch for the acofx failure `ex`: the one given at
   injection, else the one the handler put into its exception, else the
   global one."
  [ex]
  (let [{:keys [on-failure]}
        (-> ex (ex-data) (::acofx))

        handler-on-failure
        (-> ex (ex-cause) (ex-data) (::on-failure))]

    ;; NOTE: The global event is read here, at failure time, and not
    ;; when the acofx is normalized. Injection happens when the event is
    ;; registered, usually at namespace load -- before an app gets to
    ;; call `set-global-on-failure-event`.
    (or on-failure handler-on-failure @!global-on-failure-event)))

(defn- dispatch-failure!
  [ex]
  (if-let [event (on-failure-event ex)]
    (rf/dispatch (conj event ex))
    (rf-loggers/console
     :error "re-frame-async-coeffects: acofx failed, no on-failure event"
     ex)))


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interceptor
;;
;; An event with acofxs runs more than once. A run that reaches an
;; injection without results starts its acofxs and is aborted before the
;; handler. Once all of them are done, the results are parked in
;; `!results` and the event is dispatched again, marked with a
;; `::dispatch-id`. Later runs find the parked results and inject them;
;; they are removed right before the event handler runs.
;;
;; WATCHOUT: `:acoeffects` in the context (with `:dispatch-id` and
;; `:?error`) and `::dispatch-id` in the event meta are read by
;; re-frame-tasks to follow an event across its runs. Renaming them
;; breaks that silently.

(defonce ^:private !results
  (atom {}))

(defn- dispatch-id
  [context]
  (-> context (:coeffects) (:original-event) (meta) (::dispatch-id)))

(defn- ensure-dispatch-id
  [context]
  (assoc-in context [:acoeffects :dispatch-id]
            (or (dispatch-id context) (random-uuid))))

(defn- parked-results
  [context]
  (let [dispatch-id (get-in context [:acoeffects :dispatch-id])]
    (get @!results dispatch-id {})))

(defn- cleanup-results
  [dispatch-id]
  (rf/->interceptor
   :id ::cleanup-results
   :before
   (fn [context]
     (swap! !results dissoc dispatch-id)
     context)))

(defn- insert-before-event-handler
  "Inserts `interceptor` before the last one in `queue`, the event handler.

   NOTE: The handler is last by construction -- re-frame puts global
   interceptors in front. And this runs from within the queue, so nothing
   has emptied it yet."
  [queue interceptor]
  (-> #queue []
      (into (butlast queue))
      (conj interceptor)
      (conj (last queue))))

(defn- ensure-results-cleanup
  "Makes the run remove the parked results right before its event
   handler, once per run.

   NOTE: Not earlier and not later. Any interceptor between here and
   the handler may still abort the run -- a second injection, a
   `wait-for` -- and the next run needs the results again. And a handler
   that throws never gets to an `:after`, so removing them there would
   leave them parked for good."
  [context]
  (if (get-in context [:acoeffects :cleanup-scheduled?])
    context
    (let [dispatch-id (get-in context [:acoeffects :dispatch-id])]
      (-> context
          (update :queue insert-before-event-handler
                  (cleanup-results dispatch-id))
          (assoc-in [:acoeffects :cleanup-scheduled?] true)))))

(defn- abort-event
  [context]
  (-> context
      (update :queue empty)
      ;; NOTE: This interceptor is already on the stack. Dropping it skips
      ;; its own `:after`; the `:after`s of the interceptors before it
      ;; still run, with no effects to act on.
      (update :stack rest)))

(defn- event-to-redispatch
  [context]
  (let [dispatch-id (get-in context [:acoeffects :dispatch-id])]
    (-> context
        (:coeffects)
        ;; NOTE: `:original-event`, not `:event`. Interceptors like
        ;; `trim-v` alter `:event`, and they run again on the next run.
        (:original-event)
        (vary-meta assoc ::dispatch-id dispatch-id))))

(defn- run-acofxs-and-redispatch!
  "Starts the `acofxs`, then parks their results under `inject-id` and
   dispatches the event again -- or dispatches the failure. Returns a
   promise channel that yields the failure or closes on success."
  [context inject-id acofxs]
  (let [dispatch-id
        (get-in context [:acoeffects :dispatch-id])

        event
        (event-to-redispatch context)

        ?results
        (<run-acofxs! (:coeffects context) acofxs)

        ?error
        (promise-chan)]

    (core-async/take!
     ?results
     (fn [acofx-results]
       (if (async/exception? acofx-results)
         (do
           (dispatch-failure! acofx-results)
           (core-async/put! ?error acofx-results))
         (do
           (swap! !results assoc-in [dispatch-id inject-id] acofx-results)
           (rf/dispatch event)
           (core-async/close! ?error)))))

    ?error))

(defn- start-acofxs
  [context inject-id acofxs]
  (-> context
      (assoc-in [:acoeffects :?error]
                (run-acofxs-and-redispatch! context inject-id acofxs))
      (abort-event)))

(defn- inject-results
  [context results]
  (-> context
      (update :coeffects merge results)
      (ensure-results-cleanup)))

(defn inject-acofxs
  "Returns an interceptor that injects the async coeffects `acofxs` into
   the event's coeffects. They run concurrently; the event handler runs
   once all of them are done.

   Each of `acofxs` is a vector `[id opts]` of an acofx registered with
   `reg-acofx` and an optional map of

   - `:args` -- vector of arguments to the acofx handler, after the
     coeffects, passed as given. What a function among them means is up
     to the handler; `reg-acofx-by-fx` computes one from the coeffects,
     see there.
   - `:inject-key` -- the key in the coeffects, defaults to `id`. Needed
     to inject the same acofx more than once.
   - `:on-failure` -- event to dispatch on failure, the exception
     appended.

   On failure the event handler does not run. The event dispatched is the
   injection's `:on-failure`, else the one the acofx handler named (see
   `reg-acofx`), else the global one (see `set-global-on-failure-event`).
   Without any, the failure is logged.

   The event runs twice -- once to start the acofxs, once with their
   values (once more per further `inject-acofxs` on the same event).
   Interceptors before this one see every run.

       (rf/reg-event-fx ::init-view
         [(inject-acofxs
           [::http {:args [{:uri \"/a\"}], :inject-key :a}]
           [::http {:args [{:uri \"/b\"}], :inject-key :b}])]
         (fn [{:keys [db a b]} _]
           {:db (assoc db ::a a, ::b b)}))"
  [& acofxs]
  (let [inject-id
        (random-uuid)

        acofxs
        (mapv normalize-acofx acofxs)]

    (rf/->interceptor
     :id ::inject-acofxs

     :before
     (fn [context]
       (let [context
             (ensure-dispatch-id context)

             parked
             (parked-results context)]

         (if (contains? parked inject-id)
           (inject-results context (get parked inject-id))
           (start-acofxs context inject-id acofxs)))))))

(defn inject-acofx
  "Returns an interceptor that injects the single async coeffect `id`.
   `value` is either a vector of `:args` or the map of options described
   in `inject-acofxs`."
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

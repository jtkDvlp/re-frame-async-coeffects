(ns jtk-dvlp.re-frame.async-coeffects
  (:require
   [cljs.core.async]
   [jtk-dvlp.async :refer [go <!] :as a]
   [jtk-dvlp.async.interop.promise :refer [promise-go promise-chan]]

   [re-frame.core :refer [dispatch reg-fx reg-event-fx]]
   [re-frame.registrar :refer [register-handler get-handler]]
   [re-frame.interceptor :refer [->interceptor]]
   [re-frame.fx :as fx]))


(def kind :acofx)

(defn reg-acofx
  "Register the given async-coeffect `handler` for the given `id`, for later use within `inject-acofx`:

    - `id` is keyword, often namespaced.
    - `handler` is a function which takes either one or more arguements, the first of which is always `coeffects` and which returns an updated `coeffects` as `cljs.core.async/chan`.

  See also: `inject-acofx`
  "
  [id handler]
  (register-handler kind id handler))

(reg-fx ::put-on-chan
  (fn [[chan data]]
    (cljs.core.async/put! chan data)))

(reg-event-fx ::acofx-by-fx-success
  (fn [_ [_ result data]]
    {::put-on-chan [result data]}))

(reg-event-fx ::acofx-by-fx-error
  (fn [_ [_ result data]]
    (let [data
          (cond->> data
            (not (a/exception? data))
            (ex-info "acofx error" {:code :acofx-error}))]
      {::put-on-chan [result data]})))

(defn reg-acofx-by-fx
  "Register the given effect `fx` as coeffect for the given `id`, for later use within `inject-acofx`:

    - `id` is keyword, often namespaced.
    - `fx` is the effect id to use as coeffect.
    - `on-success-key` is the key of `fx` to register a success event vector.
    - `on-error-key` is the key of `fx` to register a error event vector (optional).
    - `fx-map` is a predefined map to configure the fx as the fx supports

  See also: `inject-acofx`
  "
  [id fx on-success-key & [on-error-key fx-map]]
  (reg-acofx id
    (fn [coeffects & [fx-map' & rest-args]]
      (let [result-chan
            (promise-chan)

            hook-map
            (cond-> {on-success-key [::acofx-by-fx-success result-chan]}
              on-error-key
              (assoc on-error-key [::acofx-by-fx-error result-chan]))

            handler
            (get-handler fx/kind fx true)]

        (apply handler (merge fx-map fx-map' hook-map) rest-args)
        (a/map (partial assoc coeffects id) [result-chan])))))

(defonce ^:private !results
  (atom {}))

(def ^:private !global-error-dispatch
  (atom nil))

(def set-global-error-dispatch!
  "Sets global error dispatch."
  (partial reset! !global-error-dispatch))

(defn- fx-handler-run?
  [{:keys [stack]}]
  (->> stack
       (filter #(= :fx-handler (:id %)))
       (seq)))

(defn- compute-argument
  [{:keys [event] :as coeffects} arg-or-fn]
  (if (fn? arg-or-fn)
    (arg-or-fn coeffects event)
    arg-or-fn))

(defn- run-acofx!
  [coeffects {:keys [id data-id handler args]}]
  (go
    (let [result
          (->> args
               (mapv (partial compute-argument coeffects))
               (apply handler coeffects)
               (<!)
               (#(get % id)))]

      {data-id result})))

(defn- run-acofxs!
  [{:keys [acoeffects coeffects] :as context}
   {:keys [error-dispatch acofxs] inject-id :id}]

  (let [error-dispatch
        (or error-dispatch @!global-error-dispatch)

        dispatch-id
        (:dispatch-id acoeffects)

        event
        (-> coeffects
            (:event)
            (vary-meta assoc ::dispatch-id dispatch-id))

        ?acofx
        (promise-go
         (try
           (->> acofxs
                (map (partial run-acofx! coeffects))
                (cljs.core.async/merge)
                (a/reduce merge {})
                (<!)
                (swap! !results assoc-in [dispatch-id inject-id]))

           (dispatch event)

           (catch ExceptionInfo e
             (when error-dispatch
               (->> e
                    (conj error-dispatch)
                    (dispatch)))
             (swap! !results dissoc dispatch-id)
             (throw e))))]

    (assoc-in context [:acoeffects :?error] ?acofx)))

(defn- abort-original-event
  [context]
  (-> context
      (update :queue empty)
      (update :stack rest)))

(defn- run-acofxs-n-abort-event!
  [context acofxs-n-options]
  (-> context
      (run-acofxs! acofxs-n-options)
      (abort-original-event)))

(defn- normalize-acofx
  [cofx]
  (cond
    (keyword? cofx)
    {:id cofx
     :handler (get-handler kind cofx true)
     :args-fn (constantly nil)}

    (and (vector? cofx))
    {:id (first cofx)
     :handler (get-handler kind (first cofx) true)
     :args (next cofx)}

    :else cofx))

(defn- normalize-acofxs
  [acofxs]
  (if (map? acofxs)
    (for [[data-id cofx] acofxs]
      (-> cofx
          (normalize-acofx)
          (assoc :data-id data-id)))
    (for [cofx acofxs
          :let [{:keys [id] :as cofx}
                (normalize-acofx cofx)]]
      (assoc cofx :data-id id))))

(defn inject-acofxs
  "Give as much acofxs as you want as vector or map to compute async (pseudo parallel) values. Use map keys to rename keys within event coeffects map. For further reading see `inject-acofx`."
  ([acofxs]
   (inject-acofxs acofxs nil))

  ([acofxs {:keys [error-dispatch] :as opts}]

   (let [inject-id
         (random-uuid)

         acofxs-n-options
         (->> acofxs
              (normalize-acofxs)
              (assoc opts :id inject-id, :acofxs))]

     (->interceptor
      :id
      :acoeffects

      :before
      (fn [context]
        (let [dispatch-id
              (or (some-> context (:coeffects) (:event) (meta) (::dispatch-id))
                  (random-uuid))

              context
              (assoc-in context [:acoeffects :dispatch-id] dispatch-id)]

          (if-let [result (get-in @!results [dispatch-id inject-id])]
            (update context :coeffects merge result)
            (run-acofxs-n-abort-event! context acofxs-n-options))))

      :after
      (fn [context]
        (when (fx-handler-run? context)
          (->> context
               (:acoeffects)
               (:dispatch-id)
               (swap! !results dissoc)))
        context)))))

(defn inject-acofx
  "Given an async-coeffect (acofx) returns an interceptor whose `:before` adds to the `:coeffects` (map) by calling a pre-registered 'async coeffect handler' identified by `id`.

  As first argument give the `id` of the acofx or an vector of `id` and `args`. `args` can be mixed of any val and fns. fns will be applied with coeffects and event. Computed args prepended with coeffects will be applied to acofx handler.

  As second optional argument give a map of options to carry a `:error-dispatch` vector, see also `set-global-error-dispatch`. `error-dispatch` will be called on error, event will be aborted.

  The previous association of a `async coeffect handler` with an `id` will have happened via a call to `reg-acofx` - generally on program startup. See also `reg-acofx-by-fx` to reuse effects as coeffects.

  Within the created interceptor, this 'looked up' `async coeffect handler` will be called (within the `:before`) with arguments:

  - the current value of `:coeffects`
  - optionally, the given or computed args by `args`

  This `coeffect handler` is expected to modify and return its first, `coeffects` argument.

  **Example of `inject-acofx` and `reg-acofx` working together**


  First - Early in app startup, you register a `async coeffect handler` for `:async-now`:

      #!clj
      (reg-acofx
        :async-now                        ;; usage  (inject-acofx :async-now)
        (fn async-coeffect-handler
          [coeffect]
          (go
            (<! (timeout 1000))
            (assoc coeffect :async-now (js/Date.)))))   ;; modify and return first arg

  Second - Later, add an interceptor to an -fx event handler, using `inject-acofx`:

      #!clj
      (re-frame.core/reg-event-fx            ;; when registering an event handler
        :event-id
        [ ... (inject-acofx :async-now) ... ]  ;; <-- create an injecting interceptor
        (fn event-handler
          [coeffect event]
            ;;... in here can access (:async-now coeffect) to obtain current async-now ...
          )))

  **Background**

  `coeffects` are the input resources required by an event handler to perform its job. The two most obvious ones are `db` and `event`. But sometimes an event handler might need other resources maybe async resources.

  Perhaps an event handler needs data from backend or some other async api.

  If an event handler directly accesses these resources, it stops being pure and, consequently, it becomes harder to test, etc. So we don't want that.

  Instead, the interceptor created by this function is a way to 'inject' 'necessary resources' into the `:coeffects` (map) subsequently given to the event handler at call time.

  See also `reg-acofx`
  "
  ([acofx]
   (inject-acofx acofx nil))

  ([acofx {:keys [error-dispatch] :as opts}]
   (inject-acofxs [acofx] opts)))

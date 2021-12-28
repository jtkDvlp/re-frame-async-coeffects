(ns jtk-dvlp.re-frame.async-coeffects
  (:require
   [cljs.core.async]
   [jtk-dvlp.async :refer [<!] :as a]
   [jtk-dvlp.async.interop.promise :refer [promise-go]]
   [re-frame.core :refer [dispatch]]
   [re-frame.registrar :refer [register-handler get-handler]]
   [re-frame.interceptor  :refer [->interceptor]]))


(def kind :acofx)

(defn reg-acofx
  "Register the given async-coeffect `handler` for the given `id`, for later use
  within `inject-acofx`:

    - `id` is keyword, often namespaced.
    - `handler` is a function which takes either one or more arguements, the first of which is
       always `coeffects` and which returns an updated `coeffects` as `cljs.core.async/promise-chan`.

  See also: `inject-acofx`
  "
  [id handler]
  (register-handler kind id handler))

(defonce ^:private !results
  (atom {}))

(defn- fx-handler-run?
  [{:keys [stack]}]
  (->> stack
       (filter #(= :fx-handler (:id %)))
       (seq)))

(defn- run-acofx!
  [coeffects {:keys [handler args-fn]}]
  (->> coeffects
       (args-fn)
       (apply handler coeffects)))

(defn- run-acofxs!
  [{:keys [coeffects] :as context}
   {:keys [error-dispatch acofxs]}]

  (let [{:keys [original-event]}
        coeffects

        acofxs
        (promise-go
         (try
           (->> acofxs
                (map (partial run-acofx! coeffects))
                (a/map merge)
                (<!)
                (swap! !results assoc [original-event acofxs]))

           (dispatch original-event)

           (catch ExceptionInfo e
             (when error-dispatch
               (->> e
                    (conj error-dispatch)
                    (dispatch)))
             (throw e))))]

    (update context :acoeffects (fnil assoc {}) [original-event acofxs] acofxs)))

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

(defn- normalize-acofxs
  [acofxs]
  (for [cofx acofxs]
    (cond
      (keyword? cofx)
      {:id cofx
       :handler (get-handler kind cofx)
       :args-fn (constantly nil)}

      (and (vector? cofx) (fn? (second cofx)))
      {:id (first cofx)
       :handler (get-handler kind (first cofx))
       :args-fn #(apply (second cofx) % (nnext cofx))}

      :else
      {:id (first cofx)
       :handler (get-handler kind (first cofx))
       :args-fn (constantly (next cofx))})))

(defn inject-acofx
  "Given async-coeffects (acofxs) returns an interceptor whose `:before` adds to the `:coeffects` (map) by calling a pre-registered 'async coeffect handler' identified by `id`.

  Give as much acofxs as you want to compute async (pseudo parallel) values via `id` of the acofx or an vector of `id` and a seq of `args` or `id`, `args-fn` and `args`. `args` will be applied to acofx handler unless give `args-fn`, then `args` will be applied to `args-fn`. Result of `args-fn` will be applied to acofx-handler. Both acofx-handler and `args-fn` first argument will be coeffects map.

  Give a map instead of multiple acofxs like `{:acofxs [...]}` to carry a `:error-dispatch` vector. `error-dispatch` will be called on error of any acofxs, event will be aborted.

  The previous association of a `async coeffect handler` with an `id` will have happened via a call to `reg-acofx` - generally on program startup.

  Within the created interceptor, this 'looked up' `async coeffect handler` will be called (within the `:before`) with arguments:

  - the current value of `:coeffects`
  - optionally, the given or computed args by `args` or `args-fn`

  This `coeffect handler` is expected to modify and return its first, `coeffects` argument.

  **Example of `inject-acofx` and `reg-acofx` working together**


  First - Early in app startup, you register a `async coeffect handler` for `:async-datetime`:

      #!clj
      (reg-acofx
        :async-datetime                        ;; usage  (inject-acofx :async-datetime)
        (fn async-coeffect-handler
          [coeffect]
          (go
            (<! (timeout 1000))
            (assoc coeffect :async-now (js/Date.)))))   ;; modify and return first arg

  Second - Later, add an interceptor to an -fx event handler, using `inject-acofx`:

      #!clj
      (re-frame.core/reg-event-fx            ;; when registering an event handler
        :event-id
        [ ... (inject-acofx :async-datetime) ... ]  ;; <-- create an injecting interceptor
        (fn event-handler
          [coeffect event]
            ;;... in here can access (:async-now coeffect) to obtain current async-datetime ...
          )))

  **Background**

  `coeffects` are the input resources required by an event handler to perform its job. The two most obvious ones are `db` and `event`. But sometimes an event handler might need other resources maybe async resources.

  Perhaps an event handler needs data from backend or some other async call.

  If an event handler directly accesses these resources, it stops being pure and, consequently, it becomes harder to test, etc. So we don't want that.

  Instead, the interceptor created by this function is a way to 'inject' 'necessary resources' into the `:coeffects` (map) subsequently given to the event handler at call time.

  See also `reg-acofx`
  "
  ([& [map-or-acofx :as acofxs]]

   (let [{:keys [acofxs] :as acofxs-n-options}
         (if (map? map-or-acofx)
           (update map-or-acofx :acofxs normalize-acofxs)
           {:acofxs (normalize-acofxs acofxs)})]

     (->interceptor
      :id
      :acoeffects

      :before
      (fn [{:keys [coeffects] :as context}]
        (let [{:keys [original-event]}
              coeffects]
          (if-let [result (get @!results [original-event acofxs])]
            (update context :coeffects merge result)
            (run-acofxs-n-abort-event! context acofxs-n-options ))))

      :after
      (fn [{:keys [coeffects] :as context}]
        (let [{:keys [original-event]} coeffects]
          (if (fx-handler-run? context)
            (do
              (swap! !results dissoc [original-event acofxs])
              (update context :acoeffects (fnil dissoc {}) [original-event acofxs]))
            context)))))))

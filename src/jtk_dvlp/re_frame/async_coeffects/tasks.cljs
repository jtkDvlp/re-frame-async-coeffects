(ns jtk-dvlp.re-frame.async-coeffects.tasks
  "Keeps a re-frame-tasks task running while the acofxs of its event are
   underway.

   An event with acofxs is aborted and dispatched again once they are
   done, see `jtk-dvlp.re-frame.async-coeffects`. Without this namespace
   the task of the aborted run ends with it, and the run with the results
   opens a second one. Here the task is claimed while the acofxs run and
   the event picks it up again when it comes back.

   Its own namespace, so re-frame-tasks is only needed by who requires it."
  (:require
   [cljs.core.async :as core-async]

   [re-frame.core :as rf]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.re-frame.async-coeffects :as acofxs]
   [jtk-dvlp.re-frame.tasks :as tasks]))


(defn- resume-task
  "Marks the event to be dispatched again as continuation of the task."
  [context]
  (let [task-id (tasks/task-id context)]
    (update-in context [:coeffects :original-event] tasks/resume task-id)))

(defn- release-on-failure!
  [task-id claim-key ?error]
  (core-async/take!
   ?error
   (fn [error]
     (when (async/exception? error)
       (rf/dispatch [::tasks/release task-id claim-key])))))

(defn- claim-or-release
  [context]
  (let [{:keys [dispatch-id ?error]}
        (:acoeffects context)

        task-id
        (tasks/task-id context)]

    ;; NOTE: `?error` is only there when an injection of this run started
    ;; its acofxs and aborted the run.
    (if ?error
      (do
        (release-on-failure! task-id dispatch-id ?error)
        (tasks/claim context dispatch-id))
      (tasks/release context dispatch-id))))

(defn- has-task?
  [context]
  (some? (tasks/task-id context)))

(def ^:private track-task
  (rf/->interceptor
   :id ::track-task

   :before
   (fn [context]
     (cond-> context
       (has-task? context)
       (resume-task)))

   ;; NOTE: Claims are noted in the context and applied by `as-task`
   ;; in its `:after`. This `:after` has to run before that one, which it
   ;; does as long as `as-task` comes first in the interceptor chain.
   :after
   (fn [context]
     (cond-> context
       (has-task? context)
       (claim-or-release)))))

(defn- compose-interceptors
  "One interceptor out of `outer` and `inner`: `outer`'s `:before` runs
   first, its `:after` last -- as if `outer` came first in the chain."
  [id outer inner]
  (rf/->interceptor
   :id id

   :before
   (fn [context]
     (-> context
         ((or (:before outer) identity))
         ((or (:before inner) identity))))

   :after
   (fn [context]
     (-> context
         ((or (:after inner) identity))
         ((or (:after outer) identity))))))

(defn inject-acofxs
  "Like `jtk-dvlp.re-frame.async-coeffects/inject-acofxs`, and keeps the
   task of the event running while the acofxs run. Place it after
   `jtk-dvlp.re-frame.tasks/as-task`; without a task it behaves just like
   the plain one.

   One interceptor, so it serves as global interceptor too -- then
   `as-task` has to be a global one registered before it."
  [& acofxs]
  (compose-interceptors
   ::inject-acofxs
   track-task
   (apply acofxs/inject-acofxs acofxs)))

(defn inject-acofx
  "Like `jtk-dvlp.re-frame.async-coeffects/inject-acofx`, see
   `inject-acofxs`."
  {:arglists
   '([id]
     [id [:as args]]
     [id {:keys [args on-failure inject-key]}])}

  ([id]
   (inject-acofx id nil))

  ([id value]
   (compose-interceptors
    ::inject-acofx
    track-task
    (acofxs/inject-acofx id value))))

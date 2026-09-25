[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.jtkdvlp/re-frame-async-coeffects.svg)](https://clojars.org/net.clojars.jtkdvlp/re-frame-async-coeffects)
[![cljdoc badge](https://cljdoc.org/badge/net.clojars.jtkdvlp/re-frame-async-coeffects)](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT)

# re-frame-async-coeffects

re-frame interceptors to register and inject async actions as coeffects for events.

## Features

* register async coeffects
* inject one or more async coeffects to events
  * multiple async coeffects will be synced for event calls (concurrent processing)
  * supports error handling via an on-failure event (per injection, per acofx or globally)
  * effect arguments computed from the coeffects, the event and the registered configuration
* keeps a [re-frame-tasks](https://github.com/jtkDvlp/re-frame-tasks) task running while acofxs run
* convert effects like [http-fx](https://github.com/day8/re-frame-http-fx) to async coeffect

## Motivation

Often you have to request backend data via http or some other http like bridge (electron remote e.g.). Such backend requests are async. Some browser / electron apis are also async e.g. clipboard. Such async api / backend request can be done via effect like following:

```clojure
;; maybe you have some view to init load data and/or do other preparing stuff
(reg-event-fx ::init-my-view
  (fn [_ _]
    ;; use effect to load the data. So the view wont be init with ::init-my-view, but it will start initializing.
    {:http-xhrio
      {:uri "load some data"
       ...
       ;; the event that will do futher initialization
       :on-success [::set-my-view-data]}})

(reg-event-fx ::set-my-view-data
  (fn [{:keys [db]} [_ backend-data]]
     ;; got the data, put in app db to use...
    {:db (assoc db ::data backend-data)
     ;; ...and mybe load further data.
     ;; WATCHOUT: you can only do one http request at a time with http-xhrio as with many effects. So you have to do it afterwards.
     :http-xhrio
      {:uri "load some other data"
       ...
       ;; hopefully the finalizing event after data loaded.
       :on-success [::set-my-view-other-data]}})

(reg-event-db ::set-my-view-other-data
  (fn [db [_ backend-data]]
    ;; got the other data, put it in app db to use and do finalizing stuff to show the view correctly.
    (assoc db ::other-data backend-data)
    ...))
```

So three event registrations for loading two resources and initializing a view, actualy a more or less simple task, but in my opinion a lot to write and more important to read. So imagine a more complex app with many such cases could be confusing. But one more, the two resources were load sequentially not concurrently.

To get a solution for it, do one step back: From the view of an event resources are changing world values. So this is the reason why using effectts to handle it. But why via effect? Actualy effects often handle changing the world not as in the example above reading from it. Therefore we have coeffects, for reading form the changing world. So effects and coeffects represent the changing world for a re-frame app. What´s the different between effect and coeffect? Actualy the point of view from an event. Coeffect is the input and effect is the output of an event.

What do I want for my events? I want to do some stuff with backend resource to prepare my view. So actualy these resources are input data to my event like current timestamp or cookies etc. So it would be nice to get the resources as coeffects with my event.

Said and done:

```clojure
;; register the http-xhrio effect as coeffect
(reg-acofx-by-fx ::backend-resource  ; the new async coeffect (acofx) name
  {:fx-id :http-xhrio         ; the original effect
   :on-success-key :on-success ; the effect's key for the success event
   :on-failure-key :on-failure ; the effect's key for the failure event
   ;; and some initial config for the effect
   :initial-args
   {:method :get
    :response-format (ajax/json-response-format {:keywords? true})}})

;; event to initialize the view using the new coeffect.
(reg-event-fx ::init-my-view
  ;; use the backend-resource acofx twice with a certain uri and key within
  ;; the coeffects map of the event
  [(inject-acofxs
    [::backend-resource {:args [{:uri "load some data"}]
                         :inject-key :some-data}]
    [::backend-resource {:args [{:uri "load some other data"}]
                         :inject-key :some-other-data}])]
  ;; WATCHOUT: the resources are loaded concurrently!!
  (fn [{:keys [db some-data some-other-data]} _]
    ;; Got all the backend data, put it into app db to use and to all initializing stuff.
    {:db (assoc db ::data some-data,
                ::other-data some-other-data)}
    ...))
```

So few benifits in my opinion:
- less code and more transparent structure
- more re-frame idiomatic handling of changing world values
- concurrent resources processing

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.jtkdvlp/re-frame-async-coeffects.svg)](https://clojars.org/net.clojars.jtkdvlp/re-frame-async-coeffects)

### Usage

See in repo [your-project.cljs](https://github.com/jtkDvlp/re-frame-async-coeffects/blob/master/dev/jtk_dvlp/your_project.cljs)

```clojure
(ns jtk-dvlp.your-project
  (:require
   ...
   [jtk-dvlp.re-frame.async-coeffects :as rf-acofxs]))


(rf-acofxs/reg-acofx ::async-now
  (fn [{:keys [db]} & [delay-in-ms]]
    (go
      (let [delay-in-ms
            (or delay-in-ms (::delay db) 1000)

            start
            (js/Date.)]

        (println "acofx async-now" delay-in-ms)
        (when (> delay-in-ms 10000)
          (throw (ex-info "too long delay!" {:code :too-long-delay})))

        (<! (timeout delay-in-ms))
        (println "acofx async-now finished" delay-in-ms)
        (- (.getTime (js/Date.)) (.getTime start))))))

(rf-acofxs/reg-acofx-by-fx ::github-repo-meta
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args
   {:method :get
    :uri "https://api.github.com/repos/jtkDvlp/re-frame-async-coeffects"
    :response-format (ajax/json-response-format {:keywords? true})}})

(rf-acofxs/reg-acofx-by-fx ::http-request
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args
   {:method :get
    :response-format (ajax/json-response-format {:keywords? true})}})

;; Dispatched on any failure no injection or handler names an event for.
(rf-acofxs/set-global-on-failure-event [::change-message "ahhhhhh!"])

(defn- repo-meta-request
  [repo inject-key]
  {:args [{:uri (str "https://api.github.com/repos/jtkDvlp/" repo)}]
   :inject-key inject-key})

(rf/reg-event-fx ::do-work-with-async-stuff
  [;; Inject one single acofx, the global on-failure event applies.
   ;; Without args it waits for the delay set in the input.
   (rf-acofxs/inject-acofx ::async-now)

   ;; Inject several acofxs, run concurrently.
   (rf-acofxs/inject-acofxs
    ;; With args and a key of its own in the coeffects.
    [::async-now {:args [5000], :inject-key ::async-now-5-secs-delayed}]

    ;; With its own on-failure event, instead of the global one.
    [::github-repo-meta {:on-failure [::change-message "github failed"]}]

    ;; The same acofx twice, under different keys.
    [::http-request
     (repo-meta-request "re-frame-tasks" ::re-frame-tasks-meta)]
    [::http-request
     (repo-meta-request "core.async-helpers" ::core.async-helpers-meta)])

   ;; An ordinary cofx, as usual.
   (rf/inject-cofx ::now)]

  (fn [{:keys [db] :as cofxs} _]
    (let [async-computed-results
          (-> cofxs
              (update ::github-repo-meta :description)
              (update ::re-frame-tasks-meta :description)
              (update ::core.async-helpers-meta :description)
              (dissoc :db :event :original-event))]

      {:db
       (-> db
           (assoc ::async-computed-results async-computed-results)
           (assoc ::message nil))})))
```

### Keeping a task running (re-frame-tasks)

With [re-frame-tasks](https://github.com/jtkDvlp/re-frame-tasks), use the
injections from `jtk-dvlp.re-frame.async-coeffects.tasks` after `as-task`.
The task then keeps running while the acofxs run, `wait-for` holds other
events back until the handler ran, and a failure ends the task. Without a
task they behave like the plain ones.

```clojure
(ns your-project
  (:require
   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.tasks :as tasks]
   [jtk-dvlp.re-frame.async-coeffects.tasks :as acofx-tasks]))

(rf/reg-event-fx ::init-my-view
  [(tasks/as-task :loading)
   (acofx-tasks/inject-acofx ::backend-resource [{:uri "load some data"}])]
  (fn [{:keys [db] ::keys [backend-resource]} _]
    {:db (assoc db ::data backend-resource)}))
```

Only this namespace needs re-frame-tasks (3.x) as dependency.

## Migrating from 2.x

**acofx handlers return the value, not the coeffects.** The handler still
gets the coeffects first, but the channel carries the value to inject.

```clojure
;; 2.x
(fn [coeffects] (go (assoc coeffects ::now (js/Date.))))
;; 3.x
(fn [coeffects] (go (js/Date.)))
```

**`reg-acofx-by-fx` takes a map.**

```clojure
;; 2.x
(reg-acofx-by-fx ::http :http-xhrio :on-success :on-failure {:method :get})
;; 3.x
(reg-acofx-by-fx ::http
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args {:method :get}})
```

`:initial-args` may also be a function of the coeffects and the event. The
new `:on-failure-event` names the event to dispatch when the effect fails.

**Injection takes `[id opts]` per acofx.** Args, the key in the coeffects
and the failure event are options of each acofx, not of the whole
injection. The map form of `inject-acofxs` is gone; `:inject-key` replaces
its keys.

```clojure
;; 2.x
(inject-acofxs {:a [::http {:uri "/a"}]
                :b [::http {:uri "/b"}]}
               {:error-dispatch [::failed]})
;; 3.x
(inject-acofxs
 [::http {:args [{:uri "/a"}], :inject-key :a, :on-failure [::failed]}]
 [::http {:args [{:uri "/b"}], :inject-key :b, :on-failure [::failed]}])

;; 2.x
(inject-acofx [::async-now 5000])
;; 3.x
(inject-acofx ::async-now [5000])
```

**Only `reg-acofx-by-fx` computes arguments.** In 2.x any injection
argument that was a function got called with the coeffects and the event.
Now a handler gets its arguments as given; it has the coeffects anyway.
`reg-acofx-by-fx` computes them: its `:initial-args` may be a function of
the coeffects and the event, and the injection argument may be a function
that also gets the resolved `:initial-args` and returns the configuration
to use -- so it can derive from the registration, down to removing keys:

```clojure
(inject-acofx ::http [(fn [_coeffects _event initial-args]
                        (update initial-args :uri str "/details"))])
```

**`set-global-error-dispatch!` is now `set-global-on-failure-event`.** On
failure the event dispatched is the injection's `:on-failure`, else the
one the acofx handler put under `::on-failure` into its exception (for
`reg-acofx-by-fx`: `:on-failure-event`), else the global one.

**The exception appended to the failure event is wrapped.** It is an
`ex-info` with `:code ::acofx-error` and the failed acofx under `::acofx`;
the handler's own exception is its cause. For `reg-acofx-by-fx`, what the
effect reported is under `:error` in the `ex-data` of that cause.

**The event is dispatched again as it was dispatched** (`:original-event`),
so interceptors like `trim-v` see it unchanged on every run.

**re-frame-tasks 3.x** is supported through
`jtk-dvlp.re-frame.async-coeffects.tasks`, see above. re-frame-tasks 2.x
keeps working with the plain injections.

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)

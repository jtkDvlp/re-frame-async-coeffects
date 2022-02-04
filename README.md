[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.jtkdvlp/re-frame-async-coeffects.svg)](https://clojars.org/net.clojars.jtkdvlp/re-frame-async-coeffects)
[![cljdoc badge](https://cljdoc.org/badge/net.clojars.jtkdvlp/re-frame-async-coeffects)](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT)

# re-frame-async-coeffects

re-frame interceptors to register and inject async actions as coeffects for events.

## Features

* register async coeffects
* inject one or more async coeffects to events
  * multiple async coeffects will be synced for event calls (concurrent processing)
  * supports error handling via error dispatch vector (can also be set globally)
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
  :http-xhrio ; the original effect
  :on-success ; the trigger event for success
  :on-failure ; the trigger event for failure
  ;; and some initial config for the effect
  {:method :get
   :response-format (ajax/json-response-format {:keywords? true})})

;; event to initialize the view using the new coeffect.
(reg-event-fx ::init-my-view
  [(inject-acofx
    {:acofxs
     ;; use the backend-resource acofx twice with a certain uri and key within coeffects-map for the event
     {:some-data [::backend-resource {:uri "load some data"}],
      :some-other-data [::backend-resource {:uri "load some other data"}]}})]
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
  (fn [coeffects delay-in-ms]
    (go
      (let [delay-in-ms
            (or delay-in-ms 1000)

            start
            (js/Date.)]

        (println "acofx async-now" delay-in-ms)
        (when (> delay-in-ms 10000)
          (throw (ex-info "too long delay!" {:code :too-long-delay})))

        (<! (timeout delay-in-ms))
        (println "acofx async-now finished" delay-in-ms)
        (assoc coeffects ::async-now (- (.getTime (js/Date.))(.getTime start)),)))))

(rf-acofxs/reg-acofx-by-fx ::github-repo-meta
  :http-xhrio
  :on-success
  :on-failure
  {:method :get
   :uri "https://api.github.com/repos/jtkDvlp/re-frame-async-coeffects"
   :response-format (ajax/json-response-format {:keywords? true})})

(rf-acofxs/reg-acofx-by-fx ::http-request
  :http-xhrio
  :on-success
  :on-failure
  {:method :get
   :response-format (ajax/json-response-format {:keywords? true})})

(rf-acofxs/set-global-error-dispatch! [::change-message "ahhhhhh!"])

(rf/reg-event-fx ::do-work-with-async-stuff
  [(rf-acofxs/inject-acofx ::async-now) ; Inject one single acofx without error-dispatch (global set error-dispatch will be used)
   (rf-acofxs/inject-acofxs             ; Inject multiple acofxs and renames keys within coeffects map.
    {::async-now*
     ::async-now

     ::async-now-5-secs-delayed
     [::async-now 5000]                 ; Inject with one value arg

     ::async-now-x-secs-delayed
     [::async-now #(get-in % [:db ::delay] 0)] ; Inject with one fn arg

     ::github-repo-meta
     ::github-repo-meta

     ::re-frame-tasks-meta
     [::http-request {:uri "https://api.github.com/repos/jtkDvlp/re-frame-tasks"}]

     ::core.async-helpers-meta
     [::http-request {:uri "https://api.github.com/repos/jtkDvlp/core.async-helpers"}]}

    {:error-dispatch [::change-message "ahhhhhh!"]} ; Overrides global set error-dispatch for these acofxs
    ,,,)
   (rf/inject-cofx ::now)               ; Inject normal cofx
   ]
  (fn [{:keys [db] :as cofxs} _]
    (let [async-computed-results
          (-> cofxs
              (update ::github-repo-meta (comp :description))
              (update ::re-frame-tasks-meta (comp :description))
              (update ::core.async-helpers-meta (comp :description))
              (dissoc :db :event :original-event))]

      {:db
       (-> db
           (assoc ::async-computed-results async-computed-results)
           (assoc ::message nil))})))
```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)

(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.pprint]
   [ajax.core :as ajax]
   [day8.re-frame.http-fx]
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!]]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [re-frame.core :as rf]

   [jtk-dvlp.re-frame.async-coeffects :as rf-acofxs]))


(rf/reg-cofx ::now
  (fn [coeffects]
    (println "cofx now")
    (assoc coeffects ::now (js/Date.))))

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

(rf/reg-sub ::async-computed-results
  (fn [db]
    (::async-computed-results db)))

(rf/reg-event-db ::change-delay
  (fn [db [_ delay]]
    (assoc db ::delay delay)))

(rf/reg-sub ::delay
  (fn [db]
    (::delay db 0)))

(rf/reg-event-db ::change-message
  (fn [db [_ message & more]]
    (assoc db ::message [message more])))

(rf/reg-sub ::message
  (fn [db]
    (::message db)))

(defn app-view
  []
  [:<>
   [:p (str @(rf/subscribe [::message]))]
   [:button
    {:on-click #(rf/dispatch [::do-work-with-async-stuff])}
    "do work"]
   [:input
    {:type :number
     :value @(rf/subscribe [::delay])
     :on-change #(rf/dispatch-sync [::change-delay (-> % .-target .-value)])}]
   [:pre
    (with-out-str (cljs.pprint/pprint @(rf/subscribe [::async-computed-results])))]])


;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; re-frame setup

(defn- mount-app
  []
  (rdom/render
    [app-view]
    (gdom/getElement "app")))

(defn ^:after-load on-reload
  []
  (rf/clear-subscription-cache!)
  (mount-app))

(defonce on-init
  (mount-app))

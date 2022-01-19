(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.pprint]
   [ajax.core :as ajax]
   [day8.re-frame.http-fx]
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!]]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [re-frame.core :refer [reg-event-fx reg-event-db dispatch reg-sub subscribe reg-cofx inject-cofx] :as rf]

   [jtk-dvlp.re-frame.async-coeffects :refer [reg-acofx reg-acofx-by-fx inject-acofx]]))


(reg-cofx ::now
  (fn [coeffects]
    (println "cofx now")
    (assoc coeffects ::now (js/Date.))))

(reg-acofx ::async-now
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

(reg-acofx-by-fx ::github-repo-meta
  :http-xhrio
  :on-success
  :on-failure
  {:method :get
   :uri "https://api.github.com/repos/jtkDvlp/re-frame-async-coeffects"
   :response-format (ajax/json-response-format {:keywords? true})})

(reg-acofx-by-fx ::http-request
  :http-xhrio
  :on-success
  :on-failure
  {:method :get
   :response-format (ajax/json-response-format {:keywords? true})})

(reg-event-fx ::do-async-stuff
  [(inject-acofx
    {:acofxs
     {::async-now
      ::async-now

      ::async-now-5-secs-delayed
      [::async-now
       5000]

      ::async-now-x-secs-delayed
      [::async-now
       (fn [{:keys [db]}] [(get db ::delay 0)])]

      ::async-now-xDIV2-secs-delayed
      [::async-now
       (fn [{:keys [db]} multiply] [(* multiply (get db ::delay 0))])
       0.5]

      ::github-repo-meta
      ::github-repo-meta

      ::re-frame-tasks-meta
      [::http-request {:uri "https://api.github.com/repos/jtkDvlp/re-frame-tasks"}]

      ::core.async-helpers-meta
      [::http-request {:uri "https://api.github.com/repos/jtkDvlp/core.async-helpers"}]}

     :error-dispatch [::change-message "ahhhhhh!"]}
    ,,,)
   (inject-cofx ::now)]
  (fn [{:keys [db] :as cofxs} _]
    (let [async-computed-results
          (-> cofxs
              (update ::github-repo-meta (comp :description))
              (update ::re-frame-tasks-meta (comp :description))
              (update ::core.async-helpers-meta (comp :description))
              (dissoc :db :event :original-event))]

      {:db
       (-> db
           (update ::async-computed-results (fnil conj []) async-computed-results)
           (assoc ::message nil))})))

(reg-sub ::async-computed-results
  (fn [db]
    (reverse (::async-computed-results db))))

(reg-event-db ::change-delay
  (fn [db [_ delay]]
    (assoc db ::delay delay)))

(reg-sub ::delay
  (fn [db]
    (::delay db 0)))

(reg-event-db ::change-message
  (fn [db [_ message & more]]
    (assoc db ::message [message more])))

(reg-sub ::message
  (fn [db]
    (::message db)))

(defn app-view
  []
  [:<>
   [:p (str @(subscribe [::message]))]
   [:button
    {:on-click #(dispatch [::do-async-stuff])}
    "take timestamp"]
   [:input
    {:type :number
     :value @(subscribe [::delay])
     :on-change #(rf/dispatch-sync [::change-delay (-> % .-target .-value)])}]
   [:ul
    (for [timestamp @(subscribe [::async-computed-results])]
      ^{:key timestamp}
      [:li
       [:pre
        (with-out-str (cljs.pprint/pprint timestamp))]])]])


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

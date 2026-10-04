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
  (fn [{:keys [db]} {delay-in-ms :value}]
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
  [::http-request
   {:uri (str "https://api.github.com/repos/jtkDvlp/" repo)}
   {:inject-key inject-key}])

(rf/reg-event-fx ::do-work-with-async-stuff
  [;; Inject one single acofx, the global on-failure event applies.
   ;; Without a value it waits for the delay set in the input.
   (rf-acofxs/inject-acofx ::async-now)

   ;; Inject several acofxs, run concurrently.
   (rf-acofxs/inject-acofxs
    ;; With a value and a key of its own in the coeffects.
    [::async-now 5000 {:inject-key ::async-now-5-secs-delayed}]

    ;; With its own on-failure event, instead of the global one.
    [::github-repo-meta nil {:on-failure [::change-message "github failed"]}]

    ;; The same acofx twice, under different keys.
    (repo-meta-request "re-frame-tasks" ::re-frame-tasks-meta)
    (repo-meta-request "core.async-helpers" ::core.async-helpers-meta))

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

(rf/reg-sub ::async-computed-results
  (fn [db]
    (::async-computed-results db)))

(rf/reg-event-db ::change-delay
  (fn [db [_ delay]]
    (assoc db ::delay (js/parseInt delay))))

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

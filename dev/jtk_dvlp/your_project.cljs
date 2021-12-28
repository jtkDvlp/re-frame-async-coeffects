(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!]]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [re-frame.core :refer [reg-event-fx reg-event-db dispatch reg-sub subscribe reg-cofx inject-cofx] :as rf]

   [jtk-dvlp.re-frame.async-coeffects :refer [reg-acofx inject-acofx]]))


(reg-acofx ::async-now
  (fn [coeffects delay-in-ms]
    (go
      (let [delay-in-ms
            (or delay-in-ms 1000)

            start
            (js/Date.)]

        (when (> delay-in-ms 10000)
          (throw (ex-info "too long delay!" {:code :too-long-delay})))

        (<! (timeout delay-in-ms))
        (assoc
         coeffects
         [::async-now delay-in-ms]
         {:start start, :end (js/Date.)})))))

(reg-cofx ::now
  (fn [coeffects]
    (assoc coeffects ::now (js/Date.))))

(reg-event-fx ::take-timestamp
  [(inject-acofx
    {:acofxs
     [::async-now
      [::async-now
       5000]
      [::async-now
       (fn [{:keys [db] :as x}] [(get db ::delay 0)])]
      [::async-now
       (fn [{:keys [db] :as x} multiply] [(* multiply (get db ::delay 0))])
       0.5]]
     :error-dispatch [::change-message "ahhhhhh!"]}
    ,,,)
   (inject-cofx ::now)]
  (fn [{:keys [db] :as cofxs} _]
    (let [timestamps
          (dissoc cofxs :db :event :original-event)]

      {:db
       (-> db
           (update ::timestamps (fnil conj []) timestamps)
           (assoc ::message nil))})))

(reg-sub ::taken-timestamps
  (fn [db]
    (reverse (::timestamps db))))

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
    {:on-click #(dispatch [::take-timestamp])}
    "take timestamp"]
   [:input
    {:type :number
     :value @(subscribe [::delay])
     :on-change #(rf/dispatch-sync [::change-delay (-> % .-target .-value)])}]
   [:ul
    (for [timestamp @(subscribe [::taken-timestamps])]
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

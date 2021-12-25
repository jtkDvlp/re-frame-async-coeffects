(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!]]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [re-frame.core :refer [reg-event-fx dispatch reg-sub subscribe reg-cofx inject-cofx] :as rf]

   [jtk-dvlp.re-frame.async-coeffects :refer [reg-acofx]]))


(reg-acofx ::async-now
  (fn [coeffects delay-in-ms]
    (go
      (<! (timeout (or delay-in-ms 1000)))
      (assoc coeffects ::async-now (js/Date.)))))

(reg-cofx ::now
  (fn [coeffects]
    (assoc coeffects ::now (js/Date.))))

(reg-event-fx ::take-timestamp
  [(inject-cofx ::now)]
  (fn [{:keys [db ::now]} _]
    {:db (update db ::timestamps (fnil conj []) now)}))

(reg-sub ::taken-timestamps
  (fn [db]
    (::timestamps db)))

(defn app-view
  []
  [:<>
   [:button
    {:on-click #(dispatch [::take-timestamp])}
    "take timestamp"]
   [:ul
    (for [timestamp @(subscribe [::taken-timestamps])]
      ^{:key timestamp}[:li (str timestamp)])]])


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

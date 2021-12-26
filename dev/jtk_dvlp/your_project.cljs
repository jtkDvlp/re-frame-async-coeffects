(ns ^:figwheel-hooks jtk-dvlp.your-project
  (:require
   [cljs.core.async :refer [timeout]]
   [jtk-dvlp.async :refer [go <!]]

   [goog.dom :as gdom]
   [reagent.dom :as rdom]
   [re-frame.core :refer [reg-event-fx dispatch reg-sub subscribe reg-cofx inject-cofx] :as rf]

   [jtk-dvlp.re-frame.async-coeffects :refer [reg-acofx inject-acofx]]))


(reg-acofx ::async-now
  (fn [coeffects delay-in-ms]
    (go
      (let [start (js/Date.)]
        (<! (timeout (or delay-in-ms 1000)))
        (assoc coeffects ::async-now  {:start start, :end (js/Date.)})))))

(reg-cofx ::now
  (fn [coeffects]
    (assoc coeffects ::now (js/Date.))))

(reg-event-fx ::take-timestamp
  [(inject-acofx ::async-now)
   (inject-cofx ::now)]
  (fn [{:keys [db ::now ::async-now]} _]
    (->> {:now now :async-now async-now}
         (update db ::timestamps (fnil conj []))
         (hash-map :db))))

(reg-sub ::taken-timestamps
  (fn [db]
    (reverse (::timestamps db))))

(defn app-view
  []
  [:<>
   [:button
    {:on-click #(dispatch [::take-timestamp])}
    "take timestamp"]
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

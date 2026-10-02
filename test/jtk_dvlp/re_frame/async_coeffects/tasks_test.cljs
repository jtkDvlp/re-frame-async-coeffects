(ns jtk-dvlp.re-frame.async-coeffects.tasks-test
  (:require
   [cljs.core.async :as core-async]
   [cljs.test :refer-macros [deftest is async use-fixtures]]

   [re-frame.core :as rf]
   [re-frame.db :as rf-db]

   [jtk-dvlp.async :as async]
   [jtk-dvlp.re-frame.async-coeffects :as acofxs]
   [jtk-dvlp.re-frame.async-coeffects.tasks :as acofx-tasks]
   [jtk-dvlp.re-frame.tasks :as tasks]
   [jtk-dvlp.re-frame.test-helpers :as helpers
    :refer [record-handled! !handled !failures <eventually <settle
            run-async]]))


(def ^:private !release-acofx
  "The gated acofx under test finishes once this channel closes."
  (atom nil))

(def ^:private !task-counts
  "Number of registered tasks, sampled on every app-db change."
  (atom []))

(defn- sample-task-count
  [_key _ref _old db]
  (swap! !task-counts conj (count (tasks/get-tasks db))))

(use-fixtures :each
  helpers/re-frame-fixture
  {:before
   (fn []
     (reset! !release-acofx (core-async/chan))
     (reset! !task-counts [])
     (add-watch rf-db/app-db ::task-counts sample-task-count))

   :after
   (fn []
     (remove-watch rf-db/app-db ::task-counts))})

(defn- running?
  []
  (tasks/running? @rf-db/app-db))

(defn- reg-gated-acofx!
  [fail?]
  (acofxs/reg-acofx ::gated
    (fn [_coeffects _injection]
      (let [?release @!release-acofx]
        (async/go
          (async/<! ?release)
          (when fail?
            (throw (ex-info "gated acofx under test failed" {})))
          :value)))))

(defn- release-acofx!
  []
  (core-async/close! @!release-acofx))

(defn- reg-task-event!
  []
  (rf/reg-event-fx ::event
    [(tasks/as-task :loading)
     (acofx-tasks/inject-acofx ::gated {:on-failure [::helpers/failed :x]})]
    (fn [{::keys [gated]} _]
      (record-handled! gated)
      {})))


(deftest keeps-the-task-running-while-acofxs-run
  (async done
    (run-async done
      (async/go
        (reg-gated-acofx! false)
        (reg-task-event!)

        (rf/dispatch [::event])
        (is (async/<! (<eventually running?)))
        (async/<! (<settle))
        (is (running?) "task ended while its acofx still ran")

        (release-acofx!)
        (is (async/<! (<eventually #(seq @!handled))))
        (is (async/<! (<eventually (complement running?))))
        (is (= [:value] @!handled))
        (is (= #{0 1} (set @!task-counts))
            "the run with the results opened a second task")))))

(deftest lets-waiting-events-wait-for-the-acofxs
  (async done
    (run-async done
      (async/go
        (reg-gated-acofx! false)
        (reg-task-event!)
        (rf/reg-event-fx ::waiting
          [(tasks/wait-for :loading)]
          (fn [_ _]
            (record-handled! :waiting)
            {}))

        (rf/dispatch [::event])
        (is (async/<! (<eventually running?)))
        (rf/dispatch [::waiting])
        (async/<! (<settle))
        (is (empty? @!handled) "waiting event ran before the task ended")

        (release-acofx!)
        (is (async/<! (<eventually #(= 2 (count @!handled)))))
        (is (= [:value :waiting] @!handled))))))

(deftest ends-the-task-when-an-acofx-fails
  (async done
    (run-async done
      (async/go
        (reg-gated-acofx! true)
        (reg-task-event!)

        (rf/dispatch [::event])
        (is (async/<! (<eventually running?)))
        (release-acofx!)
        (is (async/<! (<eventually #(seq @!failures))))
        (is (async/<! (<eventually (complement running?))))
        (is (empty? @!handled))))))

(deftest works-without-a-task
  (async done
    (run-async done
      (async/go
        (reg-gated-acofx! false)
        (rf/reg-event-fx ::event
          [(acofx-tasks/inject-acofx ::gated)]
          (fn [{::keys [gated]} _]
            (record-handled! gated)
            {}))

        (rf/dispatch [::event])
        (async/<! (<settle))
        (release-acofx!)
        (is (async/<! (<eventually #(seq @!handled))))
        (is (= [:value] @!handled))
        (is (not (running?)))))))

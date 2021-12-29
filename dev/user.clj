(ns user
  (:require
   [figwheel.main.api :as figwheel]))

(defn fig-init
  []
  (figwheel/start {:mode :serve} "dev"))

(defn cljs-repl
  []
  (figwheel/cljs-repl "dev"))

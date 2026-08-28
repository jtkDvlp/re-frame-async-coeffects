(defproject net.clojars.jtkdvlp/re-frame-async-coeffects "3.0.0-SNAPSHOT"
  :description
  "A re-frame interceptors to use async actions as coeffect for events"

  :url
  "https://github.com/jtkDvlp/re-frame-async-coeffects"

  :license
  {:name
   "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
   :url
   "https://www.eclipse.org/legal/epl-2.0/"}

  :plugins
  [[lein-ancient "1.0.0"]]

  :source-paths
  ["src"]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.12.5"]

     [re-frame "1.4.7"]

     [org.clojure/core.async "1.9.865"]
     [jtk-dvlp/core.async-helpers "3.6.0"]]}

   :dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.20"]
     [day8.re-frame/http-fx "0.2.4"]]

    :source-paths
    ["dev"]

    :resource-paths
    ["target"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.7.0"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user

     :init
     (fig-init)}}}

  ,,,)

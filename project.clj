(defproject jtk-dvlp/re-frame-async-coeffects "0.0.0-SNAPSHOT"
  :description
  "A re-frame interceptors to use async actions as coeffect for events"

  :url
  "https://github.com/jtkDvlp/re-frame-async-coeffects"

  :license
  {:name
   "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
   :url
   "https://www.eclipse.org/legal/epl-2.0/"}

  :source-paths
  ["src"]

  :target-path
  "target"

  :clean-targets
  ^{:protect false}
  [:target-path]

  :profiles
  {:provided
   {:dependencies
    [[org.clojure/clojure "1.10.0"]
     [org.clojure/clojurescript "1.10.773"]

     [re-frame "1.1.2"]

     [org.clojure/core.async "1.3.610"]
     [jtk-dvlp/core.async-helpers "3.0.0"]]}

   :dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.13"]
     [day8.re-frame/http-fx "0.2.3"]]

    :source-paths
    ["dev"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.5.2"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user

     :init
     (fig-init)
     }}}

  ,,,)

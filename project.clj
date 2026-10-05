;; WATCHOUT: release-please owns this number, not a hand. The annotation
;; is what it finds the line by -- without it only the changelog moves on
;; and the package keeps the old version. What stands here is the last
;; release; the next one is decided by the commits since.
(defproject net.clojars.jtkdvlp/re-frame-async-coeffects "3.1.0" ; x-release-please-version
  :description
  "re-frame interceptors to use async actions as coeffects for events"

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

  :deploy-repositories
  [["clojars"
    {:url
     "https://repo.clojars.org/"

     ;; WATCHOUT: The credentials are repository secrets reaching the
     ;; build through environment variables -- never a file in the repo,
     ;; not even an ignored one.
     :username
     :env/clojars_username

     :password
     :env/clojars_password

     ;; NOTE: No signing key in the run, and Clojars asks for no
     ;; signature.
     :sign-releases
     false}]]

  :clean-targets
  ^{:protect false}
  ["target"]

  :profiles
  {:provided
   ;; WATCHOUT: `reagent` belongs here although this library never calls
   ;; it. re-frame declares it `provided` itself, so it does not arrive
   ;; transitively -- without it `re-frame.core` does not even compile.
   ;;
   ;; NOTE: re-frame-tasks is only needed by who requires
   ;; `jtk-dvlp.re-frame.async-coeffects.tasks`.
   {:dependencies
    [[org.clojure/clojure "1.12.5"]

     [re-frame "1.4.3" :exclusions [reagent]]
     [reagent "1.3.0"]

     [org.clojure/core.async "1.9.865"]
     [jtk-dvlp/core.async-helpers "3.6.1"]
     [jtk-dvlp/re-frame-tasks "3.0.0"]]}

   :dev
   {:dependencies
    [[com.bhauman/figwheel-main "0.2.20"]
     [day8.re-frame/http-fx "0.2.4"]
     [cljsjs/react "18.3.1-1"]
     [cljsjs/react-dom "18.3.1-1"]]

    :source-paths
    ["dev"]

    :resource-paths
    ["target"]}

   ;; NOTE: The library declares no ClojureScript dependency -- a consumer
   ;; brings their own. The test run needs a compiler, and React is a
   ;; build-time need of that compile, not a contract with consumers --
   ;; they bring their own, through cljsjs or npm.
   :test
   {:dependencies
    [[org.clojure/clojurescript "1.11.132"]
     [cljsjs/react "18.3.1-1"]
     [cljsjs/react-dom "18.3.1-1"]]

    :source-paths
    ["test"]}

   :repl
   {:dependencies
    [[cider/piggieback "0.6.1"]]

    :repl-options
    {:nrepl-middleware
     [cider.piggieback/wrap-cljs-repl]

     :init-ns
     user

     :init
     (fig-init)}}}

  ,,,)

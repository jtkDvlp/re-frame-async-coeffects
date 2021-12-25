(ns jtk-dvlp.re-frame.async-coeffects
  (:require
   [cljs.core.async]
   [re-frame.registrar :refer [register-handler get-handler]]))


(def kind :acofx)

(defn reg-acofx
  "Register the given async-coeffect `handler` for the given `id`, for later use
  within `inject-acofx`:

    - `id` is keyword, often namespaced.
    - `handler` is a function which takes either one or two arguements, the first of which is
       always `coeffects` and which returns an updated `coeffects` as `cljs.core.async/promise-chan`.

  See also: `inject-acofx`
  "
  [id handler]
  (register-handler kind id handler))

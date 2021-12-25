[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-async-coeffects.svg)](https://clojars.org/jtk-dvlp/re-frame-async-coeffects)
[![cljdoc badge](https://cljdoc.org/badge/jtk-dvlp/re-frame-async-coeffects)](https://cljdoc.org/d/jtk-dvlp/re-frame-async-coeffects/CURRENT)

# re-frame-async-coeffects

re-frame interceptors to register and inject async actions as coeffects for events.

## Features

* register async coeffects
* inject one or more async coeffects to events
  * multiple async coeffects which will be synced for event calls
* convert effects like [http-fx](https://github.com/day8/re-frame-http-fx) to async coeffect

## Getting started

### Get it / add dependency

Add the following dependency to your `project.clj`:<br>
[![Clojars Project](https://img.shields.io/clojars/v/jtk-dvlp/re-frame-async-coeffects.svg)](https://clojars.org/jtk-dvlp/re-frame-async-coeffects)

### Usage

```clojure
(ns jtk-dvlp.your-project
  (:require
   [re-frame.core :as rf]
   ,,,))


;; TODO WILL FOLLOW

```

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)

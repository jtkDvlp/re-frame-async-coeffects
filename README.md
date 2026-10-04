[![CI](https://github.com/jtkDvlp/re-frame-async-coeffects/actions/workflows/ci.yml/badge.svg)](https://github.com/jtkDvlp/re-frame-async-coeffects/actions/workflows/ci.yml)
[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.jtkdvlp/re-frame-async-coeffects.svg)](https://clojars.org/net.clojars.jtkdvlp/re-frame-async-coeffects)
[![cljdoc badge](https://cljdoc.org/badge/net.clojars.jtkdvlp/re-frame-async-coeffects)](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT)
[![License](https://img.shields.io/badge/License-EPL%202.0-red.svg)](https://opensource.org/licenses/EPL-2.0)
[![paypal](https://www.paypalobjects.com/en_US/i/btn/btn_donate_SM.gif)](https://www.paypal.com/donate?hosted_button_id=2PDXQMHX56T6U)

# re-frame-async-coeffects

[re-frame](https://github.com/day8/re-frame) interceptors to register async
actions -- a backend request, an IPC call, an async browser API -- and
inject their results into an event as coeffects. The event handler runs
once, with all of them at hand, instead of being split into a chain of
load, success and further events.

See the [API docs](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT)
for the full reference.

## The problem it solves

An event that needs data from the outside world usually starts an effect
and hands the rest of its work to the effect's success event. Two
resources make three events, and the second request only starts once the
first one is done:

```clojure
(reg-event-fx ::init-my-view
  (fn [_ _]
    {:http-xhrio {:uri "/some-data", ,,, :on-success [::got-some-data]}}))

(reg-event-fx ::got-some-data
  (fn [{:keys [db]} [_ some-data]]
    {:db (assoc db ::some-data some-data)
     :http-xhrio {:uri "/other-data", ,,, :on-success [::got-other-data]}}))

(reg-event-db ::got-other-data
  (fn [db [_ other-data]]
    (assoc db ::other-data other-data)))
```

Yet the data is not something the event *does*, it is something the event
*needs* -- input, like the current time or a cookie. re-frame has a name
for input: coeffects. Registered as an async coeffect (acofx), the same
case is one event, and both requests run concurrently:

```clojure
(reg-acofx-by-fx ::http
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args {:method :get, ,,,}})

(reg-event-fx ::init-my-view
  [(inject-acofxs
    [::http {:uri "/some-data"} {:inject-key :some-data}]
    [::http {:uri "/other-data"} {:inject-key :other-data}])]
  (fn [{:keys [db some-data other-data]} _]
    {:db (assoc db ::some-data some-data, ::other-data other-data)}))
```

## Features

Full reference per namespace:
[`jtk-dvlp.re-frame.async-coeffects`](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT/api/jtk-dvlp.re-frame.async-coeffects) ·
[`…async-coeffects.tasks`](https://cljdoc.org/d/net.clojars.jtkdvlp/re-frame-async-coeffects/CURRENT/api/jtk-dvlp.re-frame.async-coeffects.tasks)

  * **Async coeffects.** `reg-acofx` registers a handler that gets the
    event's coeffects and its injection and returns a channel with the
    value to inject;
    `inject-acofx` and `inject-acofxs` put the values into the event's
    coeffects before its handler runs.

  * **Concurrent loading.** Several acofxs of one injection run at the
    same time; the event handler runs once all of them are done.

  * **Effects as coeffects.** `reg-acofx-by-fx` turns an effect that
    reports through events -- such as
    [http-fx](https://github.com/day8/re-frame-http-fx)'s `:http-xhrio` --
    into an acofx. Its configuration can be computed from the coeffects and
    the event, per registration and per injection.

  * **Error handling.** A failing acofx keeps the event handler from
    running and dispatches an on-failure event instead -- named by the
    acofx, per injection or globally.

  * **re-frame-tasks integration.** With
    [re-frame-tasks](https://github.com/jtkDvlp/re-frame-tasks), a task
    keeps running while the acofxs of its event run, and `wait-for` holds
    other events back until the handler ran.

## Getting started

### Add the dependency

[![Clojars Project](https://img.shields.io/clojars/v/net.clojars.jtkdvlp/re-frame-async-coeffects.svg)](https://clojars.org/net.clojars.jtkdvlp/re-frame-async-coeffects)

The library brings no dependencies of its own; your project provides
re-frame, core.async and
[core.async-helpers](https://github.com/jtkDvlp/core.async-helpers), whose
error propagation the acofx handlers rely on. The re-frame-tasks
integration additionally needs re-frame-tasks 3.x, and only if you require
`jtk-dvlp.re-frame.async-coeffects.tasks`.

### Usage

See in repo [your-project.cljs](https://github.com/jtkDvlp/re-frame-async-coeffects/blob/master/dev/jtk_dvlp/your_project.cljs)

```clojure
(ns jtk-dvlp.your-project
  (:require
   ...
   [jtk-dvlp.re-frame.async-coeffects :as rf-acofxs]))


(rf-acofxs/reg-acofx ::async-now
  (fn [{:keys [db]} {delay-in-ms :value}]
    (go
      (let [delay-in-ms
            (or delay-in-ms (::delay db) 1000)

            start
            (js/Date.)]

        (println "acofx async-now" delay-in-ms)
        (when (> delay-in-ms 10000)
          (throw (ex-info "too long delay!" {:code :too-long-delay})))

        (<! (timeout delay-in-ms))
        (println "acofx async-now finished" delay-in-ms)
        (- (.getTime (js/Date.)) (.getTime start))))))

(rf-acofxs/reg-acofx-by-fx ::github-repo-meta
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args
   {:method :get
    :uri "https://api.github.com/repos/jtkDvlp/re-frame-async-coeffects"
    :response-format (ajax/json-response-format {:keywords? true})}})

(rf-acofxs/reg-acofx-by-fx ::http-request
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args
   {:method :get
    :response-format (ajax/json-response-format {:keywords? true})}})

;; Dispatched on any failure no injection or handler names an event for.
(rf-acofxs/set-global-on-failure-event [::change-message "ahhhhhh!"])

(defn- repo-meta-request
  [repo inject-key]
  [::http-request
   {:uri (str "https://api.github.com/repos/jtkDvlp/" repo)}
   {:inject-key inject-key}])

(rf/reg-event-fx ::do-work-with-async-stuff
  [;; Inject one single acofx, the global on-failure event applies.
   ;; Without a value it waits for the delay set in the input.
   (rf-acofxs/inject-acofx ::async-now)

   ;; Inject several acofxs, run concurrently.
   (rf-acofxs/inject-acofxs
    ;; With a value and a key of its own in the coeffects.
    [::async-now 5000 {:inject-key ::async-now-5-secs-delayed}]

    ;; With its own on-failure event, instead of the global one.
    [::github-repo-meta nil {:on-failure [::change-message "github failed"]}]

    ;; The same acofx twice, under different keys.
    (repo-meta-request "re-frame-tasks" ::re-frame-tasks-meta)
    (repo-meta-request "core.async-helpers" ::core.async-helpers-meta))

   ;; An ordinary cofx, as usual.
   (rf/inject-cofx ::now)]

  (fn [{:keys [db] :as cofxs} _]
    (let [async-computed-results
          (-> cofxs
              (update ::github-repo-meta :description)
              (update ::re-frame-tasks-meta :description)
              (update ::core.async-helpers-meta :description)
              (dissoc :db :event :original-event))]

      {:db
       (-> db
           (assoc ::async-computed-results async-computed-results)
           (assoc ::message nil))})))
```

### Keeping a task running (re-frame-tasks)

With [re-frame-tasks](https://github.com/jtkDvlp/re-frame-tasks), use the
injections from `jtk-dvlp.re-frame.async-coeffects.tasks` after `as-task`.
The task then keeps running while the acofxs run, `wait-for` holds other
events back until the handler ran, and a failure ends the task. Without a
task they behave like the plain ones.

```clojure
(ns your-project
  (:require
   [re-frame.core :as rf]
   [jtk-dvlp.re-frame.tasks :as tasks]
   [jtk-dvlp.re-frame.async-coeffects.tasks :as acofx-tasks]))

(rf/reg-event-fx ::init-my-view
  [(tasks/as-task :loading)
   (acofx-tasks/inject-acofx ::backend-resource {:uri "load some data"})]
  (fn [{:keys [db] ::keys [backend-resource]} _]
    {:db (assoc db ::data backend-resource)}))
```

Only this namespace needs re-frame-tasks (3.x) as dependency.

### Migrating from 2.x

**acofx handlers return the value, not the coeffects.** The handler still
gets the coeffects first, but the channel carries the value to inject.
Its second argument is the injection -- the options map with `:value`,
`:inject-key` and `:on-failure` -- instead of the arguments spread out.

```clojure
;; 2.x
(fn [coeffects delay-ms]
  (go (<! (timeout delay-ms)) (assoc coeffects ::now (js/Date.))))
;; 3.x
(fn [coeffects {delay-ms :value}]
  (go (<! (timeout delay-ms)) (js/Date.)))
```

**`reg-acofx-by-fx` takes a map.**

```clojure
;; 2.x
(reg-acofx-by-fx ::http :http-xhrio :on-success :on-failure {:method :get})
;; 3.x
(reg-acofx-by-fx ::http
  {:fx-id :http-xhrio
   :on-success-key :on-success
   :on-failure-key :on-failure
   :initial-args {:method :get}})
```

`:initial-args` may also be a function of the coeffects and the event. The
new `:on-failure-event` names the event to dispatch when the effect fails.

**Injection takes `[id value opts]` per acofx, like `inject-cofx`.**
Instead of spread arguments, the handler gets one value. The key in the
coeffects and the failure event are options of each acofx, not of the
whole injection. The map form of `inject-acofxs` is gone; `:inject-key`
replaces its keys.

```clojure
;; 2.x
(inject-acofxs {:a [::http {:uri "/a"}]
                :b [::http {:uri "/b"}]}
               {:error-dispatch [::failed]})
;; 3.x
(inject-acofxs
 [::http {:uri "/a"} {:inject-key :a, :on-failure [::failed]}]
 [::http {:uri "/b"} {:inject-key :b, :on-failure [::failed]}])

;; 2.x
(inject-acofx [::async-now 5000])
;; 3.x
(inject-acofx ::async-now 5000)
```

**Only `reg-acofx-by-fx` computes values.** In 2.x any injection argument
that was a function got called with the coeffects and the event. Now a
handler gets its value as given; it has the coeffects anyway.
`reg-acofx-by-fx` computes it: its `:initial-args` may be a function of the
coeffects and the event, and the injection value may be a function that
also gets the resolved `:initial-args` and returns the configuration to
use -- so it can derive from the registration, down to removing keys:

```clojure
(inject-acofx ::http (fn [_coeffects _event initial-args]
                       (update initial-args :uri str "/details")))
```

**`set-global-error-dispatch!` is now `set-global-on-failure-event`.** On
failure the event dispatched is the one the acofx handler put under
`::on-failure` into its exception, else the injection's `:on-failure`,
else the global one. The handler gets the injection, so it decides
whether the injection's event wins; `reg-acofx-by-fx` lets it win over
its `:on-failure-event`.

**The exception appended to the failure event is wrapped.** It is an
`ex-info` with `:code ::acofx-error` and the failed acofx under `::acofx`;
the handler's own exception is its cause. For `reg-acofx-by-fx`, what the
effect reported is under `:error` in the `ex-data` of that cause.

**The event is dispatched again as it was dispatched** (`:original-event`),
so interceptors like `trim-v` see it unchanged on every run.

**re-frame-tasks 3.x** is supported through
`jtk-dvlp.re-frame.async-coeffects.tasks`, see above. re-frame-tasks 2.x
keeps working with the plain injections.

## Development

The tests run under node:

```bash
lein with-profile +test,-dev run -m cljs.main --target node \
  --output-dir target/test --output-to target/test/main.js \
  --compile-opts '{:main jtk-dvlp.re-frame.test-runner}' \
  --compile jtk-dvlp.re-frame.test-runner
node target/test/main.js
```

They run on every push and pull request, see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml).

The demo app in [`dev/`](dev/jtk_dvlp/your_project.cljs) starts with
`lein repl` (figwheel-main, port 9801).

What has changed, and what is on the default branch but not released yet,
is in the release pull request and, from the first release on, in
`CHANGELOG.md`.

## Contributing

### Commit messages

Commit subjects follow [Conventional
Commits](https://www.conventionalcommits.org/en/v1.0.0/):

```
<type>[(<scope>)][!]: <description>
```

The `!` marks a breaking change and belongs to the type, not to
`feat` -- `fix!:` is just as valid and means a bug fix that breaks.

Pull requests are merged, not squashed, so every commit of a branch ends
up on `master` -- the convention applies to each of them. A CI job checks
this on every pull request. The pull request title itself is *not* a
conventional commit: it ends up in the merge commit, and release-please
would list it a second time.

The type decides the next version:

| Subject | Release |
|---|---|
| `fix: …` | patch -- `3.0.0` → `3.0.1` |
| `feat: …` | minor -- `3.0.0` → `3.1.0` |
| any type with a `!`, or a `BREAKING CHANGE:` footer | major -- `3.0.0` → `4.0.0` |
| `perf:`, `revert:`, `refactor:`, `docs:` | patch -- they reach the user; docstrings and this README are part of the artifact |
| `build:`, `chore:`, `ci:`, `style:`, `test:` | none, and no changelog entry |

### Releasing

Releasing is automatic; nobody edits a version number by hand.

1. A merge to `master` lets
   [release-please](https://github.com/googleapis/release-please) open or
   update a release pull request. It carries the next version in
   `project.clj` and the changelog entries derived from the commits since
   the last release.
2. Merging that pull request creates the git tag and the GitHub release.
3. The same workflow run then tests the tagged state and pushes the
   artifact to Clojars.

So the release pull request is the point where a release is decided --
until it is merged, nothing leaves the house.

## Appendix

I´d be thankful to receive patches, comments and constructive criticism.

Hope the package is useful :-)

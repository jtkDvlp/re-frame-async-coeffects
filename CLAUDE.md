@../claude-guidelines/CLAUDE.md

# re-frame-async-coeffects

The cross-project guidelines are imported above (sibling checkout of
`jtkDvlp/claude-guidelines`). This file only adds what is specific to this
library, and states where it deliberately deviates.

## What the library is

A re-frame extension that treats async input (HTTP, IPC, browser APIs) as a
**coeffect** of an event instead of a chain of effect → success event →
further event. Public API lives in one namespace,
`jtk-dvlp.re-frame.async-coeffects`:

| Function | Purpose |
|---|---|
| `reg-acofx` | Register an async coeffect handler under an id (registrar kind `:acofx`). The handler returns a channel. |
| `reg-acofx-by-fx` | Reuse an existing effect (e.g. `:http-xhrio`) as an acofx by hooking its success/failure event keys. |
| `inject-acofx`, `inject-acofxs` | Interceptors that run one or several acofxs concurrently and put the results into the event's coeffects. |

## Layout

| Path | Content |
|---|---|
| `src/jtk_dvlp/re_frame/async_coeffects.cljs` | The whole library. |
| `dev/jtk_dvlp/your_project.cljs` | Demo app, also referenced from the README as usage example. |
| `dev/user.clj` | REPL entry: `fig-init` starts figwheel-main, `cljs-repl` attaches. |
| `dev.cljs.edn` | figwheel-main build `dev`, served on port 9801, output to `target/`. |

**Deviation: the namespace root is `jtk-dvlp.…`, not `jtkdvlp.<artifact>`.**
It predates the guideline and is public API; renaming it breaks every user.
Only change it together with a major version, and only after an explicit
decision — not as a side effect of other work.

## Dependencies

All runtime dependencies (`re-frame`, `reagent`, `core.async`,
`jtk-dvlp/core.async-helpers`) are in the `:provided` profile on purpose: the
consuming app picks the versions, the library does not drag its own along.
Keep new runtime dependencies there too.

**WATCHOUT:** Use `jtk-dvlp.async` (`go`, `<!`, `reduce`, …) for everything
that takes from a channel. `cljs.core.async` is only for operations that do
not take values: `put!`, `close!`, `merge`, `timeout`. A plain
`core.async/<!` swallows an error value and the failure never reaches the
`on-failure` handling (see the guideline on core.async-helpers).

## Branches and versions

| Name | Meaning |
|---|---|
| `master` | Default branch, released 2.x API (`v2.0.0`). |
| `v3.0.0` | Rewrite of the internals and API towards 3.0.0 (`3.0.0-SNAPSHOT`). |
| Tags `v1.0.0`, `v1.0.1`, `v2.0.0` | Releases. Tags carry a `v` prefix. |

**WATCHOUT: README and demo describe the 2.x API** (`set-global-error-dispatch!`,
`:error-dispatch`, `reg-acofx-by-fx` with positional arguments). On the
`v3.0.0` line they must be updated together with the API, not afterwards.

## Release

| What | Value |
|---|---|
| Version carried by | `project.clj` (`defproject … "<version>"`) |
| Package repository | Clojars, `net.clojars.jtkdvlp/re-frame-async-coeffects` |
| Tag format | `v<version>` — `include-v-in-tag` must stay `true` |

Not set up yet: release-please, the publish workflow, the Clojars deploy
secrets and the publish command. Fill in this table once they exist.

## Tests

There are none yet. The only check so far is the demo app in `dev/`.

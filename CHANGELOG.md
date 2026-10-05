# Changelog

## [3.1.0](https://github.com/jtkDvlp/re-frame-async-coeffects/compare/v3.0.0...v3.1.0) (2026-10-05)


### Features

* accept acofxs keyed by a map in inject-acofxs ([e4c7812](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/e4c78128d006171fdcddb9d62b4e8f443f3e3694))


### Documentation

* show acofxs keyed by a map ([9837213](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/9837213056df0bc9bc80f0f426110de856cbc729))

## [3.0.0](https://github.com/jtkDvlp/re-frame-async-coeffects/compare/v2.0.0...v3.0.0) (2026-10-04)


### ⚠ BREAKING CHANGES

* acofx handlers yield the value to inject instead of the updated coeffects. `reg-acofx-by-fx` takes an options map instead of positional arguments. `inject-acofxs` takes one vector `[id value opts]` per acofx; its map form and the options of the whole injection are gone. `set-global-error-dispatch!` is replaced by `set-global-on-failure-event`, and `:error-dispatch` by `:on-failure`. Functions given at injection are no longer called by the library: a `reg-acofx` handler gets them unchanged as `:value`; only `reg-acofx-by-fx` calls one, with the coeffects, the event and the resolved `:initial-args`, and its result replaces the `:initial-args` instead of being merged over them. See "Migrating from 2.x" in the README.
* An acofx handler is called as `(handler coeffects injection)` instead of `(apply handler coeffects args)`; it reads its value under `:value` of the injection. An injection is `[id value opts]` per acofx, and `inject-acofx` takes `id value opts` instead of `[id & args]`. On failure, the event the handler names under `::on-failure` takes precedence over the injection's `:on-failure`.
* functions given at injection are no longer called for every acofx; only reg-acofx-by-fx computes them, and passes the resolved :initial-args as third argument.
* the exception appended to the failure event is an ex-info with :code ::acofx-error and the acofx under ::acofx; the handler's exception is its cause.

### Features

* add a global on-failure event ([7d648bc](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/7d648bc91166b2db9d0a497c0079cb2d0f337d45))
* add acofx normalization and run functions ([76887a1](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/76887a10b2ca7a45317b5ce39e67e5b896c995c2))
* add error handling and draft the injection ([0bcd380](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/0bcd380885d59c400038ed3171c2b91fe0478e36))
* add the acofx registrar api ([900d266](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/900d266aa44b7f1841ea9e88acaa0b371ad77dd5))
* implement inject-acofxs and complete the acofx error handling ([d966a98](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/d966a9861badaecda86de38aad4c6791bb410527))
* keep a re-frame-tasks task running while acofxs run ([6dd88e8](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/6dd88e83c9b825b23035303d7c196cd8aca3bf6e))
* let reg-acofx-by-fx compute its effect args ([265b4b6](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/265b4b6cd01017a35ed1925ac66585f2bc3b4b8f))
* make the tasks injections a single interceptor ([f1c6a4d](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/f1c6a4d501d3ea6ab84274c085ea858cb5e77854))
* pass the injection to acofx handlers, with one value ([c1166aa](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/c1166aa1d539d50a89383433ce2c7807c7fa1612))


### Refactoring

* clean up parked results before the last interceptor ([2342276](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/2342276d7e56f8660a98db5fe43d902b233ce48d))
* clear the implementation to rewrite it for 3.0 ([0228c8b](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/0228c8bac832bcdae25704bc4ae6f58a9472a57e))
* improve the acofx-by-fx names ([0810457](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/0810457410a434c55671d156c799fb9c8843e4ad))


### Documentation

* document migrating from 2.x and the tasks integration ([5bbe6c0](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/5bbe6c0717e71cfc951b69a0375f889989688963))
* link the vars in docstrings and clarify reg-acofx-by-fx ([c8ec134](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/c8ec134d4865aa74eae23c74120f72141b07720b))
* name every breaking change of 3.0 in the changelog ([24ae0c5](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/24ae0c5cd68ed29569022252010148580b48398e))
* restructure the README like the other libraries ([c3fd619](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/c3fd619e15fad78e5f1eeb7dba8f50d31e3e5c4a))
* update README and demo to the v3 API ([aeecde4](https://github.com/jtkDvlp/re-frame-async-coeffects/commit/aeecde46feaac5347b1f9c646da18025a0f7c31e))

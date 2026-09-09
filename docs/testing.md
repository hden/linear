# Testing

`bb test` runs the complete Kaocha suite. It includes Malli instrumentation,
so domain boundary contracts are checked while the behavioral tests run. Tests
that require PostgreSQL, native SQLite, or SlateDB are marked on each `deftest`
with `^:integration`; they remain part of the default suite rather than a
separate opt-in runner. To focus locally, use `bb test --test-focus namespace`.

`bb coverage` runs Cloverage through `clojure.test`. It measures the same test
sources but does not install the Kaocha Malli instrumentation hook. Use `bb
test` to validate instrumented contracts and `bb coverage` to inspect execution
coverage; neither command replaces the other.

Tests live beside the boundary they exercise. Handler tests assert transport
wire behavior, adapter tests exercise native and storage boundaries, and
use-case tests use `reify` capability implementations when a domain operation
must be isolated from infrastructure. Shared integration setup is in
`linear.test-data.{postgres,slatedb,sqlite}`. Its public helpers take argument
maps, generate incidental IDs, and return those IDs to callers.

Lifecycle tests synchronize with promises or latches. A test that blocks an
evaluation always releases it in `finally`, so a failed assertion cannot leave
native resources or futures blocked.

Run `bb architecture`, `bb architecture:test`, `bb lint`, `bb check`, `bb
format:check`, and `bb e2e` before handing off a broad change. `bb check`
currently reports the known Urania reflection warning; it remains visible as a
baseline warning rather than being suppressed.

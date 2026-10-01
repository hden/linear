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

`bb e2e` exercises the real Turso client over authenticated HTTP and provides
transport assurance that source-level coverage does not measure. It starts a
local RSA/JWKS fixture, configures the application with that issuer and
audience, and passes short-lived signed tokens to the JavaScript subprocess via
environment variables. The SDK supplies those tokens through its `authToken`
option. The scenario preserves the ordinary bootstrap, pull, push, concurrent
merge, constraint, and chunked-pull checks, and also verifies that a pull-only
actor can bootstrap but cannot push and an ungranted actor cannot bootstrap.
No external identity provider is required for this test.

E2E creates resources through HTTP, bootstraps an empty DB, and verifies
rename, closure, and recovery from a closed database.

Turso E2E also verifies lost-response retries, reconnect, and DDL rollback.
HTTP requests time out after 10 seconds; the client subprocess after 120 seconds.
See [Turso compatibility](turso-compatibility.md) for the supported scope.

Tests live beside the boundary they exercise. Handler tests assert transport
wire behavior, adapter tests exercise native and storage boundaries, and
use-case tests use `reify` capability implementations when a domain operation
must be isolated from infrastructure. Shared integration setup is in
`linear.test-data.{postgres,slatedb,sqlite}`. Parameterized public helpers take
argument maps, generate incidental IDs, and return those IDs to callers.
`sqlite-image` is a fixed fixture and intentionally has no arguments.

Tests that block work must release it and join any asynchronous tasks during
cleanup, so a failed assertion cannot leave resources or futures blocked.

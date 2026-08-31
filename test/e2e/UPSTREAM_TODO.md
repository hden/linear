# Turso upstream-derived test TODOs

These cases are tracked against the upstream Turso test suite. A TODO means
that the official client exercises the capability, but the official CLI sync
server also does not provide a reference implementation that this server can
copy yet.

- [ ] `bindings/python/tests/test_database_sync.py::test_partial_sync`
  - prefix/query bootstrap and `server_pages_selector`
  - prefix selector is implemented locally; query bootstrap remains TODO
- [ ] `bindings/javascript/sync/packages/native/promise.test.ts::partial sync`
  - partial-sync concurrency, segment size, and prefetch
- [x] `bindings/python/tests/test_database_sync.py::test_pull_bytes_threshold`
  - basic threshold bootstrap with repeated selector-based pulls is covered by
    the executable e2e test; remaining upstream edge cases are not yet copied
- [ ] `bindings/javascript/sync/packages/common/run.ts::wait_changes`
  - long-poll wake-up on a newly published revision
  - the upstream CLI server's page handler currently ignores
    `long_poll_timeout_ms`, so this remains an explicit compatibility TODO

The executable e2e suite in this directory covers the upstream-derived basic
bootstrap, pull, push, stale-client, merge, and conflict scenarios. It must
not mark the cases above as passed until their capability is implemented and
verified against the upstream client behavior.

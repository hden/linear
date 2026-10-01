# Turso sync compatibility

Compatibility is verified with the official `@tursodatabase/sync` JavaScript
SDK **0.8.1**. See [testing](testing.md) for the executable E2E scenarios.

| Feature | Linear support |
| --- | --- |
| Empty and chunked bootstrap | Supported |
| Incremental pull and local-write push | Supported |
| Concurrent clients and reopening a local client | Supported |
| Retry after a lost push response | Supported; applied progress prevents duplicate SQL execution |
| Atomic application data and sync progress | Supported; committed in the same revision |
| SQL failure | Entire batch rolls back; COMMIT reports `TRANSACTION_ROLLED_BACK` |
| Query partial sync and long polling | Unsupported |
| MVCC and experimental remote writes | Outside the verified compatibility scope |

## Limitations

- Verification covers JS SDK 0.8.1; other versions and language bindings are unverified.
- `/v2/pipeline` accepts one batch without a baton; generic Hrana streams are unsupported.
- Batch rollback differs from full Hrana per-step execution semantics. Older sync
  generations return a conflict; unsupported pull options return HTTP 400.
- Clients from earlier Linear versions need fresh local bootstrap because those
  versions discarded sync progress, which cannot be reconstructed.

## Upstream verification gaps

These upstream test identifiers track remaining implementation or verification
work; they are not passing claims for Linear:

| Upstream test | Remaining work |
| --- | --- |
| `bindings/python/tests/test_database_sync.py::test_partial_sync` | Query bootstrap; prefix selection via `server_pages_selector` is implemented locally. |
| `bindings/javascript/sync/packages/native/promise.test.ts::partial sync` | Partial-sync concurrency, segment size, and prefetch. |
| `bindings/python/tests/test_database_sync.py::test_pull_bytes_threshold` | Upstream edge cases beyond the basic repeated selector-based pulls covered by E2E. |
| `bindings/javascript/sync/packages/common/run.ts::wait_changes` | Long-poll wake-up on a newly published revision. |

The original upstream notes reported no official CLI sync server reference
implementation for the missing capabilities, and a page handler that ignored
`long_poll_timeout_ms`. Recheck those upstream observations when closing these gaps.

Mark a gap as covered only after implementing the capability and verifying
the corresponding client behavior.

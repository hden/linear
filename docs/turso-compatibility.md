# Turso sync compatibility

Compatibility is verified with the official `@tursodatabase/sync` JavaScript
SDK **0.8.3** using the page protocol. See [testing](testing.md) for the executable
E2E scenarios, run by [CI](../.github/workflows/ci.yml) on Linux x64.

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

- Verification covers JS SDK 0.8.3 on Linux x64; other versions, language bindings,
  and platforms are unverified by this CI run.
- `/v2/pipeline` accepts one batch without a baton; generic Hrana streams are unsupported.
- Batch rollback differs from full Hrana per-step execution semantics. Older sync
  generations return a conflict; unsupported pull options return HTTP 400.
- Clients from earlier Linear versions need fresh local bootstrap because those
  versions discarded sync progress, which cannot be reconstructed.

## SDK 0.8.3 update

The [upstream 0.8.2 to 0.8.3 comparison](https://github.com/tursodatabase/turso/compare/v0.8.2...v0.8.3)
includes a sync-engine fix for the order of MVCC logical schema replay. It does
not change the page-protocol wire contracts. Linear explicitly advertises the
page protocol; the SDK update does not establish MVCC compatibility.

## Upstream verification gaps

These references are pinned to upstream **v0.8.3**. They track remaining
implementation or verification work, not passing claims for Linear:

| Upstream reference | Remaining work |
| --- | --- |
| [Python `test_partial_sync`](https://github.com/tursodatabase/turso/blob/v0.8.3/bindings/python/tests/test_database_sync.py) | Query bootstrap; page selection via `server_pages_selector` is implemented locally. |
| [JavaScript partial-sync tests](https://github.com/tursodatabase/turso/blob/v0.8.3/bindings/javascript/sync/packages/native/promise.test.ts) | Partial-sync concurrency, segment size, and prefetch. |
| [Python `test_pull_bytes_threshold`](https://github.com/tursodatabase/turso/blob/v0.8.3/bindings/python/tests/test_database_sync.py) | Upstream edge cases beyond the repeated selector-based pulls covered by E2E. |
| [`PullUpdatesReqProtoBody.long_poll_timeout_ms`](https://github.com/tursodatabase/turso/blob/v0.8.3/sync/engine/src/server_proto.rs) | Long-poll wake-up on a newly published revision. |

Upstream includes a [CLI sync server](https://github.com/tursodatabase/turso/blob/v0.8.3/cli/sync_server.rs)
with page-selector support. Its JavaScript partial-sync tests skip that server
when `LOCAL_SYNC_SERVER` is set, so its presence does not close Linear's gaps.

Mark a gap as covered only after implementing the capability and verifying
the corresponding client behavior.

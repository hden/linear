# Turso sync compatibility

Compatibility is verified with the official `@tursodatabase/sync` JavaScript
SDK **0.8.1** by [the E2E test](../test/e2e/turso-sync.mjs).

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

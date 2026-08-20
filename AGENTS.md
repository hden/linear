Linear
---

1. Client
  * Turso Client
2. API
  * Control Domain
  * Sync Domain (Turso-compatible API)
3. Application
  * Crypto Shredding (Keychain)
  * Authz
    * WHO: OIDC / JWT
    * WHAT: Keychain
    * HOW: {push, pull, manage}
  * Compaction
  * PITR
  * GC
4. Storage
  * relational: PostgreSQL
  * pagestore: SlateDB

refs:

* https://duct-framework.org/docs/
* https://github.com/metosin/malli/tree/master
* https://github.com/tursodatabase/turso
  * https://github.com/tursodatabase/libsql/blob/main/docs/HRANA_3_SPEC.md
  * https://github.com/tursodatabase/libsql/blob/main/docs/HTTP_V2_SPEC.md

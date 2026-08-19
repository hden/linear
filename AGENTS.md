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

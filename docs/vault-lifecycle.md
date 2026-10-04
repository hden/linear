# Vault lifecycle

Deleting a vault removes its stored wrapped data encryption key. It retains the
vault ID, database identities, encrypted pages, and grants. Export and keep the
recovery token before deleting; the service cannot export it after deletion.

All endpoints require authentication and the actor's current grant:

| Method | Path under `/control/v1/vaults` | Permission | Response |
| --- | --- | --- | --- |
| GET | `/:id` | pull | 200 `{"id":"v-...","state":"active"}` or `"deleted"` |
| DELETE | `/:id` | manage | 204, including repeated deletion |
| GET | `/:id/recovery-token` | manage | 200 `{"token":"..."}`, `Cache-Control: no-store` |
| PUT | `/:id/recovery-token` | manage | Body `{"token":"..."}`; 204, including repeated restoration |

Tokens are opaque Base64url strings without padding. Version 1 encodes the CBOR
array `[1, vault-id, master-key-id, wrapped-key-bytes]`. No plaintext key appears
in the token. Restoration checks the envelope and authenticates the ciphertext
against the requested vault ID. Invalid, corrupted, unsupported-version, or
other-vault tokens return 400. Restoration requires the corresponding master
key to remain available through the injected key protection capability; unavailable keys
produce a server error. Production KMS integration and key rotation are outside
this change.

An active vault keeps its key on restoration, after validating the token.
Deletion and restoration serialize on the vault row; mixed concurrent requests
leave the state produced by the last committed operation. A retried vault
creation still returns its original IDs and does not restore keys or grants.

A deleted vault allows state retrieval and grant management. Recovery token
export, new data pulls, pushes, and sync metadata return 409. Operations that
already obtained the key may finish. Retaining encrypted data is deliberate;
this operation does not physically erase that data or copies of exported keys.

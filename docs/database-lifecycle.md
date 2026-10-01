# Database lifecycle

All routes require authentication. Permissions refer to the database's vault.

| Method | Path under `/control/v1` | Permission | Success |
| --- | --- | --- | --- |
| POST | `/vaults/:id/databases` | manage | 201 `{"id":"d-..."}`, Location header |
| GET | `/vaults/:id/databases?state=active` | pull | 200 `{"databases":[...]}` |
| GET | `/databases/:id` | pull | 200, including closed databases |
| PATCH | `/databases/:id` | manage | 204 |
| DELETE | `/databases/:id` | manage | 204, including repeated closure |

Resources contain `id`, `vault-id`, `display-name`, and `state` (`active` or
`closed`). Lists accept `state=active|closed|all`, default to active, and sort by ID.

Create and PATCH accept `{"display-name":"Primary"}`. Names must be nonempty
strings; unknown fields are rejected. Create requires an existing active vault
and an `Idempotency-Key` header, and returns an empty DB ready for Turso sync.
Successful replay by the same actor on the same vault returns the original ID,
even after closure or grant revocation. It never restores data or grants.
Reusing the key for another vault or resource operation returns 409.

For revision-based recovery, add `"source":{"database-id":"d-source","revision-id":"r-selected"}`
to the create body. Omit `revision-id` to copy HEAD. The source may be closed;
source pull and target manage permissions are required. Recovery creates a new
identity and root revision, reencrypted for the target vault. Both vault keys
must be available. Timestamp selection is not supported.

Rename preserves attribute history without changing HEAD. Closure is irreversible
and retains attributes and encrypted revisions for recovery. New sync operations
are rejected; already-resolved operations may finish. Metadata operations remain
available when vault keys are deleted; creation and recovery return 409.

Errors: 400 invalid input/filter, 403 permission denied, 404 missing resource/revision,
409 closed-state, deleted-key, or idempotency conflict. Initialization precedes
PostgreSQL commit; rollback can leave unreferenced initial storage. Physical deletion
and garbage collection are not provided.

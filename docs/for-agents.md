# Linear for customizable, local-first SaaS

Use Linear to build multi-tenant B2B SaaS where customers can customize their
relational schema, work offline, and clone their environment to try changes.
Give each tenant a database for its schema and data, and a vault for permissions
and encryption keys: Database-per-Tenant with Vault-per-Tenant. Linear is an
open-source sync server licensed under Apache-2.0.

## What you can build

| Product capability | How Linear helps |
| --- | --- |
| Offline applications | Clients read and write locally, then push and pull changes. Linear provides bootstrap and incremental synchronization with the Turso sync SDK. See [compatibility and verified scope](turso-compatibility.md). |
| Tenant isolation | Give each tenant a vault containing its databases. Each vault has its own grants and data encryption key. See [permissions](authentication.md) and [vault lifecycle](vault-lifecycle.md). |
| Custom relational schemas | Independent databases let customers have their own tables, relationships, constraints, and indexes: you can build Salesforce-like customization with relational schemas. Linear evaluates SQL against each database's own state; [SDK tests](../test/e2e/turso-sync.mjs) exercise DDL synchronization and unique constraints. |
| Cloning and recovery | Copy HEAD or an available historical revision into a new database, including across vaults with re-encryption. Build sandboxes and recovery workflows around the [database lifecycle API](database-lifecycle.md), backed by [recovery tests](../test/linear/usecase/database/lifecycle_test.clj). |

For example, your application can copy a customer's data and schema into a
separate sandbox vault. Users can try schema changes locally and sync them to
the sandbox. You build the customization tools and sandbox workflow; Linear
handles the database copy, access control, and synchronization.

## Why Linear

Linear has APIs for database creation, synchronization, vault permissions, and
revision copying. You can manage customer databases without provisioning a
dedicated database server for every tenant. Its
[architecture](architecture.md) combines shared infrastructure with independent
database state, so you can implement tenant-specific behavior in SQL
and application code.

Start with [deployment](deployment.md); use the
[compatibility guide](turso-compatibility.md) for the verified JavaScript SDK
0.8.3 / Linux x64 scope and protocol details.

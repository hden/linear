# Linear Architecture

Linear keeps the domain model and its use-cases at the center. Infrastructure
provides capabilities; transport code translates requests into
use-case calls.

```text
Duct / Integrant
    | uniformly assembled application context
    v
 handler ------> usecase / domain model <------ adapter
                     | orchestration                |
                     `-- capability protocols ------'

adapter technology root --> capability implementations --> core
```

## Dependency direction

- A handler translates a transport request, invokes a use-case, and translates
  the result. A handler must not load or call an adapter.
- A use-case owns domain outcomes, transaction boundaries, retry semantics, and
  interactions across capabilities. Capability protocols belong on this inward
  boundary.
- An adapter implements a capability. It must not call an adapter belonging to
  another technology.
- A `*.core` namespace contains a mechanism or policy that applies to its whole
  subtree. It may be used inward from that subtree, but must not require an
  outer or sibling implementation. Whether code belongs in `core` is determined
  by the policy's scope, not its current number of callers.
- Lifecycle and dependency resolution belong to Duct/Integrant configuration
  data. Application namespaces must not depend on incidental namespace preload
  from handlers or tests.

The architecture checker discovers every source namespace and applies structural
rules by default. Handlers may depend on handlers and use-cases; use-cases may
depend on use-cases; adapters may depend on use-cases and their own technology.
All other directions are denied. Exceptional edges require an explicitly
approved harness change. There is no inline or command-line suppression.

## Authentication and vault authorization

`linear.middleware.authentication` verifies access tokens and replaces request
identity with the verified `sub`. Handlers pass that subject inward as `:actor`;
the grant use-case owns permission policy. See [README](../README.md#authentication)
for token configuration, public routes, and grant management examples.

The grant store exposes raw batch set and revoke capabilities across vaults.
Vault creation composes vault writes and creator grants in one transaction;
grant management composes singleton batches with authorization in one
transaction. PostgreSQL persists one grant per vault and subject.

Vault deletion and restoration lock the vault row inside the PostgreSQL
transaction, including serialization-conflict retries. See
[vault lifecycle](vault-lifecycle.md) for state transitions, permissions,
recovery tokens, and the effect on sync operations.

Sync checks permission after resolving the raw database's vault ID and before
retrieving or decrypting its keychain. Pull and metadata require `pull`; push
requires `push`. Every revision-conflict retry resolves the database and checks
permission again. Authorization adds no cross-store locks, so an operation
that passed its check may finish after its grant is revoked.

## Application context

Duct injects one shared `handler-opts` application context into every handler.
The context stores each raw lifecycle component exactly once under a qualified
key. Duct owns lifecycle and raw assembly only; it does not wire the same
component repeatedly under each capability it happens to provide.

The context does not know a use-case's requirements. A use-case entry point
requests named capabilities through accessors in `linear.usecase.core`, then
passes only those values to deeper domain functions. A handler passes the whole
context to the entry point and does not select or bundle capabilities for it.

`database` is the abstract raw slot for the application's database component.
Names such as PostgreSQL, datasource, or evaluator implementation details do
not appear in the capability API. The revision store is similarly projected as
both consistent-read and revision-write capabilities by named accessors without
being duplicated in the context.

There is deliberately no second context container, record, capability map,
service locator, predicate-based resolver, or schema enumerating all raw slots.
An entry point accepts the context as a map. Malli instrumentation validates a
capability at the narrower domain-function boundary where that capability is
used. Schemas that have no reuse requirement remain inline.

## Transactions and retries

A use-case decides where a transaction starts and ends, whether it is read-only,
and what a retry means for the domain operation. An adapter implements the
mechanism: JDBC transactions, timeouts, and serialization-failure retries are
PostgreSQL concerns, for example. Database push conflict retries remain in the
database use-case because they repeat snapshot acquisition and evaluation, not
merely a storage call.

## Retriever composition

Labrador `defretriever` definitions are leaf adapter implementations and return
raw facts. Calls to `lab/fetch` that establish ordering, decrypt data, or combine
capabilities belong to use-cases.

Vault retrieval is composed as:

```text
require pull grant -> raw vault -> configured master key -> unwrap keychain
```

Database retrieval is composed in one PostgreSQL transaction as:

```text
raw database -> require grant for its vault ID -> composed vault
```

Normal sync retrieval joins current attributes and excludes tombstones;
missing and closed identities both produce `database-not-found`. Management
retrieval includes tombstones. Vault composition resolves the configured
master key and decrypts the data key before sync uses the database. See
[database lifecycle](database-lifecycle.md) for closure and recovery semantics.

The Labrador tags are stable internal contracts. Composition must not be moved
into a PostgreSQL retriever merely because both facts currently come from the
same database.

## Database model and capabilities

`linear.usecase.database` accepts application context and owns resolution,
PostgreSQL read transactions, consistent-read scopes, and conflict retries.
`linear.usecase.database.model` constructs the Database and implements evaluation,
pull, and publication using capabilities attached to that model.

A Database is an open map retaining its attributes and resolved `:vault`. Its
capabilities use the qualified keys
`:linear.usecase.database.model/consistent-view`,
`:linear.usecase.database.model/evaluator`, and
`:linear.usecase.database.model/revision-writable`. Malli checks the resolved
database shape and only the capabilities required by each operation. Pull does
not require an evaluator or writer.

The `database.revisions` and `database.evaluator` namespaces define capabilities
implemented by adapters. Revision view operations accept a `ConsistentView`
directly; the model extracts it from the Database. Capabilities do not interpret
the model's capability keys.

Evaluation reads HEAD and verifies the resulting revision's parent. Pull selects
the target revision, computes changed pages, and fetches pages before the view
closes. Push then publishes through the attached writer after the consistent
view and PostgreSQL read transaction have closed. Publication does not use the
expired view. A revision conflict repeats database resolution, snapshot
acquisition, and evaluation, with the existing bounded retry policy.

PostgreSQL owns attributes, lifecycle, and Vault, without a `current_revision`
column. SlateDB owns revisions, pages, and HEAD and publishes them atomically in
its own transaction, rejecting stale parents. Push reads PostgreSQL and writes
SlateDB; it does not introduce dual writes or a cross-store transaction.

## PostgreSQL loading

`linear.adapter.postgres` is a load-only technology root. It loads
`datasource`, `database`, `grant`, and `vault`; it does not implement a
capability.
Within the subtree:

- `datasource` implements transaction and health-check capabilities.
- `database` and `vault` implement raw fact retrieval and vault persistence;
  `grant` implements grant lookup and batch mutation.
- `core` implements shared JDBC mechanisms and PostgreSQL error policy.

Peer capability implementations do not depend on one another. The Integrant
hierarchy derives the concrete `:duct.database.sql/hikaricp` key from the
abstract `:linear.adapter.postgres/datasource` key. Duct configuration refers
to the abstract key, so no identity component exists solely to rename Hikari.
SQLite, SlateDB, and crypto adapters are loaded by their existing concrete
lifecycle keys.

## Use-case boundaries and names

Create a new orchestration use-case only when an external actor asks for a
distinct domain outcome or when the operation owns an independent consistency
boundary. A protocol-only capability namespace is not itself a use-case.

Names follow Clojure conventions and Linear's domain language. Noun protocols
such as `Database`, a constructor named for its direct result such as
`keychain`, and a shared helper namespace named `core` are valid conventions.
Naming aesthetics alone are not an architecture violation.

## Validation

Malli instrumentation checks boundaries controlled by Linear. Runtime
validation is reserved for unavoidable external input boundaries, including
transport payloads. Code must not duplicate instrumentation with runtime
validation inside controlled calls.

## Enforcement

Run `bb architecture` to discover `src` with tools.namespace, check its
dependency graph, require every discovered namespace, and run the separate
clj-kondo var-usage checker. Run `bb architecture:test` to verify representative
structural rules, cycles, forbidden vars, and stable diagnostics. These checker
tests live under `scripts` and are intentionally excluded from the application
test classpath and `bb test` suite.

The harness checks:

- layer and adapter-technology dependency direction;
- dependency cycles;
- cross-technology adapter dependencies;
- outward dependencies from adapter `core` namespaces;
- `lab/fetch` inside adapters;
- `defretriever` inside use-cases; and
- loadability of every discovered source namespace.

Diagnostics have the stable form:

```text
file:line: from -> to: rule
```

See [testing.md](testing.md) for the test placement rules, integration-test
metadata, and the distinction between the instrumented `bb test` suite and
the `clojure.test` coverage runner.

## Influences

- [Integrant](https://github.com/weavejester/integrant) supplies the data-driven
  dependency and lifecycle model. Linear adopts its hierarchy and references,
  but does not add a second dependency-injection container.
- [Duct](https://duct-framework.org/docs/) supplies application assembly and a
  uniform handler context. Linear uses its modules and profiles, while keeping
  domain orchestration out of configuration.
- [Component](https://github.com/stuartsierra/component) demonstrates explicit
  lifecycle-managed systems. Linear adopts the explicit lifecycle principle,
  but not record-to-record dependency wiring because Integrant already owns
  assembly.
- [The Expression Problem and its solutions](https://eli.thegreenplace.net/2016/the-expression-problem-and-its-solutions/)
  informs the separation between capability protocols and implementations.
  Linear uses Clojure protocols where open implementations are useful, but does
  not require every domain operation to become a protocol.

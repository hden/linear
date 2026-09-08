# Operable Database model

## Intent and constraints

Preserve existing uncommitted architecture work before completing the boundary
between database acquisition, capability injection, and domain operations.
PostgreSQL owns attributes, lifecycle, and Vault; current_revision stays removed.
SlateDB owns revisions, pages, and head in its own atomic transaction. Push reads
PostgreSQL and writes SlateDB without dual writes or a combined transaction.
Keep application context, Labrador tags, HTTP contracts, and adapter boundaries.
Do not add administrative acquisition, closure operations, or storage redesign.

## Recovery and commits

Starting main HEAD: 2a5cfee580d5684a972723a227fc81fc365a0586.
Work in the existing tree on refactor/operable-database-model. Preserve unrelated
branches and worktrees. Checkpoint: d414cfe, chore: checkpoint database architecture
work in progress. Keep this recovery point; subsequent stages get new commits.
Do not amend, push, merge into main, or squash history.

## A: Operable acquisition

User clarification: a database with current_attributes is not tombstoned.
Preserve the existing INNER JOIN on current_attributes as the operability
boundary; do not add a redundant database_tombstones exclusion query. Closed
database fixtures clear current_attributes and record the tombstone.
Missing and tombstoned targets produce existing database-not-found errors.
Keep database -> Vault -> configured master-key -> DEK composition in usecases,
including existing missing-key and decryption errors. No repeated domain checks.
Commit this behavioral change separately from structural changes.

## B: Model and operations

linear.usecase.database owns context entry points, Labrador resolution,
transactions, read scopes, and conflict retries. Move database.core construction
and accessors to linear.usecase.database.model and gather domain operations there.
Keep revisions and evaluator namespaces as adapter capability contracts.

Database remains an open map preserving attributes and resolved Vault. Associate
:linear.usecase.database.model/consistent-view, /evaluator, and /revision-writable.
model/database takes [attributes capabilities]; model/evaluate [database command]
uses HEAD and checks evaluator parent; model/pull [database options] selects
revisions, differences, and pages; model/publish-next! [database revision] uses
its attached writer. Public database/push! and database/pull remain unchanged.
revisions/head, as-of, changes-since accept ConsistentView only; capability code
must not inspect model structure.

## C: Scope and contracts

with-database attaches view, evaluator, and writer to resolved attributes/Vault.
Finish evaluation and pull page reads within the view lifetime. Publish after
both view and PostgreSQL read transaction close; publication must not use view.
Retry acquisition, snapshot, and evaluation on conflicts using existing limits
and error categories. Preserve SlateDB parent checks, atomic revision/page/head
updates, and encoding. Do not copy models merely to remove expired information.
Malli validates decrypted basic shape plus only capabilities each operation
requires. Allow extra keys; avoid redundant internal runtime validation.

## Acceptance and verification

Test normal/missing/tombstoned acquisition and Vault/KEK/DEK errors. Use reify
capabilities for model evaluation, pull, publication; never with-redefs. Verify
view lifetime, publication after release, fresh conflict retries, and immediate
non-conflict errors. Preserve actual SlateDB and PostgreSQL integration tests.
Run bb test, bb architecture, bb architecture:test, bb lint, bb check,
bb format:check, and bb e2e. Conditional skips are not passing coverage.
Review model contracts, implementation, tests, and docs/architecture.md together,
including absence of PostgreSQL current_revision and dual writes.

Use existing host Java 26 and native libraries, as requested during execution.
Default jenv Java is 21; explicitly select
/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home and prepend
/opt/homebrew/opt/openjdk/bin to PATH for checks and hooks. Existing
JAVA_TOOL_OPTIONS supplies repository and Homebrew SQLite library paths.
Do not disable checks or delete database volumes/data.

## Assignment

One gpt-5.6-terra implementer at a time owns source and corresponding tests for
A, then B/C. Workers must not revert others or delegate. Parent owns coordination,
plan and architecture documentation, and cross-stage verification. Independent
mid-tier review follows each stage; strongest-model review covers the branch.
Reports contain commit SHA, changes, executed checks, and unresolved findings.

## Execution record

- Acquisition: fcae541. Preserve the existing current-attributes join. An absent
  Labrador result now reaches database-not-found without entering Vault lookup.
- Model and scope: a8bb255. Model owns evaluation, pull, and attached-writer
  publication; view contracts no longer depend on model internals.
- The requested Terra worker completed A and began B/C tests, then reported its
  usage limit. The coordinator completed B/C; independent Sol reviews of A and
  B/C found no blocking issues.
- Host Java 26.0.2.1, existing repository SlateDB library and Homebrew SQLite.
  The native preflight asserted sqlite-available? so conditional SQLite tests
  were enabled. No database volumes or user data were removed.
- Instrumented focused verification: 13 tests, 73 assertions, zero failures or
  errors. Final bb test: 122 tests, 380 assertions, zero failures.
- bb architecture, bb architecture:test (4 tests/4 assertions), bb lint,
  bb check, bb format:check (73 files), and bb e2e all exited zero.
- bb check emitted one reflection warning in dependency urania/core.cljc:249;
  project namespace compilation completed. The E2E client emitted an npm update
  notice; no dependency installation or update was performed.
- The default shell selects Java 21 through jenv. Checks explicitly select Java
  26 as above. Git has no configured active hooks in this checkout; the Codex
  stop hook inherits its caller's environment and must receive the same Java
  selection. No check or hook was disabled.

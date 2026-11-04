# Linear

## Start here

- Before changing boundaries, read [architecture](docs/architecture.md), including source policies. Before changing tests, read [testing](docs/testing.md), including interruption requirements.
- Keep the domain model central; infrastructure provides capabilities. Backward compatibility is not required.
- Keep plans and specs local in `docs/plans/` and `docs/specs/`; do not commit them.

## Commands

See [bb.edn](bb.edn) for other tasks.

- Prepare: `docker compose build app`
- Run: `docker compose run --rm --service-ports app bb main`
- Focused tests: `docker compose run --rm app bb test --test-focus namespace`
- Static checks: `docker compose run --rm --no-deps lint bb lint`
- Full gate: `docker compose run --rm app bb verify`

Run relevant application tests for behavior changes. The Stop hook runs static lint only; CI runs the full gate.

## Conventions

- Prefer Clojure data and primitives. Use atoms/refs for coordinated state and Promesa for asynchronous work; convert external async APIs at boundaries.
- Use Malli instrumentation for controlled boundaries; reserve runtime validation for unavoidable external boundaries. Keep unreused schemas inline.
- Use `!` only for functions whose purpose is side effects, earmuffs only for dynamic Vars, and no trailing `*` names.
- Guarantee resource release with `with-open` or `try`/`finally`. Catch only exceptions requiring recovery or conversion; do not add `catch Throwable` for cleanup.
- Global Var replacement and monitor locking are forbidden. Test through capabilities or real adapters. Do not add tests for verification tooling, architecture checks, setup scripts, or hooks.

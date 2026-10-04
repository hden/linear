# Linear

Linear is an Apache-2.0 service for authenticated Turso SDK synchronization.
Release images are published at `ghcr.io/hden/linear` for `linux/amd64` and
`linux/arm64`. Pin a published version (for example, after the first release):

```sh
docker pull ghcr.io/hden/linear:0.1.0
```

See the [deployment guide](docs/deployment.md) to connect your own Auth0,
PostgreSQL, GCP KMS, and GCS resources. See [release maintenance](docs/releases.md)
for publishing images. The source is licensed under [Apache-2.0](LICENSE).

## Documentation

- [Architecture](docs/architecture.md): dependency direction and capability boundaries.
- [Testing](docs/testing.md): test commands, placement, and E2E scenarios.
- [Vault lifecycle](docs/vault-lifecycle.md): deletion and recovery token APIs.
- [Database lifecycle](docs/database-lifecycle.md): creation, rename, closure, and recovery APIs.
- [Turso compatibility](docs/turso-compatibility.md): supported SDK, sync scope, and limitations.

## Native libraries

The project selects Java 25 LTS through jenv using `.java-version`. On macOS,
install the versioned Homebrew JDK and register it with jenv:

```sh
brew install openjdk@25
jenv add "$(brew --prefix openjdk@25)/libexec/openjdk.jdk/Contents/Home"
```

The application loads SQLite (`sqlite3`) and SlateDB (`slatedb_uniffi`) by
their standard names from the JVM library path. Configure
`JAVA_TOOL_OPTIONS` before starting Clojure; the application does not read
library paths from Duct configuration.

On macOS, run the one-shot setup after fetching dependencies:

```sh
sh scripts/init.sh
```

Then copy `.env.example` to `.env` and run `direnv allow` in the repository.
The checked-in `.envrc` loads `.env` and configures `JAVA_TOOL_OPTIONS` with
the repository and SQLite library directories for host Clojure commands.

The development container provides both native libraries under
`/usr/local/lib` and sets the JVM library path in `Dockerfile`.

## Deployment target

One `duct.edn` and one Docker image serve every deployment target.
`LINEAR_DEPLOYMENT_TARGET` defaults to `dev`, which uses an ephemeral Tempel
KEK regenerated on startup. Set it to `gcp` to use GCP KMS. All other values,
including `aws`, fail at startup. JDBC and SlateDB remain independently
configured through their existing environment variables.

For local development, load `.env` with direnv and run:

```sh
docker compose up --build app
```

Compose forwards the deployment target, KMS key name, and OIDC settings.
Use the same Duct commands for configuration inspection and REPL work:

```sh
docker compose run --rm app clojure -M:duct --show --main
docker compose run --rm app bb repl
```

For persistent GCP deployments, follow the [deployment guide](docs/deployment.md).

## Authentication

Linear requires an Auth0 API access token for every vault and sync request.
Tokens must be signed with RS256 and contain the configured issuer, API
audience, expiration, and a nonempty `sub`. The subject identifies the actor.
The health endpoints remain public.

`OIDC_ISSUER` and `OIDC_AUDIENCE` are required. `OIDC_JWKS_URL` is an
optional trusted endpoint override:

```sh
OIDC_ISSUER="https://example.us.auth0.com/"
OIDC_AUDIENCE="https://linear.example.com"
# OIDC_JWKS_URL="https://example.us.auth0.com/.well-known/jwks.json"
```

Vault permissions are hierarchical: `pull < push < manage`. Creating a vault
atomically grants `manage` to the creator. A manager can list, set, or revoke
grants; the last manager may downgrade or revoke their own grant.

Grant subjects are URL-encoded path segments. For example, to give
`auth0|reader/email@example.com` pull access:

```sh
curl -X PUT \
  -H "Authorization: Bearer $LINEAR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"permission":"pull"}' \
  "$LINEAR_URL/control/v1/vaults/$VAULT_ID/grants/auth0%7Creader%2Femail%40example.com"

curl \
  -H "Authorization: Bearer $LINEAR_TOKEN" \
  "$LINEAR_URL/control/v1/vaults/$VAULT_ID/grants"

curl -X DELETE \
  -H "Authorization: Bearer $LINEAR_TOKEN" \
  "$LINEAR_URL/control/v1/vaults/$VAULT_ID/grants/auth0%7Creader%2Femail%40example.com"
```

Vault creation idempotency is scoped to the authenticated actor. Replaying a
successful creation returns the original vault ID without repeating writes or
restoring grants, even after that actor's grant is revoked. Reading vault data
still requires the actor's current grant. Grant management authorization and
each mutation run atomically in one PostgreSQL transaction.

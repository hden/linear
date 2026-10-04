# Linear

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
The checked-in `.envrc` loads `.env` and makes `JAVA_TOOL_OPTIONS` available to
Clojure commands. Include the repository directory for
`libslatedb_uniffi.dylib` and the Homebrew SQLite library directory in
`-Djava.library.path`.

The development container provides both native libraries under
`/usr/local/lib` and sets the JVM library path in `Dockerfile.dev`.

## Key encryption key

Local development and CI use an ephemeral Tempel KEK, regenerated on startup.
For GCP, provide a shared `ENCRYPT_DECRYPT` CryptoKey and
[ADC](https://docs.cloud.google.com/docs/authentication/application-default-credentials)
with `roles/cloudkms.cryptoKeyEncrypterDecrypter` on that key:

```sh
export GCP_KMS_KEY_NAME="projects/my-project/locations/global/keyRings/linear/cryptoKeys/kek"
docker compose build app
docker build -f Dockerfile.gcp -t linear-gcp .
docker run --rm --env-file .env -e GCP_KMS_KEY_NAME linear-gcp
```

Both configurations use `:linear.adapter.crypto/key-service`; its `:provider`
selects the implementation. `duct.edn` selects Tempel; `duct.gcp.edn` selects
GCP KMS. The service owns its KEK or remote client and provides separate key
generation and key protection capabilities. Both configurations include
shared variables and Web settings from `config/`. The GCP image installs
`duct.gcp.edn` as its `duct.edn`; no profile selects the KEK implementation.
Pass ADC credentials and the environment variables needed by the application
to the container. If the development image has a different tag, set
`--build-arg DEV_IMAGE=<tag>` when building the GCP image.

Use standard Duct commands inside either image:

```sh
docker run --rm --env-file .env -e GCP_KMS_KEY_NAME linear-gcp clojure -M:duct --show
docker run --rm -it --env-file .env -e GCP_KMS_KEY_NAME linear-gcp clojure -M:duct --repl
docker compose run --rm app clojure -M:duct --show
```

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

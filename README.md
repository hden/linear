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

## Authentication

For the supported SDK version, sync behavior, and retry guarantees, see
[Turso sync compatibility](docs/turso-compatibility.md).

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

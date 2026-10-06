# Authentication

Vault and sync requests require an RS256 bearer token with the configured issuer,
audience, expiration, and a nonempty `sub`. The subject identifies the actor.
Health endpoints are public.

`OIDC_ISSUER` and `OIDC_AUDIENCE` are required. `OIDC_JWKS_URL` is an
optional trusted endpoint override. See [`.env.example`](../.env.example).

## Vault permissions

Permissions are hierarchical: `pull < push < manage`. Vault creation grants
`manage` to the creator. Managers can list, set, or revoke grants, including
their own last `manage` grant.

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

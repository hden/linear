# Deployment

Image: `ghcr.io/hden/linear:<version>` ([releases](https://github.com/hden/linear/releases)).
Port: `3000`.

## Prerequisites

- An [Auth0 API](authentication.md).
- A PostgreSQL database and a user with migration permissions.
- A symmetric GCP KMS `ENCRYPT_DECRYPT` CryptoKey.
- A dedicated GCS bucket.

Enable the Cloud KMS and Cloud Storage APIs. Give the runtime service account
Cloud KMS CryptoKey Encrypter/Decrypter on the key and Storage Object Admin on
the bucket.

## Required settings

Set these environment variables:

```dotenv
LINEAR_DEPLOYMENT_TARGET=gcp
OIDC_ISSUER=https://YOUR_TENANT.auth0.com/
OIDC_AUDIENCE=https://linear.example.com
JDBC_DATABASE_URL=jdbc:postgresql://YOUR_POSTGRES:5432/linear?user=YOUR_USER&password=YOUR_PASSWORD
GCP_KMS_KEY_NAME=projects/YOUR_PROJECT/locations/REGION/keyRings/linear/cryptoKeys/kek
SLATEDB_OBJECT_STORE_URL=gs://YOUR_BUCKET
```

# Deployment

Use a published `ghcr.io/hden/linear:<version>` image for `linux/amd64` or
`linux/arm64`. The examples use `0.1.0`; choose an available Release version.
The service listens on port 3000. Provide your own Auth0 API, PostgreSQL,
GCP KMS CryptoKey, and GCS bucket.

## Required settings

| Variable | Value |
| --- | --- |
| `OIDC_ISSUER` | Auth0 issuer URL, including the trailing `/` |
| `OIDC_AUDIENCE` | Identifier of your Auth0 API |
| `JDBC_DATABASE_URL` | PostgreSQL JDBC URL with credentials |
| `LINEAR_DEPLOYMENT_TARGET` | `gcp` for persistent deployments |
| `GCP_KMS_KEY_NAME` | Full `projects/.../locations/.../keyRings/.../cryptoKeys/...` name |
| `SLATEDB_OBJECT_STORE_URL` | `gs://your-bucket` |
| `GOOGLE_APPLICATION_CREDENTIALS` | Credential file path inside the container when using a file |

`OIDC_JWKS_URL` optionally overrides the trusted JWKS endpoint. API access
tokens must use RS256 and include the configured issuer, audience, expiration,
and a nonempty `sub`. See [authentication](authentication.md).
`PORT` optionally changes the listening port.

Use a dedicated GCS bucket. A URL path such as `gs://bucket/prefix` does not
provide application-level prefix isolation; use the bucket-only URL. Database
IDs supply the object paths. Keep the same KMS key and its old decryptable key
versions across restarts. The default `dev` key is regenerated on startup,
and `memory:///` loses revision data; neither is suitable for persistent use.

## GCP preparation

Enable the Cloud KMS and Cloud Storage APIs in your project. Create a key ring
and a symmetric `ENCRYPT_DECRYPT` CryptoKey, and a dedicated Standard GCS
bucket with uniform bucket-level access and public access prevention. Use
`asia-northeast1` for both to reproduce the configuration exercised with the
existing SDK E2E scenario. Choose retention and backup policies for your own
requirements.

Give the runtime identity [Cloud KMS CryptoKey Encrypter/Decrypter](https://docs.cloud.google.com/kms/docs/reference/permissions-and-roles) on the
key and [Storage Object Admin](https://docs.cloud.google.com/storage/docs/access-control/iam-roles) on the bucket. Both the Java KMS client
and native SlateDB GCS client need credentials. The verified local setup uses
SDK Application Default Credentials (ADC):

```sh
gcloud auth application-default login --project=YOUR_PROJECT
```

Mount the resulting ADC JSON read-only as below. On GCP, an attached service
account can provide ADC without a file mount; that configuration was not part
of the local E2E verification. See Google's
[ADC documentation](https://docs.cloud.google.com/docs/authentication/application-default-credentials).

## Minimal Compose deployment

Save this as `compose.yaml` in a new directory. It connects to your existing
PostgreSQL service; the database and credentials must already exist. Startup
applies the application's SQL migrations, so the database user needs migration
permissions. Supply these values in a private `.env` file:

```dotenv
LINEAR_VERSION=0.1.0
OIDC_ISSUER=https://YOUR_TENANT.auth0.com/
OIDC_AUDIENCE=https://linear.example.com
JDBC_DATABASE_URL=jdbc:postgresql://YOUR_POSTGRES:5432/linear?user=YOUR_USER&password=YOUR_PASSWORD
GCP_KMS_KEY_NAME=projects/YOUR_PROJECT/locations/asia-northeast1/keyRings/linear/cryptoKeys/kek
SLATEDB_OBJECT_STORE_URL=gs://YOUR_BUCKET
ADC_FILE=/absolute/path/to/application_default_credentials.json
```

```yaml
services:
  linear:
    image: ghcr.io/hden/linear:${LINEAR_VERSION:?Set LINEAR_VERSION}
    init: true
    restart: unless-stopped
    ports:
      - "127.0.0.1:3000:3000"
    environment:
      LINEAR_DEPLOYMENT_TARGET: gcp
      OIDC_ISSUER: ${OIDC_ISSUER:?Set OIDC_ISSUER}
      OIDC_AUDIENCE: ${OIDC_AUDIENCE:?Set OIDC_AUDIENCE}
      JDBC_DATABASE_URL: ${JDBC_DATABASE_URL:?Set JDBC_DATABASE_URL}
      GCP_KMS_KEY_NAME: ${GCP_KMS_KEY_NAME:?Set GCP_KMS_KEY_NAME}
      SLATEDB_OBJECT_STORE_URL: ${SLATEDB_OBJECT_STORE_URL:?Set SLATEDB_OBJECT_STORE_URL}
      GOOGLE_APPLICATION_CREDENTIALS: /run/adc.json
    volumes:
      - type: bind
        source: ${ADC_FILE:?Set ADC_FILE}
        target: /run/adc.json
        read_only: true
```

```sh
docker compose pull
docker compose up -d
curl --fail http://localhost:3000/health/ok
curl --fail http://localhost:3000/health/ready
```

Both health routes are public and should return HTTP 200. Readiness checks
PostgreSQL and SQLite; it is not a complete KMS/GCS access check. Run the
[real-token E2E command](testing.md#real-token-e2e) against a disposable database
and bucket to exercise authenticated sync. Put TLS in front of the service
when exposing it beyond localhost.

Pin a version or manifest digest and back up PostgreSQL and GCS before an
upgrade. Preserve the KMS key versions needed to decrypt those backups.
Restart persistence with GCS and data compatibility between image versions
have not been verified. Health checks alone do not establish either guarantee.

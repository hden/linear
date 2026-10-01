import assert from "node:assert/strict";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { randomUUID } from "node:crypto";
import { connect } from "@tursodatabase/sync";

// Adapted from Turso upstream:
// bindings/python/tests/test_database_sync.py::test_bootstrap
// bindings/python/tests/test_database_sync.py::test_pull
// bindings/python/tests/test_database_sync.py::test_push
// bindings/python/tests/test_database_sync.py::test_pull_bytes_threshold

const baseUrl = process.argv[2];
assert(baseUrl, "usage: npm run e2e -- <linear-base-url>");
const managerToken = process.env.LINEAR_E2E_MANAGER_TOKEN;
const readerToken = process.env.LINEAR_E2E_READER_TOKEN;
const readerSubject = process.env.LINEAR_E2E_READER_SUBJECT;
const ungrantedToken = process.env.LINEAR_E2E_UNGRANTED_TOKEN;
assert(managerToken, "LINEAR_E2E_MANAGER_TOKEN is required");
assert(readerToken, "LINEAR_E2E_READER_TOKEN is required");
assert(readerSubject, "LINEAR_E2E_READER_SUBJECT is required");
assert(ungrantedToken, "LINEAR_E2E_UNGRANTED_TOKEN is required");

const clients = [];

const control = (method, path, body, idempotencyKey) => fetch(`${baseUrl}${path}`, {
  method,
  headers: {
    authorization: `Bearer ${managerToken}`,
    "content-type": "application/json",
    ...(idempotencyKey ? { "idempotency-key": idempotencyKey } : {})
  },
  ...(body ? { body: JSON.stringify(body) } : {})
});

const createdVault = await control("POST", "/control/v1/vaults", null, randomUUID());
assert.equal(createdVault.status, 201);
const vaultId = (await createdVault.json()).id;
const collection = `/control/v1/vaults/${vaultId}/databases`;
const createDatabase = async (body) => {
  const response = await control("POST", collection, body, randomUUID());
  assert.equal(response.status, 201, await response.clone().text());
  const { id } = await response.json();
  const location = new URL(response.headers.get("location"), baseUrl);
  assert.equal(location.origin, baseUrl);
  assert.equal(location.pathname, `/control/v1/databases/${id}`);
  return id;
};
const databaseId = await createDatabase({ "display-name": "E2E" });
const url = `${baseUrl}/d/${databaseId}`;
const granted = await control("PUT", `/control/v1/vaults/${vaultId}/grants/${encodeURIComponent(readerSubject)}`, { permission: "pull" });
assert.equal(granted.status, 204);
const root = await mkdtemp(join(tmpdir(), "linear-turso-e2e-"));

const rows = async (client, sql) =>
  (await client.prepare(sql)).all();

const connectClient = async (name, options = {}) => {
  const client = await connect({
    path: join(root, name),
    url,
    clientName: `linear-e2e-${name}`,
    authToken: managerToken,
    ...options
  });
  clients.push(client);
  return client;
};

try {
  const clientA = await connectClient("client-a");

  assert.deepEqual(await rows(clientA, "SELECT name FROM sqlite_schema WHERE name = 't'"), []);
  await clientA.exec("CREATE TABLE t (id INTEGER PRIMARY KEY, value TEXT)");
  await clientA.exec("INSERT INTO t (value) VALUES ('before-evaluator')");
  await clientA.push();

  assert.deepEqual(await rows(clientA, "SELECT value FROM t WHERE id = 1"), [
    { value: "before-evaluator" }
  ]);

  const clientB = await connectClient("client-b");

  await clientA.exec("UPDATE t SET value = 'from-js' WHERE id = 1");
  assert.deepEqual(await rows(clientB, "SELECT value FROM t WHERE id = 1"), [
    { value: "before-evaluator" }
  ]);

  await clientA.push();

  assert.equal(await clientB.pull(), true);
  assert.deepEqual(await rows(clientB, "SELECT value FROM t WHERE id = 1"), [
    { value: "from-js" }
  ]);

  const readerClient = await connectClient("reader", { authToken: readerToken });
  assert.deepEqual(await rows(readerClient, "SELECT value FROM t WHERE id = 1"), [
    { value: "from-js" }
  ]);
  await readerClient.exec("UPDATE t SET value = 'reader-local' WHERE id = 1");
  await assert.rejects(() => readerClient.push(), /403/);

  await assert.rejects(
    () => connectClient("ungranted", { authToken: ungrantedToken }),
    /403/
  );

  // Official sync test: select-without-push. Local writes stay local until
  // the client explicitly pushes them.
  await clientA.exec("UPDATE t SET value = 'local-only' WHERE id = 1");
  assert.deepEqual(await rows(clientA, "SELECT value FROM t WHERE id = 1"), [
    { value: "local-only" }
  ]);
  assert.deepEqual(await rows(clientB, "SELECT value FROM t WHERE id = 1"), [
    { value: "from-js" }
  ]);
  await clientA.push();
  await clientB.pull();

  // Official sync test: DDL and non-overlapping writes from two stale
  // clients are both preserved after push/pull.
  await clientA.exec("CREATE TABLE q (x TEXT PRIMARY KEY, y TEXT)");
  await clientA.push();
  await clientB.pull();

  const clientC = await connectClient("client-c");
  const clientD = await connectClient("client-d");
  await clientC.exec("INSERT INTO q VALUES ('k1', 'value1')");
  await clientD.exec("INSERT INTO q VALUES ('k2', 'value2')");
  await Promise.all([clientC.push(), clientD.push()]);
  await Promise.all([clientC.pull(), clientD.pull()]);

  const merged = [
    { x: "k1", y: "value1" },
    { x: "k2", y: "value2" }
  ];
  assert.deepEqual(await rows(clientC, "SELECT * FROM q ORDER BY x"), merged);
  assert.deepEqual(await rows(clientD, "SELECT * FROM q ORDER BY x"), merged);

  // Official sync test: a unique constraint conflict is reported when a
  // stale client's push is evaluated against the current server revision.
  await clientA.exec(
    "CREATE TABLE u (x TEXT PRIMARY KEY, y TEXT UNIQUE)"
  );
  await clientA.push();
  await clientB.pull();

  const clientE = await connectClient("client-e");
  const clientF = await connectClient("client-f");
  await clientE.exec("INSERT INTO u VALUES ('k1', 'same')");
  await clientF.exec("INSERT INTO u VALUES ('k2', 'same')");
  await clientE.push();
  await assert.rejects(() => clientF.push(), /UNIQUE constraint failed/);

  // Upstream-derived test_pull_bytes_threshold: a threshold causes bootstrap
  // to issue multiple pull-updates requests with page selectors.
  await clientA.exec("CREATE TABLE threshold (id INTEGER PRIMARY KEY, value BLOB)");
  await clientA.exec(
    "INSERT INTO threshold SELECT value, randomblob(1024) FROM generate_series(1, 50)"
  );
  await clientA.push();

  let thresholdPulls = 0;
  const countingFetch = async (...args) => {
    if (String(args[0]).endsWith("/pull-updates")) thresholdPulls += 1;
    return globalThis.fetch(...args);
  };
  const thresholdClient = await connectClient("threshold", {
    pullBytesThreshold: 8192,
    fetch: countingFetch
  });
  assert.deepEqual(await rows(thresholdClient, "SELECT COUNT(*) AS count FROM threshold"), [
    { count: 50 }
  ]);
  assert(thresholdPulls > 1, `expected chunked bootstrap, got ${thresholdPulls} pull`);

  const resourcePath = `/control/v1/databases/${databaseId}`;
  assert.equal((await control("PATCH", resourcePath, { "display-name": "Renamed" })).status, 204);
  assert.equal((await control("GET", resourcePath)).status, 200);
  for (let attempt = 0; attempt < 2; attempt += 1) {
    assert.equal((await control("DELETE", resourcePath)).status, 204);
  }
  const closed = await (await control("GET", resourcePath)).json();
  assert.equal(closed.state, "closed");
  assert.equal(closed["display-name"], "Renamed");
  const closedList = await (await control("GET", `${collection}?state=closed`)).json();
  assert(closedList.databases.some(database => database.id === databaseId));
  const pullAfterClose = await fetch(`${url}/pull-updates`, {
    method: "POST",
    headers: { authorization: `Bearer ${managerToken}` },
    body: new Uint8Array()
  });
  assert.equal(pullAfterClose.status, 400);
  const recoveredId = await createDatabase({ "display-name": "Recovered", source: { "database-id": databaseId } });
  assert.notEqual(recoveredId, databaseId);
  const recovered = await connectClient("recovered", { url: `${baseUrl}/d/${recoveredId}` });
  assert.deepEqual(await rows(recovered, "SELECT COUNT(*) AS count FROM threshold"), [{ count: 50 }]);
  assert.equal((await (await control("GET", resourcePath)).json()).state, "closed");
} finally {
  for (const client of clients.reverse()) {
    await client.close?.();
  }
  await rm(root, { recursive: true, force: true });
}

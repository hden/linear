import assert from "node:assert/strict";
import { join } from "node:path";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { connect } from "@tursodatabase/sync";

// Adapted from Turso upstream:
// bindings/python/tests/test_database_sync.py::test_bootstrap
// bindings/python/tests/test_database_sync.py::test_pull
// bindings/python/tests/test_database_sync.py::test_push
// bindings/python/tests/test_database_sync.py::test_pull_bytes_threshold

const url = process.argv[2];
assert(url, "usage: npm run e2e -- <linear-base-url>");

const root = await mkdtemp(join(tmpdir(), "linear-turso-e2e-"));
const clients = [];

const rows = async (client, sql) =>
  (await client.prepare(sql)).all();

const connectClient = async (name, options = {}) => {
  const client = await connect({
    path: join(root, name),
    url,
    clientName: `linear-e2e-${name}`,
    ...options
  });
  clients.push(client);
  return client;
};

try {
  const clientA = await connectClient("client-a");

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
} finally {
  for (const client of clients.reverse()) {
    await client.close?.();
  }
  await rm(root, { recursive: true, force: true });
}

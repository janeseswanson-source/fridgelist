// Kid paths with real server timestamps (field transforms), as the SDK sends them.
const ROOT = "http://127.0.0.1:8080/v1/projects/demo-fridgelist/databases/(default)/documents";
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const token = (uid) => `${b64({ alg: "none" })}.${b64({ user_id: uid, sub: uid, iat: 1, exp: 9999999999, aud: "demo-fridgelist", iss: "https://securetoken.google.com/demo-fridgelist" })}.`;
const DOCS = "projects/demo-fridgelist/databases/(default)/documents";
async function commit(uid, write) {
  const r = await fetch(`${ROOT}:commit`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token(uid)}` }, body: JSON.stringify({ writes: [write] }) });
  return r.status === 200 ? "ALLOW" : r.status === 403 ? "DENY" : `HTTP ${r.status}`;
}
const s = (v) => ({ stringValue: v });
const ts = (...f) => f.map((fieldPath) => ({ fieldPath, setToServerValue: "REQUEST_TIME" }));
const path = `${DOCS}/households/seed-home/lists/groceries/items`;
const run = Date.now().toString(36); // fresh IDs each run
const create = (uid, status, id) => ({
  update: { name: `${path}/${id}`, fields: { name: s("chips"), status: s(status), urgent: { booleanValue: false }, addedBy: s(uid), addedVia: s("app_text") } },
  currentDocument: { exists: false },
  updateTransforms: ts("createdAt", "updatedAt"),
});
const results = [
  ["kid creates a pending request", await commit("qa-kid", create("qa-kid", "pending", `qa-kid-req-${run}`)), "ALLOW"],
  ["kid creates an open item", await commit("qa-kid", create("qa-kid", "open", `qa-kid-open-${run}`)), "DENY"],
  ["adult creates an open item", await commit("seed-adult", create("seed-adult", "open", `qa-adult-open-${run}`)), "ALLOW"],
  ["kid ticks the adult's item done", await commit("qa-kid", { update: { name: `${path}/qa-adult-open-${run}`, fields: { status: s("done"), doneBy: s("qa-kid") } }, updateMask: { fieldPaths: ["status", "doneBy"] }, updateTransforms: ts("doneAt", "updatedAt") }), "ALLOW"],
  ["kid approves their own request (owner/adult only)", await commit("qa-kid", { update: { name: `${path}/qa-kid-req-${run}`, fields: { status: s("open") } }, updateMask: { fieldPaths: ["status"] }, updateTransforms: ts("updatedAt") }), "DENY"],
  ["helper declines (deletes) a request", await commit("seed-helper", { delete: `${path}/qa-kid-req-${run}` }), "DENY"],
  ["adult approves the request", await commit("seed-adult", { update: { name: `${path}/qa-kid-req-${run}`, fields: { status: s("open") } }, updateMask: { fieldPaths: ["status"] }, updateTransforms: ts("updatedAt") }), "ALLOW"],
  ["tick done as someone else (doneBy spoof)", await commit("seed-helper", { update: { name: `${path}/qa-kid-req-${run}`, fields: { status: s("done"), doneBy: s("seed-owner") } }, updateMask: { fieldPaths: ["status", "doneBy"] }, updateTransforms: ts("doneAt", "updatedAt") }), "DENY"],
];
let fail = 0;
for (const [label, got, want] of results) { const ok = got === want; if (!ok) fail++; console.log(`${ok ? "ok  " : "FAIL"} ${label.padEnd(52)} → ${got}`); }
console.log(`${results.length - fail}/${results.length}`);

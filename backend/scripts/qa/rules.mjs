// Firestore rules probe through the REST API (as a phone would, rules enforced), dev emulators.
const DB = "http://127.0.0.1:8080/v1/projects/demo-fridgelist/databases/(default)/documents";
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const token = (uid) =>
  `${b64({ alg: "none" })}.${b64({ user_id: uid, sub: uid, iat: 1, exp: 9999999999, aud: "demo-fridgelist", iss: "https://securetoken.google.com/demo-fridgelist", firebase: { sign_in_provider: "google.com" } })}.`;
const ADMIN = { Authorization: "Bearer owner" };

const val = (v) =>
  typeof v === "string" ? { stringValue: v } : typeof v === "boolean" ? { booleanValue: v } : typeof v === "number" ? { doubleValue: v } : v === "SERVER" ? { timestampValue: new Date().toISOString() } : v;
const fields = (o) => Object.fromEntries(Object.entries(o).map(([k, v]) => [k, v && v.timestampValue ? v : val(v)]));
const now = { timestampValue: new Date().toISOString() };

async function req(method, path, uid, body, mask) {
  const headers = { "Content-Type": "application/json" };
  if (uid === "ADMIN") Object.assign(headers, ADMIN);
  else if (uid) headers.Authorization = `Bearer ${token(uid)}`;
  const q = mask ? "?" + mask.map((m) => `updateMask.fieldPaths=${m}`).join("&") : "";
  const r = await fetch(`${DB}/${path}${q}`, { method, headers, body: body ? JSON.stringify({ fields: fields(body) }) : undefined });
  return r.status === 200 ? "ALLOW" : r.status === 403 ? "DENY" : `HTTP ${r.status}`;
}

let checks = 0;
let failures = 0;
async function expect(label, got, want) {
  checks++;
  const ok = (await got) === want;
  if (!ok) failures++;
  console.log(`${ok ? "ok  " : "FAIL"} ${label.padEnd(66)} → ${await got}`);
}

const HID = "seed-home";
const items = `households/${HID}/lists/groceries/items`;
const OWNER = "seed-owner";
const ADULT = "seed-adult";
const HELPER = "seed-helper";
const KID = "qa-kid"; // joined by authz.mjs
const STRANGER = "qa-stranger";

// A fresh item by the adult, to probe edits.
const created = await fetch(`${DB}/${items}`, {
  method: "POST",
  headers: { "Content-Type": "application/json", ...ADMIN },
  body: JSON.stringify({ fields: fields({ name: "probe", status: "open", urgent: false, addedBy: ADULT, addedVia: "app_text", createdAt: now, updatedAt: now }) }),
}).then((r) => r.json());
const itemId = created.name.split("/").pop();

console.log("— strangers see nothing");
await expect("stranger reads the household", req("GET", `households/${HID}`, STRANGER), "DENY");
await expect("stranger reads members", req("GET", `households/${HID}/members/${OWNER}`, STRANGER), "DENY");
await expect("stranger reads an item", req("GET", `${items}/${itemId}`, STRANGER), "DENY");
await expect("stranger adds an item", req("POST", items, STRANGER, { name: "x", status: "open", urgent: false, addedBy: STRANGER, addedVia: "app_text", createdAt: now, updatedAt: now }), "DENY");
await expect("stranger makes themselves a member", req("PATCH", `households/${HID}/members/${STRANGER}`, STRANGER, { role: "owner", displayName: "Eve", language: "en" }), "DENY");
await expect("stranger reads another user's profile", req("GET", `users/${OWNER}`, STRANGER), "DENY");
await expect("signed-out reads the catalog", req("GET", "catalog/milk", null), "DENY");
await expect("signed-in reads a store", req("GET", "stores/user_seed_tr_01", STRANGER), "ALLOW");

console.log("— server-only collections are closed to every client");
for (const path of ["shareLinks/x", "invites/ABCD2345", "rateLimits/x", "pushThrottle/x", "catalogCandidates/x", "whatsappNumbers/x", "whatsappLinkCodes/x"]) {
  await expect(`owner reads ${path}`, req("GET", path, OWNER), "DENY");
  await expect(`owner writes ${path}`, req("PATCH", path, OWNER, { a: "b" }), "DENY");
}
for (const sub of ["trips/t", "events/e", "purchases/p", "mealSuggestions/m", "usuals/u"]) {
  await expect(`owner writes households/…/${sub}`, req("PATCH", `households/${HID}/${sub}`, OWNER, { a: "b" }), "DENY");
}
await expect("owner writes a store", req("PATCH", "stores/user_seed_tr_01", OWNER, { name: "Mine" }, ["name"]), "DENY");
await expect("owner writes the catalog", req("PATCH", "catalog/milk", OWNER, { category: "other" }, ["category"]), "DENY");

console.log("— inside the household");
await expect("kid adds an open item (must be pending)", req("POST", items, KID, { name: "chips", status: "open", urgent: false, addedBy: KID, addedVia: "app_text", createdAt: now, updatedAt: now }), "DENY");
// Client timestamps are refused (rules require request.time); real kid paths: rules-server-time.mjs.
await expect("kid adds a pending item with a phone clock", req("POST", items, KID, { name: "chips", status: "pending", urgent: false, addedBy: KID, addedVia: "app_text", createdAt: now, updatedAt: now }), "DENY");
await expect("adult adds an item as someone else", req("POST", items, ADULT, { name: "x", status: "open", urgent: false, addedBy: OWNER, addedVia: "app_text", createdAt: now, updatedAt: now }), "DENY");
await expect("adult spoofs addedVia guest_web", req("POST", items, ADULT, { name: "x", status: "open", urgent: false, addedBy: ADULT, addedVia: "guest_web", createdAt: now, updatedAt: now }), "DENY");
await expect("adult sets the server category source", req("PATCH", `${items}/${itemId}`, ADULT, { canonicalId: "milk" }, ["canonicalId"]), "DENY");
await expect("helper renames the adult's item", req("PATCH", `${items}/${itemId}`, HELPER, { name: "renamed", updatedAt: now }, ["name", "updatedAt"]), "DENY");
await expect("kid ticks done with a phone clock", req("PATCH", `${items}/${itemId}`, KID, { status: "done", doneBy: KID, doneAt: now, updatedAt: now }, ["status", "doneBy", "doneAt", "updatedAt"]), "DENY");
// By design: requests are deleted by owner/adult, other items by any member (with undo in the app).
await expect("helper deletes the adult's (non-request) item", req("DELETE", `${items}/${itemId}`, HELPER), "ALLOW");
await expect("adult edits their own role to owner", req("PATCH", `households/${HID}/members/${ADULT}`, ADULT, { role: "owner" }, ["role"]), "DENY");
await expect("adult renames the household", req("PATCH", `households/${HID}`, ADULT, { name: "Mine" }, ["name"]), "DENY");
await expect("adult changes ownerUid", req("PATCH", `households/${HID}`, ADULT, { ownerUid: ADULT }, ["ownerUid"]), "DENY");
await expect("kid creates a list", req("POST", `households/${HID}/lists`, KID, { name: "kid list", type: "other", order: 9, archived: false, createdAt: now }), "DENY");
await expect("member reads usuals", req("GET", `households/${HID}/usuals/nothing`, HELPER), "HTTP 404");
await expect("member reads events", req("GET", `households/${HID}/events/nothing`, HELPER), "HTTP 404");
await expect("user writes another user's device", req("PATCH", `users/${OWNER}/devices/evil`, ADULT, { platform: "android", fcmToken: "x" }), "DENY");
await expect("user deletes own device (sign-out)", req("DELETE", `users/${ADULT}/devices/nothing`, ADULT), "ALLOW");

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);

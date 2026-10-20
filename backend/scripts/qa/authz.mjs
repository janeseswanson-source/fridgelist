// Authorization matrix against the dev emulators: every callable × caller × hostile input.
// Prints one line per check and a summary; exit code 1 when any check fails.
const FN = "http://127.0.0.1:5001/demo-fridgelist/me-central2";
const HID = "seed-home";
const LID = "groceries";
const b64 = (o) => Buffer.from(JSON.stringify(o)).toString("base64url");
const token = (uid) =>
  `${b64({ alg: "none" })}.${b64({ user_id: uid, sub: uid, iat: 1, exp: 9999999999, aud: "demo-fridgelist", iss: "https://securetoken.google.com/demo-fridgelist" })}.`;

async function call(name, data, uid) {
  const headers = { "Content-Type": "application/json" };
  if (uid) headers.Authorization = `Bearer ${token(uid)}`;
  const r = await fetch(`${FN}/${name}`, { method: "POST", headers, body: JSON.stringify({ data }) });
  const body = await r.json().catch(() => ({}));
  return body.error ? body.error.status : "OK";
}

let failures = 0;
let checks = 0;
async function expect(label, name, data, uid, allowed) {
  checks++;
  const got = await call(name, data, uid);
  const ok = Array.isArray(allowed) ? allowed.includes(got) : got === allowed;
  if (!ok) failures++;
  console.log(`${ok ? "ok  " : "FAIL"} ${label.padEnd(58)} → ${got}${ok ? "" : `   (expected ${allowed})`}`);
  return got;
}

const OWNER = "seed-owner";
const ADULT = "seed-adult";
const HELPER = "seed-helper";
const STRANGER = "qa-stranger";
const KID = "qa-kid";

// Set up a kid in the seed household and a stranger with their own household.
const invite = await (async () => {
  const r = await fetch(`${FN}/createInvite`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token(OWNER)}` }, body: JSON.stringify({ data: { householdId: HID, role: "kid" } }) });
  return (await r.json()).result;
})();
await call("joinHousehold", { code: invite.code, displayName: "QA Kid", language: "ar" }, KID);
await call("createHousehold", { name: "Stranger home", defaultLanguage: "en", displayName: "Eve", language: "en", uiLanguage: "en" }, STRANGER);

console.log("— no sign-in: everything but the public guest callables refuses");
for (const name of ["createHousehold", "createInvite", "joinHousehold", "parseItems", "transcribeAudio", "nearbyStores", "addStore", "startTrip", "updateTrip", "registerLiveActivityToken", "createShareLink", "revokeShareLink", "suggestMeals", "mealToItems", "exportMyData", "deleteMyAccount", "linkWhatsApp"]) {
  await expect(`anon ${name}`, name, { householdId: HID }, null, "UNAUTHENTICATED");
}
await expect("anon resolveShareLink (public) with a bad token", "resolveShareLink", { token: "A".repeat(24) }, null, "NOT_FOUND");

console.log("— a stranger can't touch another household");
const h = { householdId: HID };
await expect("stranger createInvite", "createInvite", { ...h, role: "adult" }, STRANGER, "PERMISSION_DENIED");
await expect("stranger parseItems", "parseItems", { ...h, text: "milk", localeHints: ["en"] }, STRANGER, "PERMISSION_DENIED");
await expect("stranger startTrip", "startTrip", { ...h, status: "heading", auto: false, platform: "android", deviceId: "d" }, STRANGER, "PERMISSION_DENIED");
await expect("stranger createShareLink", "createShareLink", { ...h, listId: LID, scope: "add", kind: "qr" }, STRANGER, "PERMISSION_DENIED");
await expect("stranger suggestMeals", "suggestMeals", { ...h, people: 4, occasion: "iftar", lang: "ar" }, STRANGER, "PERMISSION_DENIED");
await expect("stranger mealToItems", "mealToItems", { ...h, listId: LID, mealIds: ["x"], people: 4, lang: "ar" }, STRANGER, "PERMISSION_DENIED");
await expect("stranger revokeShareLink", "revokeShareLink", { ...h, token: "A".repeat(24) }, STRANGER, ["PERMISSION_DENIED", "NOT_FOUND"]);
await expect("stranger linkWhatsApp (flag off first)", "linkWhatsApp", h, STRANGER, ["FAILED_PRECONDITION", "PERMISSION_DENIED"]);

console.log("— roles inside the household");
await expect("kid createInvite", "createInvite", { ...h, role: "kid" }, KID, "PERMISSION_DENIED");
await expect("helper createInvite", "createInvite", { ...h, role: "kid" }, HELPER, "PERMISSION_DENIED");
await expect("adult createInvite owner role (escalation)", "createInvite", { ...h, role: "owner" }, ADULT, "INVALID_ARGUMENT");
await expect("kid startTrip", "startTrip", { ...h, status: "heading", auto: false, platform: "android", deviceId: "d" }, KID, "PERMISSION_DENIED");
await expect("kid createShareLink", "createShareLink", { ...h, listId: LID, scope: "add", kind: "qr" }, KID, "PERMISSION_DENIED");
await expect("helper createShareLink", "createShareLink", { ...h, listId: LID, scope: "add", kind: "qr" }, HELPER, "PERMISSION_DENIED");
const trip = await (async () => {
  const r = await fetch(`${FN}/startTrip`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token(OWNER)}` }, body: JSON.stringify({ data: { ...h, status: "heading", auto: false, listIds: [LID], platform: "android", deviceId: "qa-dev" } }) });
  return (await r.json()).result;
})();
await expect("adult updateTrip on the owner's trip", "updateTrip", { ...h, tripId: trip.tripId, status: "done" }, ADULT, "PERMISSION_DENIED");
await expect("owner updateTrip own trip", "updateTrip", { ...h, tripId: trip.tripId, status: "cancelled" }, OWNER, "OK");
await expect("join with an expired/unknown code", "joinHousehold", { code: "ABCD2345", displayName: "x", language: "en" }, STRANGER, ["NOT_FOUND", "FAILED_PRECONDITION"]);
await expect("rejoin own household (kid re-using the code)", "joinHousehold", { code: invite.code, displayName: "QA Kid", language: "ar" }, KID, ["OK", "ALREADY_EXISTS", "FAILED_PRECONDITION"]);

console.log("— hostile input");
await expect("parseItems 501 chars", "parseItems", { ...h, text: "x".repeat(501), localeHints: ["en"] }, OWNER, "INVALID_ARGUMENT");
await expect("parseItems householdId object", "parseItems", { householdId: { $gt: "" }, text: "milk", localeHints: ["en"] }, OWNER, "INVALID_ARGUMENT");
await expect("parseItems householdId path traversal", "parseItems", { householdId: "../users/seed-owner", text: "milk", localeHints: ["en"] }, OWNER, ["INVALID_ARGUMENT", "PERMISSION_DENIED"]);
await expect("parseItems householdId with slash", "parseItems", { householdId: "seed-home/lists/groceries", text: "milk", localeHints: ["en"] }, OWNER, ["INVALID_ARGUMENT", "PERMISSION_DENIED"]);
await expect("suggestMeals people 31", "suggestMeals", { ...h, people: 31, occasion: "iftar", lang: "ar" }, OWNER, "INVALID_ARGUMENT");
await expect("suggestMeals people 0", "suggestMeals", { ...h, people: 0, occasion: "iftar", lang: "ar" }, OWNER, "INVALID_ARGUMENT");
await expect("suggestMeals unknown occasion", "suggestMeals", { ...h, people: 4, occasion: "party", lang: "ar" }, OWNER, "INVALID_ARGUMENT");
await expect("nearbyStores lat 999", "nearbyStores", { lat: 999, lng: 0 }, OWNER, "INVALID_ARGUMENT");
await expect("nearbyStores radius 1e9", "nearbyStores", { lat: 25, lng: 55, radiusM: 1e9 }, OWNER, "INVALID_ARGUMENT");
await expect("addStore empty name", "addStore", { name: "", lat: 25, lng: 55 }, OWNER, "INVALID_ARGUMENT");
await expect("addStore 10k-char name", "addStore", { name: "x".repeat(10000), lat: 25, lng: 55 }, OWNER, "INVALID_ARGUMENT");
await expect("addStore outside TR/SA/AE", "addStore", { name: "Shop", lat: 48.85, lng: 2.35 }, OWNER, ["INVALID_ARGUMENT", "FAILED_PRECONDITION"]);
await expect("createShareLink unknown list", "createShareLink", { ...h, listId: "nope", scope: "add", kind: "qr" }, OWNER, "NOT_FOUND");
await expect("createShareLink expiresInDays 10000", "createShareLink", { ...h, listId: LID, scope: "add", kind: "qr", expiresInDays: 10000 }, OWNER, "INVALID_ARGUMENT");
await expect("guestAddItem bad token", "guestAddItem", { token: "B".repeat(24), text: "milk" }, null, "NOT_FOUND");
await expect("guestAddItem 24-char token with slash", "guestAddItem", { token: "AAAAAAAAAAAA/AAAAAAAAAAA", text: "milk" }, null, "INVALID_ARGUMENT");
await expect("transcribeAudio 2 MB", "transcribeAudio", { audioBase64: "A".repeat(2_000_000), mimeType: "audio/ogg", localeHints: ["ar"] }, OWNER, ["INVALID_ARGUMENT"]);
await expect("transcribeAudio wrong mime", "transcribeAudio", { audioBase64: "AAAA", mimeType: "text/html", localeHints: ["ar"] }, OWNER, "INVALID_ARGUMENT");
await expect("deleteMyAccount transfer to a kid", "deleteMyAccount", { transferOwnershipTo: KID }, OWNER, "FAILED_PRECONDITION");
await expect("deleteMyAccount transfer to a stranger", "deleteMyAccount", { transferOwnershipTo: STRANGER }, OWNER, "FAILED_PRECONDITION");

// Guest link abuse: add-scope link, URL stripping, rate limit.
const link = await (async () => {
  const r = await fetch(`${FN}/createShareLink`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token(OWNER)}` }, body: JSON.stringify({ data: { ...h, listId: LID, scope: "add", kind: "qr" } }) });
  return (await r.json()).result;
})();
console.log("— guest link abuse");
await expect("guest text only a URL", "guestAddItem", { token: link.token, text: "https://evil.example/x" }, null, "INVALID_ARGUMENT");
await expect("guest HTML/script text", "guestAddItem", { token: link.token, text: "<img src=x onerror=alert(1)>" }, null, ["OK", "INVALID_ARGUMENT"]);
await expect("guest name 31 chars", "guestAddItem", { token: link.token, text: "milk", guestName: "x".repeat(31) }, null, "INVALID_ARGUMENT");
let limited = "OK";
for (let i = 0; i < 25 && limited === "OK"; i++) limited = await call("guestAddItem", { token: link.token, text: `item ${i}` }, null);
checks++;
const rlOk = limited === "RESOURCE_EXHAUSTED";
if (!rlOk) failures++;
console.log(`${rlOk ? "ok  " : "FAIL"} ${"guest adds stop at 20/hour".padEnd(58)} → ${limited}`);
await expect("adult revokes", "revokeShareLink", { ...h, token: link.token }, ADULT, "OK");
await expect("revoked link resolves", "resolveShareLink", { token: link.token }, null, "NOT_FOUND");
await expect("revoked link adds", "guestAddItem", { token: link.token, text: "milk" }, null, "NOT_FOUND");

console.log(`\n${checks - failures}/${checks} checks passed`);
process.exit(failures ? 1 : 0);

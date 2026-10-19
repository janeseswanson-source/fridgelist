import { getAuth } from "firebase-admin/auth";
import {
  FieldPath,
  Timestamp,
  getFirestore,
  type DocumentData,
  type Firestore,
} from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { EXPORT_PREFIX, StorageExportStore, type ExportStore } from "../account/exportStore.js";
import { log } from "../lib/log.js";
import { consume, LIMITS } from "../lib/rateLimit.js";
import { parse, requireUid } from "../lib/requests.js";
import { defaultSender, type PushSender } from "../push/sender.js";
import { endTrip } from "../trips/endTrip.js";
import { ACTIVE } from "../trips/live.js";

export const EXPORT_VALID_MS = 24 * 60 * 60 * 1000;

/** Rate-limit buckets keyed by user (suggestMeals is keyed by household). */
// suggestMeals is per household and whatsapp per hashed number; the rest are per user.
const USER_BUCKETS = (Object.keys(LIMITS) as (keyof typeof LIMITS)[]).filter(
  (b) => b !== "suggestMeals" && b !== "whatsapp",
);

/** Timestamps → ISO strings so the export is plain, readable JSON. */
function plain(value: unknown): unknown {
  if (value instanceof Timestamp) return value.toDate().toISOString();
  if (Array.isArray(value)) return value.map(plain);
  if (value && typeof value === "object")
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, plain(v)]));
  return value;
}

function without(data: DocumentData, keys: readonly string[]): DocumentData {
  return Object.fromEntries(Object.entries(data).filter(([k]) => !keys.includes(k)));
}

async function householdIdsOf(db: Firestore, uid: string): Promise<string[]> {
  const ids: unknown = (await db.doc(`users/${uid}`).get()).get("householdIds");
  return Array.isArray(ids) ? ids.filter((x): x is string => typeof x === "string") : [];
}

/**
 * Everything personal to the caller: profile, devices, memberships, and in each household the
 * items they added, aliases they created, their purchases, trips and events. Other members'
 * data is not included.
 */
export async function collectExport(db: Firestore, uid: string, now: Date): Promise<Record<string, unknown>> {
  const [user, devices] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.collection(`users/${uid}/devices`).get(),
  ]);
  const households: Record<string, unknown>[] = [];
  for (const hid of await householdIdsOf(db, uid)) {
    const h = db.doc(`households/${hid}`);
    const [household, member, lists, aliases, purchases, trips, events] = await Promise.all([
      h.get(),
      h.collection("members").doc(uid).get(),
      h.collection("lists").get(),
      h.collection("aliases").where("createdBy", "==", uid).get(),
      h.collection("purchases").where("by", "==", uid).get(),
      h.collection("trips").where("shopperUid", "==", uid).get(),
      h.collection("events").where("actorUid", "==", uid).get(),
    ]);
    if (!member.exists) continue;
    const items: DocumentData[] = [];
    for (const list of lists.docs) {
      const mine = await list.ref.collection("items").where("addedBy", "==", uid).get();
      items.push(...mine.docs.map((d) => ({ listName: list.get("name") as unknown, ...d.data() })));
    }
    const data = (snap: { docs: { data(): DocumentData }[] }) => snap.docs.map((d) => d.data());
    households.push({
      householdName: household.get("name") as unknown,
      membership: member.data(),
      itemsYouAdded: items,
      aliasesYouCreated: data(aliases),
      yourPurchases: data(purchases),
      yourTrips: trips.docs.map((d) => without(d.data(), ["liveActivityToken"])),
      yourActivity: data(events),
    });
  }
  return plain({
    exportedAt: now,
    uid,
    profile: user.data() ?? null,
    // Push tokens are credentials for our sender, not the user's data: platform and locale only.
    devices: devices.docs.map((d) => ({
      deviceId: d.id,
      ...without(d.data(), ["fcmToken", "apnsPushToStartToken"]),
    })),
    households,
  }) as Record<string, unknown>;
}

/** CONTRACT §4 `exportMyData`: `{ url }`, a signed URL valid 24 h. 3 exports per day. */
export async function exportMyDataCore(
  db: Firestore,
  uid: string,
  store: ExportStore,
  now: Date = new Date(),
): Promise<{ url: string }> {
  await consume(db, "exportMyData", uid, now);
  const json = JSON.stringify(await collectExport(db, uid, now), null, 2);
  const url = await store.save(`${EXPORT_PREFIX}${uid}/${String(now.getTime())}.json`, json, EXPORT_VALID_MS);
  log.info("data_exported", { bytes: json.length });
  return { url };
}

export const DeleteMyAccountRequest = z.object({
  transferOwnershipTo: z.string().min(1).max(128).optional(),
});

interface Plan {
  householdId: string;
  action: "delete_household" | "transfer_and_leave" | "leave";
  newOwner?: string;
}

/**
 * CONTRACT §4 `deleteMyAccount`. Validates every household first and changes nothing when one
 * can't be resolved: an owner with other members must name `transferOwnershipTo` (a member there,
 * not a kid); a last member's household is deleted. Then: active trips end, membership goes, the
 * name is removed from events and trips, the user's own docs go, and the Auth user last.
 */
export async function deleteMyAccountCore(
  db: Firestore,
  uid: string,
  data: unknown,
  sender: PushSender,
  deleteAuthUser: (uid: string) => Promise<void>,
  exports: Pick<ExportStore, "deleteOlderThan"> | null,
  now: Date = new Date(),
): Promise<{ ok: true }> {
  const input = parse(DeleteMyAccountRequest, data);
  const plans: Plan[] = [];
  for (const hid of await householdIdsOf(db, uid)) {
    const members = await db.collection(`households/${hid}/members`).get();
    const me = members.docs.find((d) => d.id === uid);
    if (!me) continue;
    const others = members.docs.filter((d) => d.id !== uid);
    if (others.length === 0) plans.push({ householdId: hid, action: "delete_household" });
    else if (me.get("role") !== "owner") plans.push({ householdId: hid, action: "leave" });
    else {
      const target = others.find((d) => d.id === input.transferOwnershipTo);
      if (!target || target.get("role") === "kid") {
        throw new HttpsError(
          "failed-precondition",
          "Choose who takes over the household (transferOwnershipTo).",
        );
      }
      plans.push({ householdId: hid, action: "transfer_and_leave", newOwner: target.id });
    }
  }

  for (const plan of plans) {
    const h = db.doc(`households/${plan.householdId}`);
    const active = await h
      .collection("trips")
      .where("shopperUid", "==", uid)
      .where("status", "in", [...ACTIVE])
      .get();
    for (const trip of active.docs) await endTrip(db, sender, plan.householdId, trip.id, "cancelled", now);

    if (plan.action === "delete_household") {
      const [links, invites] = await Promise.all([
        db.collection("shareLinks").where("householdId", "==", plan.householdId).get(),
        db.collection("invites").where("householdId", "==", plan.householdId).get(),
      ]);
      await Promise.all([...links.docs, ...invites.docs].map((d) => d.ref.delete()));
      await db.recursiveDelete(h);
      continue;
    }
    if (plan.newOwner) {
      const batch = db.batch();
      batch.update(h, { ownerUid: plan.newOwner });
      batch.update(h.collection("members").doc(plan.newOwner), { role: "owner" });
      await batch.commit();
    }
    await h.collection("members").doc(uid).delete(); // onMemberWritten updates memberLanguages
    const [events, trips] = await Promise.all([
      h.collection("events").where("actorUid", "==", uid).get(),
      h.collection("trips").where("shopperUid", "==", uid).get(),
    ]);
    await Promise.all([
      ...events.docs.map((d) => d.ref.update({ actorName: "" })),
      ...trips.docs.map((d) => d.ref.update({ shopperName: "" })),
    ]);
  }

  await db.recursiveDelete(db.doc(`users/${uid}`));
  // Per-user counters (`rateLimits/{bucket}:{uid}`) and push throttles (`pushThrottle/{uid}_{device}`).
  const throttles = await db
    .collection("pushThrottle")
    .where(FieldPath.documentId(), ">=", `${uid}_`)
    .where(FieldPath.documentId(), "<", `${uid}_~`)
    .get();
  // Phase 2: the WhatsApp link (hashed number) and any unused link codes.
  const [waNumbers, waCodes] = await Promise.all([
    db.collection("whatsappNumbers").where("uid", "==", uid).get(),
    db.collection("whatsappLinkCodes").where("uid", "==", uid).get(),
  ]);
  await Promise.all([
    ...USER_BUCKETS.map((b) => db.doc(`rateLimits/${b}:${uid}`).delete()),
    ...throttles.docs.map((d) => d.ref.delete()),
    ...waNumbers.docs.map((d) => d.ref.delete()),
    ...waCodes.docs.map((d) => d.ref.delete()),
  ]);
  if (exports) await exports.deleteOlderThan(`${EXPORT_PREFIX}${uid}/`, 0);
  await deleteAuthUser(uid);
  log.info("account_deleted", {
    households: plans.length,
    deletedHouseholds: plans.filter((p) => p.action === "delete_household").length,
    transferred: plans.filter((p) => p.action === "transfer_and_leave").length,
  });
  return { ok: true };
}

export const exportMyData = onCall((request) =>
  exportMyDataCore(getFirestore(), requireUid(request), new StorageExportStore()),
);
export const deleteMyAccount = onCall({ timeoutSeconds: 300 }, (request) =>
  deleteMyAccountCore(
    getFirestore(),
    requireUid(request),
    request.data,
    defaultSender(getFirestore()),
    (uid) => getAuth().deleteUser(uid),
    new StorageExportStore(),
  ),
);

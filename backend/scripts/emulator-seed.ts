/**
 * Seeds the local Emulator Suite (never a real project) with one household, two lists, 15 items
 * in mixed languages and ~30 stores around Kadıköy, Olaya and Dubai Marina (prompts/01-backend.md B1).
 * Fixed IDs, so re-running overwrites instead of duplicating.
 *
 * The three members are linked to the Android dev "test user" sign-in (fake Google token with
 * `sub = test-<slug>`), so signing in as "Mama", "Ayse" or "Maria" on a dev build lands in this
 * household.
 */
import { initializeApp } from "firebase-admin/app";
import { getAuth } from "firebase-admin/auth";
import { FieldValue, Timestamp, getFirestore } from "firebase-admin/firestore";
import { geohashForLocation } from "geofire-common";

const PROJECT = "demo-fridgelist";
process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= "127.0.0.1:9099";
if (!PROJECT.startsWith("demo-")) throw new Error("The seed only ever runs against a demo- project.");

initializeApp({ projectId: PROJECT });
const db = getFirestore();
const now = FieldValue.serverTimestamp();
const NOTIFY = { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false };

const HID = "seed-home";
const members = [
  { uid: "seed-owner", slug: "mama", displayName: "Mama", role: "owner", language: "ar", uiLanguage: "ar" },
  { uid: "seed-adult", slug: "ayse", displayName: "Ayşe", role: "adult", language: "tr", uiLanguage: "tr" },
  { uid: "seed-helper", slug: "maria", displayName: "Maria", role: "helper", language: "fil", uiLanguage: "en" },
] as const;

async function seedAuth(): Promise<void> {
  const result = await getAuth().importUsers(
    members.map((m) => ({
      uid: m.uid,
      displayName: m.displayName,
      email: `${m.slug}@test.fridgelist.invalid`,
      emailVerified: true,
      providerData: [
        {
          providerId: "google.com",
          uid: `test-${m.slug}`,
          displayName: m.displayName,
          email: `${m.slug}@test.fridgelist.invalid`,
        },
      ],
    })),
  );
  // Re-runs report "uid already exists"; that is fine.
  const real = result.errors.filter((e) => !/exist/i.test(e.error.message));
  if (real.length > 0) throw new Error(`auth import failed: ${real.map((e) => e.error.message).join("; ")}`);
}

type Item = {
  id: string;
  list: string;
  name: string;
  lang: string;
  canonicalId?: string;
  category: string;
  qty?: number;
  unit?: string;
  status?: "open" | "claimed" | "done" | "missing";
  by: (typeof members)[number]["uid"];
  names?: Record<string, string>;
  urgent?: boolean;
};

const ITEMS: Item[] = [
  { id: "i01", list: "groceries", name: "بندورة", lang: "ar", canonicalId: "tomato", category: "produce", qty: 2, unit: "kg", by: "seed-owner", names: { ar: "بندورة", tr: "Domates", en: "Tomatoes", fil: "Kamatis" } },
  { id: "i02", list: "groceries", name: "خبز عربي", lang: "ar", canonicalId: "arabic_bread", category: "bakery", qty: 2, unit: "pack", by: "seed-owner" },
  { id: "i03", list: "groceries", name: "لبنة", lang: "ar", canonicalId: "labneh", category: "dairy_eggs", by: "seed-owner", urgent: true },
  { id: "i04", list: "groceries", name: "بيض", lang: "ar", canonicalId: "eggs", category: "dairy_eggs", qty: 30, unit: "piece", status: "claimed", by: "seed-owner" },
  { id: "i05", list: "groceries", name: "رز بسمتي", lang: "ar", canonicalId: "basmati_rice", category: "pantry", qty: 5, unit: "kg", by: "seed-owner" },
  { id: "i06", list: "groceries", name: "süt", lang: "tr", canonicalId: "milk", category: "dairy_eggs", qty: 2, unit: "l", by: "seed-adult", names: { ar: "حليب", tr: "süt", en: "Milk", fil: "Gatas" } },
  { id: "i07", list: "groceries", name: "beyaz peynir", lang: "tr", canonicalId: "white_cheese", category: "dairy_eggs", qty: 0.5, unit: "kg", by: "seed-adult" },
  { id: "i08", list: "groceries", name: "zeytin", lang: "tr", canonicalId: "olives", category: "breakfast", by: "seed-adult" },
  { id: "i09", list: "groceries", name: "ayran", lang: "tr", canonicalId: "laban_ayran", category: "beverages", qty: 6, unit: "bottle", status: "done", by: "seed-adult" },
  { id: "i10", list: "groceries", name: "sigara böreği yufkası", lang: "tr", category: "frozen", by: "seed-adult" },
  { id: "i11", list: "groceries", name: "sabong panlaba", lang: "fil", canonicalId: "laundry_detergent", category: "household_cleaning", by: "seed-helper" },
  { id: "i12", list: "groceries", name: "Dish soap", lang: "en", canonicalId: "dish_soap", category: "household_cleaning", status: "missing", by: "seed-helper" },
  { id: "i13", list: "groceries", name: "bananas", lang: "en", canonicalId: "banana", category: "produce", qty: 1, unit: "bunch", by: "seed-helper" },
  { id: "i14", list: "pharmacy", name: "بنادول", lang: "ar", category: "pharmacy", qty: 1, unit: "box", by: "seed-owner" },
  { id: "i15", list: "pharmacy", name: "güneş kremi", lang: "tr", category: "personal_care", by: "seed-adult" },
];

async function seedHousehold(): Promise<void> {
  const batch = db.batch();
  const h = db.doc(`households/${HID}`);
  batch.set(h, {
    name: "بيت العائلة",
    ownerUid: "seed-owner",
    defaultLanguage: "ar",
    memberLanguages: [...new Set(members.map((m) => m.language))],
    plan: "free",
    createdAt: now,
  });
  for (const m of members) {
    batch.set(h.collection("members").doc(m.uid), {
      role: m.role,
      displayName: m.displayName,
      language: m.language,
      notify: NOTIFY,
      joinedAt: now,
    });
    batch.set(db.doc(`users/${m.uid}`), {
      displayName: m.displayName,
      uiLanguage: m.uiLanguage,
      language: m.language,
      householdIds: [HID],
      createdAt: now,
    });
  }
  batch.set(h.collection("lists").doc("groceries"), { name: "البقالة", type: "grocery", order: 0, archived: false, createdAt: now });
  batch.set(h.collection("lists").doc("pharmacy"), { name: "الصيدلية", type: "pharmacy", order: 1, archived: false, createdAt: now });
  for (const item of ITEMS) {
    const status = item.status ?? "open";
    batch.set(h.collection("lists").doc(item.list).collection("items").doc(item.id), {
      name: item.name,
      lang: item.lang,
      ...(item.names ? { names: item.names } : {}),
      ...(item.canonicalId ? { canonicalId: item.canonicalId } : {}),
      category: item.category,
      categorySource: "auto",
      ...(item.qty !== undefined ? { qty: item.qty } : {}),
      ...(item.unit ? { unit: item.unit } : {}),
      urgent: item.urgent ?? false,
      status,
      addedBy: item.by,
      addedVia: "app_text",
      ...(status === "claimed" ? { claimedBy: "seed-adult" } : {}),
      ...(status === "done" ? { doneBy: "seed-helper", doneAt: now } : {}),
      createdAt: now,
      updatedAt: now,
    });
  }
  await batch.commit();
}

type Store = [chainId: string, name: string, dLat: number, dLng: number];
const AREAS: { country: "TR" | "SA" | "AE"; lat: number; lng: number; stores: Store[] }[] = [
  {
    country: "TR", // Kadıköy, Istanbul
    lat: 40.9903,
    lng: 29.029,
    stores: [
      ["migros", "Migros", 0.0012, 0.0021], ["migros", "Migros Jet", -0.0031, 0.0008], ["a101", "A101", 0.0024, -0.0017],
      ["a101", "A101", -0.0045, -0.0032], ["bim", "BİM", 0.0038, 0.0035], ["sok", "ŞOK", -0.0016, 0.0047],
      ["carrefoursa", "CarrefourSA", 0.0061, -0.0012], ["file", "File", -0.0058, 0.0025], ["macrocenter", "Macrocenter", 0.0072, 0.0054],
      ["other", "Bakkal Ahmet", 0.0004, -0.0006],
    ],
  },
  {
    country: "SA", // Olaya, Riyadh
    lat: 24.6956,
    lng: 46.685,
    stores: [
      ["panda", "Panda", 0.0018, 0.0026], ["panda", "Hyper Panda", -0.0062, 0.0041], ["danube", "Danube", 0.0044, -0.0021],
      ["tamimi", "Tamimi Markets", -0.0027, -0.0049], ["othaim", "Othaim Markets", 0.0067, 0.0013], ["lulu", "LuLu Hypermarket", -0.0081, -0.0016],
      ["carrefour", "Carrefour", 0.0091, 0.0062], ["other", "بقالة الحي", 0.0007, 0.0009], ["othaim", "Othaim Markets", -0.0011, 0.0071],
      ["danube", "Danube", 0.0052, -0.0068],
    ],
  },
  {
    country: "AE", // Dubai Marina
    lat: 25.0805,
    lng: 55.1403,
    stores: [
      ["carrefour", "Carrefour Market", 0.0015, 0.0011], ["spinneys", "Spinneys", -0.0023, 0.0029], ["waitrose", "Waitrose", 0.0036, -0.0018],
      ["union_coop", "Union Coop", -0.0049, -0.0037], ["choithrams", "Choithrams", 0.0058, 0.0024], ["lulu", "LuLu Hypermarket", -0.0071, 0.0052],
      ["viva", "Viva", 0.0026, -0.0055], ["almaya", "Al Maya", -0.0012, -0.0008], ["spinneys", "Spinneys", 0.0083, 0.0047],
      ["other", "Marina Grocery", 0.0003, 0.0004],
    ],
  },
];

async function seedStores(): Promise<number> {
  const batch = db.batch();
  let n = 0;
  for (const area of AREAS) {
    area.stores.forEach(([chainId, name, dLat, dLng], i) => {
      const lat = Number((area.lat + dLat).toFixed(6));
      const lng = Number((area.lng + dLng).toFixed(6));
      // Synthetic seed stores: user_ ids, so they can never collide with real osm_ imports (B5).
      batch.set(db.doc(`stores/user_seed_${area.country.toLowerCase()}_${String(i + 1).padStart(2, "0")}`), {
        name,
        chainId,
        lat,
        lng,
        geohash: geohashForLocation([lat, lng], 9),
        country: area.country,
        source: "user",
        verified: true,
        updatedAt: Timestamp.now(),
      });
      n++;
    });
  }
  await batch.commit();
  return n;
}

await seedAuth();
await seedHousehold();
const stores = await seedStores();
process.stdout.write(
  `Seeded household ${HID}: ${String(members.length)} members, 2 lists, ${String(ITEMS.length)} items; ${String(stores)} stores.\n` +
    `Dev app test sign-in: Mama (owner, ar), Ayse (adult, tr), Maria (helper, fil).\n`,
);

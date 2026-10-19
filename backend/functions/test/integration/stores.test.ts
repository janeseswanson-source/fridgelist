import { deleteApp, type App } from "firebase-admin/app";
import type { Firestore } from "firebase-admin/firestore";
import { geohashForLocation } from "geofire-common";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { createHouseholdCore } from "../../src/callables/createHousehold.js";
import { addStoreCore, nearbyStoresCore } from "../../src/callables/stores.js";
import { clearDb, expectHttpsError, testDb } from "./setup.js";

let app: App;
let db: Firestore;
beforeAll(() => {
  ({ app, db } = testDb("stores"));
});
afterAll(async () => {
  await deleteApp(app);
});

const KADIKOY: [number, number] = [40.9903, 29.029];
const ME = "u-me";
const NEIGHBOUR = "u-neighbour";
let myHousehold = "";

/** A store at a bearing/distance from Kadıköy (metres; ~111 km per degree of latitude). */
async function store(id: string, dNorthM: number, dEastM: number, extra: Record<string, unknown> = {}) {
  const lat = KADIKOY[0] + dNorthM / 111_320;
  const lng = KADIKOY[1] + dEastM / (111_320 * Math.cos((KADIKOY[0] * Math.PI) / 180));
  await db.doc(`stores/${id}`).set({
    name: id,
    chainId: "migros",
    lat,
    lng,
    geohash: geohashForLocation([lat, lng], 9),
    country: "TR",
    source: "osm",
    verified: true,
    ...extra,
  });
}

beforeEach(async () => {
  await clearDb();
  const profile = { defaultLanguage: "tr", displayName: "Ayşe", language: "tr", uiLanguage: "tr" };
  ({ householdId: myHousehold } = await createHouseholdCore(db, ME, { name: "Ev", ...profile }));
  await createHouseholdCore(db, NEIGHBOUR, { name: "Komşu", ...profile });
  await store("osm_node_300", 300, 0);
  await store("osm_node_100", 0, 100);
  await store("osm_node_2000", -2000, 0);
  await store("osm_node_4900", 0, -4900);
  await store("osm_node_5100", 5100, 0);
  await store("osm_node_9000", 0, 9000);
});

const nearby = (data: Record<string, unknown>, uid = ME) =>
  nearbyStoresCore(db, uid, { lat: KADIKOY[0], lng: KADIKOY[1], ...data });

describe("nearbyStores (CONTRACT §4)", () => {
  it("nearest first within the default 5 km, with distances", async () => {
    const { stores } = await nearby({});
    expect(stores.map((s) => s.storeId)).toEqual([
      "osm_node_100",
      "osm_node_300",
      "osm_node_2000",
      "osm_node_4900",
    ]);
    expect(stores[0]).toMatchObject({ name: "osm_node_100", chainId: "migros" });
    expect(stores[0]?.distanceM).toBeGreaterThan(95);
    expect(stores[0]?.distanceM).toBeLessThan(105);
  });

  it("radius (max 10 km) and limit (max 100)", async () => {
    expect((await nearby({ radiusM: 10_000 })).stores).toHaveLength(6);
    expect((await nearby({ radiusM: 500 })).stores.map((s) => s.storeId)).toEqual([
      "osm_node_100",
      "osm_node_300",
    ]);
    expect((await nearby({ limit: 1 })).stores.map((s) => s.storeId)).toEqual(["osm_node_100"]);
    await expectHttpsError(nearby({ radiusM: 10_001 }), "invalid-argument");
    await expectHttpsError(nearby({ limit: 101 }), "invalid-argument");
    await expectHttpsError(nearby({ lat: 91 }), "invalid-argument");
    await expectHttpsError(nearbyStoresCore(db, ME, { lat: "40" }), "invalid-argument");
  });

  it("unverified stores show only to the household that added them", async () => {
    await store("user_x", 50, 50, { source: "user", verified: false, addedByHousehold: myHousehold });
    expect((await nearby({ radiusM: 200 })).stores.map((s) => s.storeId)).toContain("user_x");
    expect((await nearby({ radiusM: 200 }, NEIGHBOUR)).stores.map((s) => s.storeId)).not.toContain("user_x");
  });

  it("works across geohash cell edges (no duplicates, nothing missed)", async () => {
    // Points around the centre in all directions at the same distance.
    for (const [i, [n, e]] of (
      [
        [700, 0],
        [-700, 0],
        [0, 700],
        [0, -700],
        [495, 495],
        [-495, -495],
      ] as [number, number][]
    ).entries()) {
      await store(`ring_${String(i)}`, n, e);
    }
    const ids = (await nearby({ radiusM: 800 })).stores.map((s) => s.storeId);
    expect(ids.filter((id) => id.startsWith("ring_"))).toHaveLength(6);
    expect(new Set(ids).size).toBe(ids.length);
  });
});

describe("addStore (CONTRACT §4)", () => {
  const add = (data: Record<string, unknown>, uid = ME) =>
    addStoreCore(db, uid, { name: "Bakkal Ahmet", lat: KADIKOY[0] + 0.01, lng: KADIKOY[1], ...data });

  it("creates an unverified user_ store for the adder's household", async () => {
    const { storeId } = await add({});
    expect(storeId).toMatch(/^user_/);
    expect((await db.doc(`stores/${storeId}`).get()).data()).toMatchObject({
      name: "Bakkal Ahmet",
      chainId: "other",
      country: "TR",
      source: "user",
      verified: false,
      addedByHousehold: myHousehold,
    });
    const near = { lat: KADIKOY[0] + 0.01, lng: KADIKOY[1], radiusM: 100 };
    expect((await nearbyStoresCore(db, ME, near)).stores.map((s) => s.storeId)).toEqual([storeId]);
    expect((await nearbyStoresCore(db, NEIGHBOUR, near)).stores).toEqual([]);
  });

  it("the same name within 50 m is the same store", async () => {
    const first = await add({});
    expect(await add({ name: " bakkal ahmet ", lat: KADIKOY[0] + 0.0102 })).toEqual(first);
    expect(await add({ name: "Başka Bakkal" })).not.toEqual(first);
  });

  it("validation: supported countries, known chains, a household first, 20 per day", async () => {
    await expectHttpsError(add({ lat: 48.85, lng: 2.35 }), "invalid-argument");
    await expectHttpsError(add({ chainId: "aldi" }), "invalid-argument");
    await expectHttpsError(add({ name: "" }), "invalid-argument");
    await expectHttpsError(add({}, "u-nobody"), "failed-precondition");
    expect((await add({ name: "Migros", chainId: "migros", lng: KADIKOY[1] + 0.02 })).storeId).toMatch(
      /^user_/,
    );
    // Rejected requests above did not count; Migros was add #1, so 19 more reach the limit of 20.
    for (let i = 0; i < 19; i++)
      await add({ name: `Store ${String(i)}`, lng: KADIKOY[1] + 0.03 + i * 0.001 });
    await expectHttpsError(add({ name: "One too many", lng: KADIKOY[1] + 0.1 }), "resource-exhausted");
  });
});

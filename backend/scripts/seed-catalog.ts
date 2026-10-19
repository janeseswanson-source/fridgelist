/**
 * Loads fixtures/catalog_seed.json into `catalog/{canonicalId}` (CONTRACT §3.14). Idempotent:
 * fixed IDs, full overwrite, and catalog docs no longer in the seed are removed.
 *
 *   npm run seed:catalog                      # the running emulators (demo-fridgelist)
 *   npm run seed:catalog -- --live <projectId> # a real project: only when the owner asks
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { initializeApp } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";

interface SeedItem {
  id: string;
  category: string;
  defaultUnit?: string;
  names: Record<string, string>;
  synonyms: Record<string, string[]>;
}

export async function seedCatalog(db: Firestore): Promise<number> {
  const seedPath = resolve(import.meta.dirname, "../../fixtures/catalog_seed.json");
  const seed = JSON.parse(readFileSync(seedPath, "utf8")) as { items: SeedItem[] };
  const ids = new Set(seed.items.map((item) => item.id));
  const existing = await db.collection("catalog").listDocuments();

  let batch = db.batch();
  let pending = 0;
  const flush = async () => {
    if (pending > 0) await batch.commit();
    batch = db.batch();
    pending = 0;
  };
  for (const item of seed.items) {
    batch.set(db.doc(`catalog/${item.id}`), {
      names: item.names,
      synonyms: item.synonyms,
      category: item.category,
      ...(item.defaultUnit ? { defaultUnit: item.defaultUnit } : {}),
    });
    if (++pending === 400) await flush();
  }
  for (const doc of existing) {
    if (!ids.has(doc.id)) {
      batch.delete(doc);
      if (++pending === 400) await flush();
    }
  }
  await flush();
  return seed.items.length;
}

const isMain = process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;
if (isMain) {
  const liveIndex = process.argv.indexOf("--live");
  const liveProject = liveIndex > 0 ? process.argv[liveIndex + 1] : undefined;
  if (liveProject === undefined) {
    process.env.FIRESTORE_EMULATOR_HOST ??= "127.0.0.1:8080";
    initializeApp({ projectId: "demo-fridgelist" });
  } else {
    if (process.env.FIRESTORE_EMULATOR_HOST) throw new Error("--live with FIRESTORE_EMULATOR_HOST set: unset it first.");
    initializeApp({ projectId: liveProject });
  }
  const n = await seedCatalog(getFirestore());
  process.stdout.write(`Catalog: ${String(n)} items written${liveProject ? ` to ${liveProject}` : " to the emulators"}.\n`);
}

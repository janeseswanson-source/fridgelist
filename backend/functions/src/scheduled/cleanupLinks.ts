import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { EXPORT_PREFIX, StorageExportStore, type ExportStore } from "../account/exportStore.js";
import { EXPORT_VALID_MS } from "../callables/account.js";
import { log } from "../lib/log.js";

/**
 * Daily housekeeping (ARCHITECTURE §3.4 `cleanupLinks`): revoked and expired share links,
 * expired invites, and data exports whose 24-hour link has run out.
 */
export async function cleanupLinksCore(
  db: Firestore,
  exports: Pick<ExportStore, "deleteOlderThan"> | null,
  now: Date,
): Promise<{ links: number; invites: number; exports: number }> {
  const at = Timestamp.fromDate(now);
  const [revoked, expiredLinks, expiredInvites] = await Promise.all([
    db.collection("shareLinks").where("revoked", "==", true).get(),
    db.collection("shareLinks").where("expiresAt", "<=", at).get(),
    db.collection("invites").where("expiresAt", "<=", at).get(),
  ]);
  const links = new Map([...revoked.docs, ...expiredLinks.docs].map((d) => [d.id, d.ref]));
  const refs = [...links.values(), ...expiredInvites.docs.map((d) => d.ref)];
  for (let i = 0; i < refs.length; i += 400) {
    const batch = db.batch();
    refs.slice(i, i + 400).forEach((ref) => batch.delete(ref));
    await batch.commit();
  }
  const exportFiles = exports ? await exports.deleteOlderThan(EXPORT_PREFIX, EXPORT_VALID_MS) : 0;
  const result = { links: links.size, invites: expiredInvites.size, exports: exportFiles };
  log.info("cleanup_links", result);
  return result;
}

export const cleanupLinks = onSchedule({ schedule: "every day 03:00", timeZone: "Asia/Riyadh" }, async () => {
  await cleanupLinksCore(getFirestore(), new StorageExportStore(), new Date());
});

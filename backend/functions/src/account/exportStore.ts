import { randomUUID } from "node:crypto";
import { getStorage } from "firebase-admin/storage";

/** Where exports go and how the user gets them (CONTRACT §4 `exportMyData`: signed URL, 24 h). */
export interface ExportStore {
  /** Saves the JSON and returns a URL valid for `validForMs`. */
  save(path: string, json: string, validForMs: number): Promise<string>;
  /** Deletes export files older than `olderThanMs`; returns how many. */
  deleteOlderThan(prefix: string, olderThanMs: number): Promise<number>;
}

export const EXPORT_PREFIX = "exports/";

/** Cloud Storage. Signed URLs need a credential that can sign (the functions' service account). */
export class StorageExportStore implements ExportStore {
  async save(path: string, json: string, validForMs: number): Promise<string> {
    const file = getStorage().bucket().file(path);
    if (process.env.FUNCTIONS_EMULATOR === "true") {
      // The Storage emulator can't sign and enforces storage.rules (deny all) on plain media URLs:
      // a Firebase download token makes the local link work. Production uses the signed URL.
      const token = randomUUID();
      await file.save(json, {
        contentType: "application/json; charset=utf-8",
        resumable: false,
        metadata: { metadata: { firebaseStorageDownloadTokens: token } },
      });
      const host = process.env.FIREBASE_STORAGE_EMULATOR_HOST ?? "127.0.0.1:9199";
      return `http://${host}/v0/b/${file.bucket.name}/o/${encodeURIComponent(path)}?alt=media&token=${token}`;
    }
    await file.save(json, { contentType: "application/json; charset=utf-8", resumable: false });
    const [url] = await file.getSignedUrl({
      action: "read",
      expires: Date.now() + validForMs,
      version: "v4",
    });
    return url;
  }

  async deleteOlderThan(prefix: string, olderThanMs: number): Promise<number> {
    const [files] = await getStorage().bucket().getFiles({ prefix });
    const cutoff = Date.now() - olderThanMs;
    const stale = files.filter((f) => new Date(String(f.metadata.timeCreated ?? 0)).getTime() < cutoff);
    await Promise.all(stale.map((f) => f.delete({ ignoreNotFound: true })));
    return stale.length;
  }
}

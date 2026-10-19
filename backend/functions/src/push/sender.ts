import type { Firestore } from "firebase-admin/firestore";
import { getMessaging, type BatchResponse, type Message } from "firebase-admin/messaging";
import { log } from "../lib/log.js";
import type { Device } from "./messages.js";

export interface Outgoing {
  message: Message;
  /** The device the message is for, so a dead token can be removed. */
  device?: Pick<Device, "uid" | "deviceId">;
  /** For logs (never user text): e.g. "trip_heading". */
  kind: string;
}

export interface PushSender {
  send(outgoing: readonly Outgoing[]): Promise<void>;
}

/** FCM error codes that mean the token will never work again. */
const DEAD_TOKEN = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

/**
 * FCM HTTP v1 through firebase-admin (`sendEach`, which also carries `apns.liveActivityToken`).
 * A token FCM reports as unregistered gets its device doc deleted, so the household stops paying
 * for pushes to an uninstalled app.
 */
export class FcmSender implements PushSender {
  constructor(
    private readonly db: Firestore,
    private readonly sendEach: (messages: Message[]) => Promise<BatchResponse> = (messages) =>
      getMessaging().sendEach(messages),
  ) {}

  async send(outgoing: readonly Outgoing[]): Promise<void> {
    for (let i = 0; i < outgoing.length; i += 500) {
      const chunk = outgoing.slice(i, i + 500);
      const result = await this.sendEach(chunk.map((o) => o.message));
      await Promise.all(
        result.responses.map(async (response, j) => {
          const o = chunk[j];
          if (response.success || !o) return;
          const code = response.error?.code ?? "unknown";
          log.warn("push_failed", { kind: o.kind, code });
          if (o.device && DEAD_TOKEN.has(code)) {
            await this.db.doc(`users/${o.device.uid}/devices/${o.device.deviceId}`).delete();
          }
        }),
      );
      log.info("push_sent", { count: chunk.length, failed: result.failureCount });
    }
  }
}

/** Collects messages instead of sending them (tests, and local runs without FCM). */
export class RecordingSender implements PushSender {
  readonly sent: Outgoing[] = [];
  send(outgoing: readonly Outgoing[]): Promise<void> {
    this.sent.push(...outgoing);
    return Promise.resolve();
  }
}

/** The Emulator Suite has no FCM: log what would be sent (kinds and counts only). */
export class EmulatorLogSender implements PushSender {
  send(outgoing: readonly Outgoing[]): Promise<void> {
    const kinds: Record<string, number> = {};
    for (const o of outgoing) kinds[o.kind] = (kinds[o.kind] ?? 0) + 1;
    for (const [kind, count] of Object.entries(kinds)) log.info("push_emulated", { kind, count });
    return Promise.resolve();
  }
}

/** FCM in production; a log in the Functions emulator. */
export function defaultSender(db: Firestore): PushSender {
  return process.env.FUNCTIONS_EMULATOR === "true" ? new EmulatorLogSender() : new FcmSender(db);
}

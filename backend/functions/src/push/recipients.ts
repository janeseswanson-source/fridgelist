import type { Firestore } from "firebase-admin/firestore";
import { uiLanguageOr } from "../i18n/index.js";
import type { Device } from "./messages.js";

export type NotifyKey = "tripHeading" | "tripArrived" | "itemMissing" | "usuals";

/** CONTRACT §3.4 defaults: tripHeading true, tripArrived false, itemMissing true, usuals false. */
const DEFAULTS: Record<NotifyKey, boolean> = {
  tripHeading: true,
  tripArrived: false,
  itemMissing: true,
  usuals: false,
};

export interface Member {
  uid: string;
  displayName: string;
  /** Item display language (display rule). */
  language: string;
  role: string;
  notify: Partial<Record<NotifyKey, boolean>>;
}

export function wants(member: Member, key: NotifyKey): boolean {
  return member.notify[key] ?? DEFAULTS[key];
}

export async function membersOf(db: Firestore, householdId: string): Promise<Member[]> {
  const snap = await db.collection(`households/${householdId}/members`).get();
  return snap.docs.map((d) => ({
    uid: d.id,
    displayName: String(d.get("displayName") ?? ""),
    language: String(d.get("language") ?? "en"),
    role: String(d.get("role") ?? ""),
    notify: (d.get("notify") as Member["notify"] | undefined) ?? {},
  }));
}

/** Every registered device of `uid`, with the user's push language (`users.uiLanguage`). */
export async function devicesOf(db: Firestore, uid: string): Promise<Device[]> {
  const [user, devices] = await Promise.all([
    db.doc(`users/${uid}`).get(),
    db.collection(`users/${uid}/devices`).get(),
  ]);
  const uiLanguage = uiLanguageOr(user.get("uiLanguage"));
  return devices.docs.flatMap((d): Device[] => {
    const platform: unknown = d.get("platform");
    const fcmToken: unknown = d.get("fcmToken");
    if ((platform !== "ios" && platform !== "android") || typeof fcmToken !== "string" || !fcmToken)
      return [];
    const pushToStart: unknown = d.get("apnsPushToStartToken");
    return [
      {
        uid,
        deviceId: d.id,
        platform,
        fcmToken,
        uiLanguage,
        ...(typeof pushToStart === "string" && pushToStart ? { apnsPushToStartToken: pushToStart } : {}),
      },
    ];
  });
}

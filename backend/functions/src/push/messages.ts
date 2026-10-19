import type { Message } from "firebase-admin/messaging";
import type { UiLanguage } from "../config.js";
import { format } from "../i18n/index.js";
import type { TripState } from "../trips/state.js";

/** A device as registered under users/{uid}/devices (CONTRACT §3.2). */
export interface Device {
  uid: string;
  deviceId: string;
  platform: "ios" | "android";
  fcmToken: string;
  apnsPushToStartToken?: string;
  uiLanguage: UiLanguage;
}

/** CONTRACT §5.1 iOS categories and Android channels. */
const IOS_CATEGORY = {
  trip_heading: "TRIP_HEADING",
  trip_arrived: "TRIP_ARRIVED",
  item_missing: "ITEM_MISSING",
} as const;
export type AlertType = keyof typeof IOS_CATEGORY;

/**
 * A visible household push (trip_heading, trip_arrived, item_missing). Android (contract v2):
 * data-only with `title`/`body`, priority high; the app builds the notification and the inline
 * reply. iOS: `aps.alert` + category, data keys alongside.
 */
export function alertMessage(
  device: Device,
  type: AlertType,
  householdId: string,
  title: string,
  body: string,
  extra: Record<string, string>,
): Message {
  const data = { type, householdId, ...extra };
  if (device.platform === "android") {
    return { token: device.fcmToken, data: { ...data, title, body }, android: { priority: "high" } };
  }
  return {
    token: device.fcmToken,
    data,
    apns: {
      headers: { "apns-push-type": "alert", "apns-priority": "10" },
      payload: { aps: { alert: { title, body }, category: IOS_CATEGORY[type], sound: "default" } },
    },
  };
}

/** `store` empty = the shopper didn't name one: "Ayşe alışverişe çıktı", not "…: market". */
export function headingTexts(language: UiLanguage, name: string, store: string, minutes: number) {
  return {
    title: store
      ? format(language, "tripHeadingTitle", { name, store })
      : format(language, "tripHeadingTitleNoStore", { name }),
    body:
      minutes > 0
        ? format(language, "tripHeadingBody", { minutes }, minutes)
        : format(language, "addAnything"),
  };
}

export function arrivedTexts(language: UiLanguage, name: string, store: string) {
  return {
    title: store
      ? format(language, "tripArrivedTitle", { name, store })
      : format(language, "tripArrivedTitleNoStore", { name }),
    body: format(language, "addAnything"),
  };
}

export function missingTexts(language: UiLanguage, name: string, item: string) {
  return {
    title: format(language, "itemMissingTitle", { name, item }),
    body: format(language, "itemMissingBody"),
  };
}

/** CONTRACT §5.3: the Android shopper's ongoing notification; every value a string. */
export function tripUpdateMessage(
  device: Device,
  householdId: string,
  tripId: string,
  storeName: string,
  state: TripState,
): Message {
  const data: Record<string, string> = {
    type: "trip_update",
    householdId,
    tripId,
    status: state.status,
    storeName,
    remaining: String(state.remaining),
    done: String(state.done),
    nextItems: JSON.stringify(state.nextItems),
  };
  if (state.lastCallUntil !== undefined) data.lastCallUntil = String(state.lastCallUntil);
  if (state.latestAddBy !== undefined) data.latestAddBy = state.latestAddBy;
  if (state.latestAddName !== undefined) data.latestAddName = state.latestAddName;
  return { token: device.fcmToken, data, android: { priority: "high" } };
}

/** ContentState JSON (CONTRACT §5.2): numbers stay numbers; optional keys omitted. */
export function contentState(state: TripState): Record<string, unknown> {
  return {
    status: state.status,
    remaining: state.remaining,
    done: state.done,
    nextItems: state.nextItems,
    ...(state.lastCallUntil !== undefined ? { lastCallUntil: state.lastCallUntil } : {}),
    ...(state.latestAddBy !== undefined ? { latestAddBy: state.latestAddBy } : {}),
    ...(state.latestAddName !== undefined ? { latestAddName: state.latestAddName } : {}),
  };
}

export interface LiveActivityInput {
  /** The FCM registration token of the iOS device. */
  fcmToken: string;
  /** Push-to-start token for `start`; the activity's token for `update`/`end`. */
  liveActivityToken: string;
  event: "start" | "update" | "end";
  nowSeconds: number;
  state: TripState;
  attributes?: { tripId: string; householdId: string; storeName: string; chainId: string };
  alert?: { title: string; body: string };
}

/** CONTRACT §5.2 Live Activity through FCM HTTP v1 (`apns.liveActivityToken`). */
export function liveActivityMessage(input: LiveActivityInput): Message {
  const aps: Record<string, unknown> = {
    timestamp: input.nowSeconds,
    event: input.event,
    "content-state": contentState(input.state),
  };
  if (input.event === "start") {
    aps["attributes-type"] = "ShoppingAttributes";
    aps.attributes = input.attributes;
    aps.alert = input.alert;
  }
  if (input.event === "end") aps["dismissal-date"] = input.nowSeconds + 15 * 60;
  return {
    token: input.fcmToken,
    apns: {
      liveActivityToken: input.liveActivityToken,
      headers: { "apns-push-type": "liveactivity", "apns-priority": input.event === "update" ? "5" : "10" },
      payload: { aps },
    },
  };
}

/** CONTRACT §5.1 `list_changed`: data-only (Android), a silent background push (iOS). */
export function listChangedMessage(device: Device, householdId: string, listId: string): Message {
  const data = { type: "list_changed", householdId, listId };
  if (device.platform === "android") return { token: device.fcmToken, data };
  return {
    token: device.fcmToken,
    data,
    apns: {
      headers: { "apns-push-type": "background", "apns-priority": "5" },
      payload: { aps: { "content-available": 1 } },
    },
  };
}

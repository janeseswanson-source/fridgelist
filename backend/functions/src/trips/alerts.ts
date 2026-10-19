import type { Firestore } from "firebase-admin/firestore";
import { displayTitle } from "../lib/display.js";
import { log } from "../lib/log.js";
import { alertMessage, arrivedTexts, headingTexts, missingTexts } from "../push/messages.js";
import { devicesOf, membersOf, wants } from "../push/recipients.js";
import type { Outgoing, PushSender } from "../push/sender.js";
import type { MissingNotifier } from "../triggers/onItemUpdated.js";

export interface TripAlert {
  householdId: string;
  tripId: string;
  shopperUid: string;
  shopperName: string;
  /** Empty when the shopper didn't name a store: the store-less titles are used ("Ayşe markette"). */
  storeName: string;
  listId: string;
  /** Unix seconds, `trip_heading` only. */
  lastCallUntil?: number;
  lastCallMinutes?: number;
}

/**
 * CONTRACT §5.1: `trip_heading` to other members with `notify.tripHeading`, `trip_arrived` to
 * those with `notify.tripArrived`; text in each recipient's `uiLanguage`.
 */
export async function sendTripAlert(
  db: Firestore,
  sender: PushSender,
  type: "trip_heading" | "trip_arrived",
  alert: TripAlert,
): Promise<number> {
  const members = await membersOf(db, alert.householdId);
  const recipients = members.filter(
    (m) => m.uid !== alert.shopperUid && wants(m, type === "trip_heading" ? "tripHeading" : "tripArrived"),
  );
  const devices = (await Promise.all(recipients.map((m) => devicesOf(db, m.uid)))).flat();
  const outgoing = devices.map((device): Outgoing => {
    const texts =
      type === "trip_heading"
        ? headingTexts(device.uiLanguage, alert.shopperName, alert.storeName, alert.lastCallMinutes ?? 0)
        : arrivedTexts(device.uiLanguage, alert.shopperName, alert.storeName);
    const extra: Record<string, string> = { tripId: alert.tripId, listId: alert.listId };
    if (type === "trip_heading" && alert.lastCallUntil !== undefined)
      extra.lastCallUntil = String(alert.lastCallUntil);
    return {
      kind: type,
      device,
      message: alertMessage(device, type, alert.householdId, texts.title, texts.body, extra),
    };
  });
  await sender.send(outgoing);
  log.info("trip_alert", { householdId: alert.householdId, type, devices: outgoing.length });
  return outgoing.length;
}

/** The real `item_missing` sender (replaces the B4 stub). */
export class PushMissingNotifier implements MissingNotifier {
  constructor(
    private readonly db: Firestore,
    private readonly sender: PushSender,
  ) {}

  async itemMissing(input: {
    householdId: string;
    listId: string;
    itemId: string;
    tripId?: string;
    recipientUid: string;
  }): Promise<void> {
    const [item, household, members, devices, trip] = await Promise.all([
      this.db.doc(`households/${input.householdId}/lists/${input.listId}/items/${input.itemId}`).get(),
      this.db.doc(`households/${input.householdId}`).get(),
      membersOf(this.db, input.householdId),
      devicesOf(this.db, input.recipientUid),
      input.tripId
        ? this.db.doc(`households/${input.householdId}/trips/${input.tripId}`).get()
        : Promise.resolve(null),
    ]);
    const data = item.data();
    if (!data) return;
    const recipient = members.find((m) => m.uid === input.recipientUid);
    const itemTitle = displayTitle(
      {
        name: String(data.name ?? ""),
        lang: data.lang as string | undefined,
        names: data.names as Record<string, string> | undefined,
      },
      recipient?.language ?? "en",
      String(household.get("defaultLanguage") ?? "en"),
    );
    const shopperName = String(trip?.get("shopperName") ?? "");
    const extra: Record<string, string> = { itemId: input.itemId, listId: input.listId };
    if (input.tripId) extra.tripId = input.tripId;
    await this.sender.send(
      devices.map((device) => {
        const texts = missingTexts(device.uiLanguage, shopperName, itemTitle);
        return {
          kind: "item_missing",
          device,
          message: alertMessage(device, "item_missing", input.householdId, texts.title, texts.body, extra),
        };
      }),
    );
  }
}

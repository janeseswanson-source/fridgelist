import { CATEGORIES } from "../lib/categories.js";
import { displayTitle, type DisplayableItem } from "../lib/display.js";

/** What the trip's live surfaces show (CONTRACT §5.2 ContentState, §5.3 trip_update). */
export interface TripState {
  status: "heading" | "shopping" | "done" | "cancelled";
  remaining: number;
  done: number;
  nextItems: string[];
  /** Unix seconds. */
  lastCallUntil?: number;
  latestAddBy?: string;
  latestAddName?: string;
}

export interface StateItem extends DisplayableItem {
  status: string;
  category?: string | null;
  claimedBy?: string | null;
  doneTripId?: string | null;
  addedBy?: string;
  /** Unix millis. */
  createdAtMillis?: number;
  sortKey?: number | null;
}

export interface StateTrip {
  tripId: string;
  status: TripState["status"];
  shopperUid: string;
  /** Unix millis. */
  startedAtMillis: number;
  /** Unix millis. */
  lastCallUntilMillis?: number | null;
}

export interface StateContext {
  shopperLanguage: string;
  defaultLanguage: string;
  /** uid → display name, for `latestAddBy`. */
  memberNames: Record<string, string>;
  nowMillis: number;
}

const ORDER = new Map<string, number>(CATEGORIES.map((c, i) => [c, i]));
export const NEXT_ITEMS = 4;

/**
 * - remaining: open items plus items the shopper claimed (others' claims are theirs to buy);
 *   kids' pending requests don't count until approved.
 * - done: items ticked on this trip.
 * - nextItems: up to 4 of the remaining, in the shopper's language, in the default aisle order.
 * - latestAddBy/Name: the newest item someone else added since the trip started, while last call
 *   is open (ARCHITECTURE §6: "items added by others during the window are highlighted").
 */
export function computeTripState(trip: StateTrip, items: readonly StateItem[], ctx: StateContext): TripState {
  const toBuy = items.filter(
    (i) => i.status === "open" || (i.status === "claimed" && i.claimedBy === trip.shopperUid),
  );
  const done = items.filter((i) => i.status === "done" && i.doneTripId === trip.tripId).length;
  const ordered = [...toBuy].sort(
    (a, b) =>
      (ORDER.get(a.category ?? "other") ?? ORDER.size) - (ORDER.get(b.category ?? "other") ?? ORDER.size) ||
      (a.sortKey ?? 0) - (b.sortKey ?? 0) ||
      (a.createdAtMillis ?? 0) - (b.createdAtMillis ?? 0),
  );
  const state: TripState = {
    status: trip.status,
    remaining: toBuy.length,
    done,
    nextItems: ordered
      .slice(0, NEXT_ITEMS)
      .map((i) => displayTitle(i, ctx.shopperLanguage, ctx.defaultLanguage)),
  };
  const lastCall = trip.lastCallUntilMillis ?? null;
  if (lastCall !== null && lastCall > ctx.nowMillis) {
    state.lastCallUntil = Math.floor(lastCall / 1000);
    const latest = toBuy
      .filter(
        (i) => i.addedBy && i.addedBy !== trip.shopperUid && (i.createdAtMillis ?? 0) >= trip.startedAtMillis,
      )
      .sort((a, b) => (b.createdAtMillis ?? 0) - (a.createdAtMillis ?? 0))[0];
    if (latest?.addedBy) {
      state.latestAddBy = ctx.memberNames[latest.addedBy] ?? "";
      state.latestAddName = displayTitle(latest, ctx.shopperLanguage, ctx.defaultLanguage);
    }
  }
  return state;
}

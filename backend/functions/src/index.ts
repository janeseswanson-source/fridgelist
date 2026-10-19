// Must stay the first import: sets the region and initializes the Admin SDK (see setup.ts).
import "./setup.js";

// Callables (CONTRACT §4). Phase 2 (B10): linkWhatsApp, whatsappWebhook.
export { health } from "./callables/health.js";
export { createHousehold } from "./callables/createHousehold.js";
export { createInvite, joinHousehold } from "./callables/invites.js";
export { parseItems } from "./callables/parseItems.js";
export { transcribeAudio } from "./callables/transcribeAudio.js";
export { addStore, nearbyStores } from "./callables/stores.js";
export { registerLiveActivityToken, startTrip, updateTrip } from "./callables/trips.js";
export { createShareLink, guestAddItem, resolveShareLink, revokeShareLink } from "./callables/shareLinks.js";
export { mealToItems, suggestMeals } from "./callables/meals.js";
export { deleteMyAccount, exportMyData } from "./callables/account.js";

export { onMemberWritten } from "./triggers/onMemberWritten.js";
export { onItemCreated } from "./triggers/onItemCreated.js";
export { onItemUpdated } from "./triggers/onItemUpdated.js";
export { onItemWritten } from "./triggers/onItemWritten.js";

export { closeStaleTrips } from "./scheduled/closeStaleTrips.js";
export { cleanupLinks } from "./scheduled/cleanupLinks.js";

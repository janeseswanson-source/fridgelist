// Must stay the first import: sets the region and initializes the Admin SDK (see setup.ts).
import "./setup.js";

// Callables (CONTRACT §4) arrive milestone by milestone: B6 trips, B7 links, B8 meals,
// B9 accounts.
export { health } from "./callables/health.js";
export { createHousehold } from "./callables/createHousehold.js";
export { createInvite, joinHousehold } from "./callables/invites.js";
export { parseItems } from "./callables/parseItems.js";
export { transcribeAudio } from "./callables/transcribeAudio.js";
export { addStore, nearbyStores } from "./callables/stores.js";

export { onMemberWritten } from "./triggers/onMemberWritten.js";
export { onItemCreated } from "./triggers/onItemCreated.js";
export { onItemUpdated } from "./triggers/onItemUpdated.js";

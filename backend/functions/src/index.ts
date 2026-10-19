import { initializeApp } from "firebase-admin/app";
import { setGlobalOptions } from "firebase-functions/v2";
import { REGION } from "./config.js";

initializeApp();
setGlobalOptions({ region: REGION, maxInstances: 10 });

// Callables (CONTRACT §4) arrive milestone by milestone: B3 households and invites, B4 items,
// B5 stores, B6 trips, B7 links, B8 meals, B9 accounts.
export { health } from "./callables/health.js";

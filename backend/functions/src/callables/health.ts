import { onCall } from "firebase-functions/v2/https";
import { CONTRACT_VERSION, REGION } from "../config.js";

/**
 * Diagnostics only, not part of CONTRACT §4: lets RUNBOOK checks and app developers confirm
 * the functions are reachable in the expected region. Returns no user data and needs no auth.
 */
export function healthResponse() {
  return { ok: true, region: REGION, contractVersion: CONTRACT_VERSION };
}

export const health = onCall({ region: REGION }, () => healthResponse());

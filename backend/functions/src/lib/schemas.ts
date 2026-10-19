import { z } from "zod";
import { ITEM_LANGUAGES, UI_LANGUAGES } from "../config.js";

/** CONTRACT §2 / §3 building blocks for request validation. */
export const uiLanguage = z.enum(UI_LANGUAGES);
export const itemLanguage = z.enum(ITEM_LANGUAGES);

/** 1–40 characters after trimming (counted like the apps: UTF-16 units). */
export const shortName = z.string().trim().min(1).max(40);

export const id = z.string().min(1).max(128).regex(/^[^/]+$/);

export const invitableRole = z.enum(["adult", "kid", "helper"]);

export const DEFAULT_NOTIFY = { tripHeading: true, tripArrived: false, itemMissing: true, usuals: false } as const;

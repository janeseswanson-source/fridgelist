import { logger } from "firebase-functions";

/**
 * The only way functions write logs (CLAUDE.md rule 4: no item, household or member names).
 * Fields are limited to IDs, codes, counts and flags, so user text cannot be logged by mistake.
 */
export type LogFields = Record<string, string | number | boolean | null>;

/** Keys that may hold user text; rejected at runtime as a second line of defence. */
const FORBIDDEN_KEYS = /name|text|title|body|notes|email|displayName/i;

export function safeFields(fields: LogFields): LogFields {
  const out: LogFields = {};
  for (const [key, value] of Object.entries(fields)) {
    out[key] = FORBIDDEN_KEYS.test(key) ? "[redacted]" : value;
  }
  return out;
}

export const log = {
  info: (event: string, fields: LogFields = {}) => {
    logger.info(event, safeFields(fields));
  },
  warn: (event: string, fields: LogFields = {}) => {
    logger.warn(event, safeFields(fields));
  },
  error: (event: string, fields: LogFields = {}) => {
    logger.error(event, safeFields(fields));
  },
};

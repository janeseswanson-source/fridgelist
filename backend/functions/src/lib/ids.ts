import { randomInt } from "node:crypto";

/** CONTRACT §3.12: invite codes use this alphabet (no 0/O, 1/I look-alikes). */
export const INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
export const INVITE_CODE_LENGTH = 8;

/** A random invite code from a cryptographic source. */
export function inviteCode(random: (max: number) => number = randomInt): string {
  let code = "";
  for (let i = 0; i < INVITE_CODE_LENGTH; i++) {
    code += INVITE_ALPHABET.charAt(random(INVITE_ALPHABET.length));
  }
  return code;
}

export function isInviteCode(value: string): boolean {
  return new RegExp(`^[${INVITE_ALPHABET}]{${String(INVITE_CODE_LENGTH)}}$`).test(value);
}

/** CONTRACT §3.13: OSM stores are `osm_<node|way>_<id>`. */
export function osmStoreId(type: "node" | "way", id: number | string): string {
  return `osm_${type}_${String(id)}`;
}

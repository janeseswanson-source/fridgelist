import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { matchKey } from "../lib/normalize.js";

/**
 * Meta's webhook signature: `X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body>` with
 * the app secret. Compared in constant time; anything malformed is rejected.
 */
export function validSignature(rawBody: Buffer, header: string | undefined, appSecret: string): boolean {
  if (!appSecret || !header?.startsWith("sha256=")) return false;
  const expected = createHmac("sha256", appSecret).update(rawBody).digest();
  const given = Buffer.from(header.slice("sha256=".length), "hex");
  return given.length === expected.length && timingSafeEqual(given, expected);
}

/** A string field of an untrusted payload, else "". */
const str = (value: unknown): string => (typeof value === "string" ? value : "");

/** GET verification when the webhook is registered (`hub.mode=subscribe`). */
export function verifyChallenge(
  query: Record<string, unknown>,
  verifyToken: string,
): { status: number; body: string } {
  const ok = query["hub.mode"] === "subscribe" && !!verifyToken && query["hub.verify_token"] === verifyToken;
  return ok ? { status: 200, body: str(query["hub.challenge"]) } : { status: 403, body: "" };
}

/** CONTRACT v4 §3.19: only the SHA-256 of the number (E.164 digits, no "+") is stored. */
export function phoneHash(phone: string): string {
  return createHash("sha256").update(phone.replace(/\D/g, "")).digest("hex");
}

export type Command =
  | { kind: "link"; code: string }
  | { kind: "list" }
  | { kind: "got"; text: string }
  | { kind: "help" }
  | { kind: "add"; text: string };

/** Command words in ar/tr/en, as match keys. Anything else is items to add. */
const WORDS = {
  link: ["link", "ربط", "bagla", "bağla"],
  list: ["list", "liste", "listem", "القائمه", "قائمه", "اللسته"],
  got: ["got", "bought", "اشتريت", "جبت", "aldim", "aldım"],
  help: ["help", "?", "مساعده", "yardim", "yardım"],
  add: ["add", "اضف", "ekle"],
} as const;

const keyed = Object.fromEntries(
  Object.entries(WORDS).map(([k, words]) => [k, new Set(words.map((w) => matchKey(w, true)))]),
) as Record<keyof typeof WORDS, Set<string>>;

export function parseCommand(text: string): Command {
  const trimmed = text.trim();
  const [first = "", ...rest] = trimmed.split(/\s+/u);
  const head = matchKey(first, true);
  const tail = rest.join(" ").trim();
  if (keyed.link.has(head) && tail) return { kind: "link", code: tail.toUpperCase().replace(/[\s-]/g, "") };
  if (keyed.list.has(head) && !tail) return { kind: "list" };
  if (keyed.got.has(head) && tail) return { kind: "got", text: tail };
  if (keyed.help.has(head) && !tail) return { kind: "help" };
  if (keyed.add.has(head) && tail) return { kind: "add", text: tail };
  return { kind: "add", text: trimmed };
}

/** The parts of Meta's webhook payload we use. */
export interface InboundMessage {
  id: string;
  from: string;
  phoneNumberId: string;
  type: string;
  text?: string;
  audioId?: string;
}

export function inboundMessages(body: unknown): InboundMessage[] {
  const out: InboundMessage[] = [];
  const entries = (body as { entry?: unknown[] } | null)?.entry ?? [];
  for (const entry of entries) {
    for (const change of (entry as { changes?: unknown[] }).changes ?? []) {
      const value = (change as { value?: Record<string, unknown> }).value ?? {};
      const phoneNumberId = str(
        (value.metadata as { phone_number_id?: unknown } | undefined)?.phone_number_id,
      );
      for (const m of (value.messages as Record<string, unknown>[] | undefined) ?? []) {
        const message: InboundMessage = {
          id: str(m.id),
          from: str(m.from),
          phoneNumberId,
          type: str(m.type),
        };
        const body = (m.text as { body?: unknown } | undefined)?.body;
        if (typeof body === "string") message.text = body;
        const audio = (m.audio as { id?: unknown } | undefined)?.id;
        if (typeof audio === "string") message.audioId = audio;
        if (message.from && message.phoneNumberId) out.push(message);
      }
    }
  }
  return out;
}

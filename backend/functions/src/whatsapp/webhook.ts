import { FieldValue, Timestamp, type Firestore } from "firebase-admin/firestore";
import type { UiLanguage } from "../config.js";
import { format, uiLanguageOr } from "../i18n/index.js";
import { displayTitle } from "../lib/display.js";
import type { Flags } from "../lib/flags.js";
import { LocalSplitter } from "../lib/localSplit.js";
import { log } from "../lib/log.js";
import { matchKey } from "../lib/normalize.js";
import { consume } from "../lib/rateLimit.js";
import type { SpeechToText } from "../stt/speechToText.js";
import type { WhatsAppClient } from "./client.js";
import { inboundMessages, parseCommand, phoneHash, validSignature, type InboundMessage } from "./protocol.js";

export interface WebhookDeps {
  db: Firestore;
  flags: Flags;
  client: WhatsAppClient;
  stt: SpeechToText;
  appSecret: string;
  now: Date;
}

/** A reply language before the number is linked: by script, else English. */
function guessLanguage(text: string): UiLanguage {
  if (/[\u0600-ۿ]/u.test(text)) return "ar";
  if (/[ğĞışŞİçÇöÖüÜ]/u.test(text)) return "tr";
  return "en";
}

interface Linked {
  uid: string;
  householdId: string;
  role: string;
  language: string;
  ui: UiLanguage;
}

async function linkedUser(db: Firestore, hash: string): Promise<Linked | null> {
  const link = await db.doc(`whatsappNumbers/${hash}`).get();
  if (!link.exists) return null;
  const uid = String(link.get("uid"));
  const householdId = String(link.get("householdId"));
  const [member, user] = await Promise.all([
    db.doc(`households/${householdId}/members/${uid}`).get(),
    db.doc(`users/${uid}`).get(),
  ]);
  if (!member.exists) {
    await link.ref.delete(); // left the household: the link goes too
    return null;
  }
  return {
    uid,
    householdId,
    role: String(member.get("role")),
    language: String(member.get("language") ?? "en"),
    ui: uiLanguageOr(user.get("uiLanguage")),
  };
}

/** The household's first list that isn't archived (the one WhatsApp adds go to). */
async function defaultList(db: Firestore, householdId: string) {
  const lists = await db.collection(`households/${householdId}/lists`).orderBy("order").get();
  return lists.docs.find((d) => d.get("archived") !== true) ?? null;
}

async function handle(deps: WebhookDeps, m: InboundMessage): Promise<string> {
  const { db, now } = deps;
  const hash = phoneHash(m.from);
  let text = m.text ?? "";
  const user = await linkedUser(db, hash);
  const lang = user?.ui ?? guessLanguage(text);

  try {
    await consume(db, "whatsapp", hash, now);
  } catch {
    return format(lang, "waTooMany");
  }

  if (m.type === "audio" && m.audioId) {
    if (!user) return format(lang, "waNotLinked");
    try {
      const audio = await deps.client.downloadMedia(m.audioId);
      text = (await deps.stt.transcribe(audio.bytes, audio.mimeType, [user.language])).text;
    } catch {
      text = "";
    }
    if (!text) return format(lang, "waVoiceFailed");
  }

  const command = parseCommand(text);
  if (command.kind === "link") {
    const ref = db.doc(`whatsappLinkCodes/${command.code}`);
    const code = await ref.get();
    const expires = (code.get("expiresAt") as Timestamp | undefined)?.toMillis() ?? 0;
    if (!code.exists || expires <= now.getTime()) return format(lang, "waLinkUnknown");
    const householdId = String(code.get("householdId"));
    await db
      .doc(`whatsappNumbers/${hash}`)
      .set({ uid: code.get("uid") as unknown, householdId, linkedAt: Timestamp.fromDate(now) });
    await ref.delete();
    const household = await db.doc(`households/${householdId}`).get();
    const linked = await linkedUser(db, hash);
    return format(linked?.ui ?? lang, "waLinked", { household: String(household.get("name") ?? "") });
  }
  if (!user) return format(lang, "waNotLinked");
  if (command.kind === "help") return format(lang, "waHelp");

  const list = await defaultList(db, user.householdId);
  if (!list) return format(lang, "waHelp");
  const items = list.ref.collection("items");
  const defaultLanguage = String(
    (await db.doc(`households/${user.householdId}`).get()).get("defaultLanguage") ?? "en",
  );
  const title = (d: FirebaseFirestore.DocumentSnapshot) =>
    displayTitle(
      {
        name: String(d.get("name") ?? ""),
        lang: d.get("lang") as string | undefined,
        names: d.get("names") as Record<string, string> | undefined,
      },
      user.language,
      defaultLanguage,
    );

  if (command.kind === "list") {
    const open = await items.where("status", "in", ["open", "claimed"]).get();
    const listName = String(list.get("name") ?? "");
    if (open.empty) return format(lang, "waListEmpty", { list: listName });
    return [`${listName}:`, ...open.docs.map((d) => `• ${title(d)}`)].join("\n");
  }

  if (command.kind === "got") {
    const wanted = matchKey(command.text, true);
    const open = await items.where("status", "in", ["open", "claimed"]).get();
    const hit = open.docs.find((d) => {
      const names = [
        String(d.get("name") ?? ""),
        ...Object.values((d.get("names") as Record<string, string> | undefined) ?? {}),
      ];
      return names.some((n) => matchKey(n, true) === wanted);
    });
    if (!hit) return format(lang, "waGotNone", { text: command.text });
    await hit.ref.update({
      status: "done",
      doneBy: user.uid,
      doneAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
    return format(lang, "waGot", { item: title(hit) });
  }

  const split = new LocalSplitter().split(command.text, [user.language]).slice(0, 10);
  if (split.length === 0) return format(lang, "waHelp");
  const batch = db.batch();
  for (const item of split) {
    batch.create(items.doc(), {
      name: item.name,
      ...(item.qty != null && item.qty > 0 && item.qty <= 999 ? { qty: item.qty } : {}),
      ...(item.unit ? { unit: item.unit } : {}),
      urgent: false,
      // Kids' adds are requests, from WhatsApp too (CONTRACT §3.6).
      status: user.role === "kid" ? "pending" : "open",
      addedBy: `wa:${user.uid}`,
      addedVia: "whatsapp",
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });
  }
  await batch.commit();
  return format(lang, "waAdded", { count: split.length }, split.length);
}

/**
 * POST handler: signature first (401), then nothing at all while `whatsapp_enabled` is off (200 so
 * Meta doesn't retry). Each message gets one reply. Numbers are never logged or stored in clear.
 */
export async function handleWebhookPost(
  deps: WebhookDeps,
  rawBody: Buffer,
  signature: string | undefined,
  body: unknown,
): Promise<number> {
  if (!validSignature(rawBody, signature, deps.appSecret)) return 401;
  if (!(await deps.flags.enabled("whatsapp_enabled"))) return 200;
  for (const message of inboundMessages(body)) {
    const reply = await handle(deps, message);
    try {
      await deps.client.sendText(message.phoneNumberId, message.from, reply);
    } catch (error) {
      log.warn("whatsapp_reply_failed", { code: (error as Error).message });
    }
  }
  return 200;
}

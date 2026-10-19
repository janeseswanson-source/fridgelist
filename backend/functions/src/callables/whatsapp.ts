import { Timestamp, getFirestore, type Firestore } from "firebase-admin/firestore";
import { defineSecret, defineString } from "firebase-functions/params";
import { HttpsError, onCall, onRequest } from "firebase-functions/v2/https";
import { z } from "zod";
import { inviteCode } from "../lib/ids.js";
import { remoteFlags, type Flags } from "../lib/flags.js";
import { parse, requireMember, requireUid } from "../lib/requests.js";
import { id } from "../lib/schemas.js";
import { GoogleSpeechToText } from "../stt/speechToText.js";
import { GraphWhatsAppClient } from "../whatsapp/client.js";
import { verifyChallenge } from "../whatsapp/protocol.js";
import { handleWebhookPost } from "../whatsapp/webhook.js";

/** Phase 2 secrets (prompt 01 stack, B10) and the bot's public number for the app to show. */
export const WHATSAPP_TOKEN = defineSecret("WHATSAPP_TOKEN");
export const WHATSAPP_APP_SECRET = defineSecret("WHATSAPP_APP_SECRET");
export const WHATSAPP_VERIFY_TOKEN = defineSecret("WHATSAPP_VERIFY_TOKEN");
export const WHATSAPP_BOT_NUMBER = defineString("WHATSAPP_BOT_NUMBER", { default: "" });

/** CONTRACT v4 §3.18: link codes live 15 minutes. */
export const LINK_CODE_MINUTES = 15;

export const LinkWhatsAppRequest = z.object({ householdId: id });

/** CONTRACT §4 `linkWhatsApp` → `{ code, botNumber }`; off unless `whatsapp_enabled`. */
export async function linkWhatsAppCore(
  db: Firestore,
  uid: string,
  data: unknown,
  flags: Flags,
  botNumber: string,
  now: Date = new Date(),
): Promise<{ code: string; botNumber: string }> {
  const input = parse(LinkWhatsAppRequest, data);
  if (!(await flags.enabled("whatsapp_enabled")))
    throw new HttpsError("failed-precondition", "WhatsApp is not available yet.");
  await requireMember(db, input.householdId, uid);
  const code = inviteCode();
  await db.doc(`whatsappLinkCodes/${code}`).create({
    uid,
    householdId: input.householdId,
    expiresAt: Timestamp.fromMillis(now.getTime() + LINK_CODE_MINUTES * 60_000),
  });
  return { code, botNumber };
}

export const linkWhatsApp = onCall((request) =>
  linkWhatsAppCore(
    getFirestore(),
    requireUid(request),
    request.data,
    remoteFlags,
    WHATSAPP_BOT_NUMBER.value(),
  ),
);

/** Meta webhook: GET verification, POST messages (signature-checked, flag-gated). */
export const whatsappWebhook = onRequest(
  { secrets: [WHATSAPP_TOKEN, WHATSAPP_APP_SECRET, WHATSAPP_VERIFY_TOKEN], timeoutSeconds: 60 },
  async (req, res) => {
    if (req.method === "GET") {
      const result = verifyChallenge(req.query, WHATSAPP_VERIFY_TOKEN.value());
      res.status(result.status).send(result.body);
      return;
    }
    if (req.method !== "POST") {
      res.status(405).send("");
      return;
    }
    const status = await handleWebhookPost(
      {
        db: getFirestore(),
        flags: remoteFlags,
        client: new GraphWhatsAppClient(WHATSAPP_TOKEN.value()),
        stt: new GoogleSpeechToText(process.env.GCLOUD_PROJECT ?? ""),
        appSecret: WHATSAPP_APP_SECRET.value(),
        now: new Date(),
      },
      req.rawBody,
      req.get("X-Hub-Signature-256"),
      req.body,
    );
    res.status(status).send("");
  },
);

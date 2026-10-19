import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { HttpsError, onCall } from "firebase-functions/v2/https";
import { z } from "zod";
import { log } from "../lib/log.js";
import { consume } from "../lib/rateLimit.js";
import { parse, requireUid } from "../lib/requests.js";
import {
  GoogleSpeechToText,
  SpeechUnavailableError,
  recognitionLanguages,
  type SpeechToText,
} from "../stt/speechToText.js";

/** CONTRACT §4: ≤ 1 MB base64, ≤ 15 s (the apps stop recording at 15 s). */
export const MAX_BASE64 = 1_000_000;

export const TranscribeAudioRequest = z.object({
  audioBase64: z.string().min(1).max(MAX_BASE64),
  mimeType: z.enum(["audio/ogg", "audio/mp4", "audio/wav"]),
  localeHints: z.array(z.string().max(20)).max(5),
});

export interface TranscribeAudioResponse {
  text: string;
  locale: string;
  confidence: number;
}

/**
 * Cloud fallback for voice add when on-device recognition can't do the language. The audio is
 * never stored or logged. STT failures → `unavailable` (the apps say "voice needs the internet"
 * and typing still works).
 */
export async function transcribeAudioCore(
  db: Firestore,
  uid: string,
  data: unknown,
  stt: SpeechToText,
): Promise<TranscribeAudioResponse> {
  const input = parse(TranscribeAudioRequest, data);
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(input.audioBase64))
    throw new HttpsError("invalid-argument", "Invalid: audioBase64");
  const audio = Buffer.from(input.audioBase64, "base64");
  if (audio.length === 0) throw new HttpsError("invalid-argument", "Invalid: audioBase64");
  await consume(db, "transcribeAudio", uid);

  const languages = recognitionLanguages(input.localeHints);
  try {
    const transcript = await stt.transcribe(audio, input.mimeType, languages);
    log.info("transcribe", {
      bytes: audio.length,
      mimeType: input.mimeType,
      empty: transcript.text.length === 0,
    });
    return {
      text: transcript.text,
      locale: transcript.locale ?? languages[0] ?? "en-US",
      confidence: transcript.confidence,
    };
  } catch (error) {
    if (error instanceof SpeechUnavailableError)
      throw new HttpsError("unavailable", "Speech service unavailable.");
    throw error;
  }
}

export const transcribeAudio = onCall({ memory: "512MiB" }, (request) =>
  transcribeAudioCore(
    getFirestore(),
    requireUid(request),
    request.data,
    new GoogleSpeechToText(process.env.GCLOUD_PROJECT ?? ""),
  ),
);

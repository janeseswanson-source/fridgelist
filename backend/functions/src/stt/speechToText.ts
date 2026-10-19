import { v2 } from "@google-cloud/speech";

export interface Transcript {
  text: string;
  /** BCP-47 of the recognized language, when the engine reports it. */
  locale: string | null;
  confidence: number;
}

export interface SpeechToText {
  /** Throws SpeechUnavailableError when the service can't be reached or fails. */
  transcribe(audio: Buffer, mimeType: string, languageCodes: string[]): Promise<Transcript>;
}

export class SpeechUnavailableError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "SpeechUnavailableError";
  }
}

/** Primary language → the region the apps most likely mean (Turkey, Saudi Arabia, UAE markets). */
const DEFAULT_REGION: Record<string, string> = {
  ar: "ar-SA",
  tr: "tr-TR",
  en: "en-US",
  fil: "fil-PH",
  hi: "hi-IN",
  ur: "ur-PK",
};

/** Up to 3 recognition languages from the locale hints ("ar" → "ar-SA"; "ar-EG" stays). */
export function recognitionLanguages(localeHints: readonly string[]): string[] {
  const codes = localeHints
    .map((hint) => (hint.includes("-") ? hint : (DEFAULT_REGION[hint.toLowerCase()] ?? null)))
    .filter((code): code is string => code !== null);
  const unique = [...new Set(codes)].slice(0, 3);
  return unique.length > 0 ? unique : ["en-US"];
}

/**
 * Google Cloud Speech-to-Text v2 (ARCHITECTURE §3.5): auto-decoding handles OGG/Opus, AAC/MP4 and
 * WAV. Uses the project's default recognizer. Needs the Speech-to-Text API enabled on the
 * project (owner action, docs/RUNBOOK.md).
 */
export class GoogleSpeechToText implements SpeechToText {
  private readonly client = new v2.SpeechClient();

  constructor(private readonly projectId: string) {}

  async transcribe(audio: Buffer, _mimeType: string, languageCodes: string[]): Promise<Transcript> {
    try {
      const [response] = await this.client.recognize({
        recognizer: `projects/${this.projectId}/locations/global/recognizers/_`,
        config: { autoDecodingConfig: {}, languageCodes, model: "short" },
        content: audio,
      });
      const best = response.results?.map((r) => r.alternatives?.[0]).filter((a) => a?.transcript) ?? [];
      const text = best
        .map((a) => a?.transcript ?? "")
        .join(" ")
        .trim();
      const locale = response.results?.find((r) => r.languageCode)?.languageCode ?? null;
      const confidence = best.length > 0 ? Math.min(...best.map((a) => a?.confidence ?? 0)) : 0;
      return { text, locale, confidence };
    } catch (error) {
      const code = (error as { code?: number }).code;
      throw new SpeechUnavailableError(`speech error ${String(code ?? "unknown")}`);
    }
  }
}

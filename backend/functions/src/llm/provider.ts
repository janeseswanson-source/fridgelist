import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { z } from "zod";
import { CATEGORIES } from "../lib/categories.js";
import { ITEM_LANGUAGES } from "../config.js";
import { LlmUnavailableError, type LlmTransport } from "./transport.js";

export interface ClassifyInput {
  /** Item names exactly as typed. */
  names: string[];
  localeHints: string[];
  /** Languages to translate into (household `memberLanguages`). */
  targetLanguages: string[];
}

const ClassifiedItem = z.object({
  canonicalId: z.string().nullable(),
  category: z.enum(CATEGORIES),
  lang: z.enum(ITEM_LANGUAGES),
  names: z.record(z.string(), z.string().min(1).max(80)),
  confidence: z.number().min(0).max(1),
});
export type ClassifiedItem = z.infer<typeof ClassifiedItem>;

const ClassifyOutput = z.object({ items: z.array(ClassifiedItem) });

const SYSTEM = readFileSync(resolve(import.meta.dirname, "prompts/classify.md"), "utf8");

export interface LlmProvider {
  /** One result per input name, in order. Throws LlmUnavailableError on any failure. */
  classify(input: ClassifyInput): Promise<ClassifiedItem[]>;
}

/**
 * ARCHITECTURE §3.5: JSON through a forced tool call, validated with zod; invalid output → one
 * retry → LlmUnavailableError (callers fall back: local splitter for parsing, skip translation).
 */
export class ClassifyingLlm implements LlmProvider {
  constructor(
    private readonly transport: LlmTransport,
    private readonly model: string,
    private readonly catalogIds: readonly string[],
  ) {}

  async classify(input: ClassifyInput): Promise<ClassifiedItem[]> {
    if (input.names.length === 0) return [];
    const request = {
      model: this.model,
      system: `${SYSTEM}\nCatalog ids: ${this.catalogIds.join(", ")}`,
      user: JSON.stringify({
        items: input.names,
        localeHints: input.localeHints,
        targetLanguages: input.targetLanguages,
      }),
      toolName: "classify_items",
      toolDescription: "Return one classification per input item, in order.",
      inputSchema: z.toJSONSchema(ClassifyOutput) as Record<string, unknown>,
      maxTokens: 2_000,
    };
    for (let attempt = 0; attempt < 2; attempt++) {
      const parsed = ClassifyOutput.safeParse(await this.transport.complete(request));
      if (parsed.success && parsed.data.items.length === input.names.length) {
        const ids = new Set(this.catalogIds);
        // Never trust an id the catalog doesn't have.
        return parsed.data.items.map((item) => ({
          ...item,
          canonicalId: item.canonicalId !== null && ids.has(item.canonicalId) ? item.canonicalId : null,
        }));
      }
    }
    throw new LlmUnavailableError("invalid output twice");
  }
}

/** Used when no API key is configured: every call falls back. */
export const NO_LLM: LlmProvider = {
  classify: () => Promise.reject(new LlmUnavailableError("no LLM configured")),
};

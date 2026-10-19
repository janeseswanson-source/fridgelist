import type { ClassifiedItem, ClassifyInput, LlmProvider } from "../src/llm/provider.js";
import { LlmUnavailableError } from "../src/llm/transport.js";
import type { CachedCandidate, CandidateCache } from "../src/lib/resolve.js";

/** An LLM that answers from a table (by name) and records every call. */
export class FakeLlm implements LlmProvider {
  readonly calls: ClassifyInput[] = [];
  constructor(
    private readonly answers: Record<string, Partial<ClassifiedItem>> = {},
    private readonly down = false,
  ) {}

  classify(input: ClassifyInput): Promise<ClassifiedItem[]> {
    this.calls.push(input);
    if (this.down) return Promise.reject(new LlmUnavailableError("down"));
    return Promise.resolve(
      input.names.map((name) => ({
        canonicalId: null,
        category: "other",
        lang: "en",
        names: Object.fromEntries(input.targetLanguages.map((l) => [l, `${name} (${l})`])),
        confidence: 0.8,
        ...this.answers[name],
      })),
    );
  }
}

export class MemoryCache implements CandidateCache {
  readonly entries = new Map<string, CachedCandidate>();
  get(normalized: string) {
    return Promise.resolve(this.entries.get(normalized) ?? null);
  }
  put(normalized: string, candidate: CachedCandidate) {
    this.entries.set(normalized, candidate);
    return Promise.resolve();
  }
}

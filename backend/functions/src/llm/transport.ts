import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

/**
 * One forced tool call to a model: the request is plain JSON so it can be hashed, recorded and
 * replayed (ARCHITECTURE §3.5: golden tests replay recordings; CI never calls the API).
 */
export interface ToolRequest {
  model: string;
  system: string;
  user: string;
  toolName: string;
  toolDescription: string;
  inputSchema: Record<string, unknown>;
  maxTokens: number;
}

export interface LlmTransport {
  /** Returns the tool input the model produced (unvalidated). */
  complete(request: ToolRequest): Promise<unknown>;
}

/** The model, the API or a recording is not available: callers fall back (never block an add). */
export class LlmUnavailableError extends Error {
  constructor(reason: string) {
    super(reason);
    this.name = "LlmUnavailableError";
  }
}

export function recordingKey(request: ToolRequest): string {
  return createHash("sha256").update(JSON.stringify(request)).digest("hex").slice(0, 24);
}

/** Replays `test/recordings/<key>.json`; a missing recording is "unavailable". */
export class ReplayTransport implements LlmTransport {
  constructor(private readonly dir: string) {}

  complete(request: ToolRequest): Promise<unknown> {
    const file = join(this.dir, `${recordingKey(request)}.json`);
    if (!existsSync(file)) return Promise.reject(new LlmUnavailableError("no recording"));
    const recorded = JSON.parse(readFileSync(file, "utf8")) as { output: unknown };
    return Promise.resolve(recorded.output);
  }
}

/** `RECORD=1`: calls the real transport once and saves the response for replay. */
export class RecordingTransport implements LlmTransport {
  constructor(
    private readonly real: LlmTransport,
    private readonly dir: string,
  ) {}

  async complete(request: ToolRequest): Promise<unknown> {
    const output = await this.real.complete(request);
    mkdirSync(this.dir, { recursive: true });
    const file = join(this.dir, `${recordingKey(request)}.json`);
    writeFileSync(
      file,
      `${JSON.stringify({ request: { model: request.model, user: request.user }, output }, null, 2)}\n`,
    );
    return output;
  }
}

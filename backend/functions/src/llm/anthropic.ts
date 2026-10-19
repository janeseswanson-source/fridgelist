import Anthropic from "@anthropic-ai/sdk";
import { LlmUnavailableError, type LlmTransport, type ToolRequest } from "./transport.js";

/** Anthropic Messages API with a forced tool call (structured JSON out). Temperature 0. */
export class AnthropicTransport implements LlmTransport {
  private readonly client: Anthropic;

  constructor(apiKey: string) {
    this.client = new Anthropic({ apiKey, maxRetries: 1, timeout: 20_000 });
  }

  async complete(request: ToolRequest): Promise<unknown> {
    let response: Anthropic.Message;
    try {
      response = await this.client.messages.create({
        model: request.model,
        max_tokens: request.maxTokens,
        temperature: 0,
        system: request.system,
        messages: [{ role: "user", content: request.user }],
        tools: [
          {
            name: request.toolName,
            description: request.toolDescription,
            input_schema: request.inputSchema as Anthropic.Tool.InputSchema,
          },
        ],
        tool_choice: { type: "tool", name: request.toolName },
      });
    } catch (error) {
      // Status only, never the request (it holds user text).
      const status = (error as { status?: number }).status;
      throw new LlmUnavailableError(`anthropic error ${String(status ?? "network")}`);
    }
    const block = response.content.find((b) => b.type === "tool_use");
    if (!block) throw new LlmUnavailableError("no tool_use block");
    return block.input;
  }
}

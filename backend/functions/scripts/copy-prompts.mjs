// tsc does not copy non-TS files: put the LLM prompts (src/llm/prompts/*.md) next to the build output.
import { cpSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
cpSync(resolve(here, "../src/llm/prompts"), resolve(here, "../lib/llm/prompts"), { recursive: true });

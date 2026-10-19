// Copies the shared catalog seed (repo-root fixtures/, CLAUDE.md rule 6) into src/generated/ so the
// deployed functions bundle it. Runs before build, typecheck and tests; the output is git-ignored.
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const target = resolve(here, "../src/generated");
mkdirSync(target, { recursive: true });
copyFileSync(resolve(here, "../../../fixtures/catalog_seed.json"), resolve(target, "catalog_seed.json"));
// Remote Config defaults (CONTRACT §10): the server's fallback when Remote Config can't be read.
copyFileSync(
  resolve(here, "../../remoteconfig.template.json"),
  resolve(target, "remoteconfig.template.json"),
);

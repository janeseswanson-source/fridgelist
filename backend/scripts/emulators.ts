/**
 * `npm run emulators`: builds the functions, starts the Firebase Emulator Suite for the demo
 * project, waits until it is ready, then seeds it (scripts/emulator-seed.ts). Ctrl+C stops all.
 */
import { spawn, spawnSync } from "node:child_process";
import { existsSync, writeFileSync } from "node:fs";

const PROJECT = "demo-fridgelist";
const HUB = "http://127.0.0.1:4400/emulators";
const shell = process.platform === "win32";

// Secrets for the Functions emulator (git-ignored). An empty key means "no LLM": parseItems and
// onItemCreated use the catalog and fallbacks, and the emulator doesn't try Secret Manager.
// Put a real key here to use the LLM locally: ANTHROPIC_API_KEY=sk-ant-...
const secrets = "functions/.secret.local";
if (!existsSync(secrets)) writeFileSync(secrets, `ANTHROPIC_API_KEY=${"\n"}`);

const build = spawnSync("npm", ["--prefix", "functions", "run", "build"], {
  stdio: "inherit",
  shell,
});
if (build.status === 0)
  spawnSync("npm", ["run", "build:hosting"], { stdio: "inherit", shell });
if (build.status !== 0) process.exit(build.status ?? 1);

const firebase = spawn("firebase", ["emulators:start", "--project", PROJECT], {
  stdio: "inherit",
  shell,
});
firebase.on("exit", (code) => process.exit(code ?? 0));
process.on("SIGINT", () => firebase.kill("SIGINT"));

const HEALTH = `http://127.0.0.1:5001/${PROJECT}/me-central2/health`;
const ITEM = `http://127.0.0.1:8080/v1/projects/${PROJECT}/databases/(default)/documents/households/seed-home/lists/groceries/items/i01`;
const pause = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * Hub up is not enough: the seed's item writes must reach onItemCreated, which only fires once
 * the functions are loaded. `health` answering means the functions emulator loaded the code.
 */
async function ready(): Promise<boolean> {
  try {
    const response = await fetch(HUB);
    if (!response.ok) return false;
    const running = (await response.json()) as Record<string, unknown>;
    if (!["auth", "firestore", "functions"].every((name) => name in running))
      return false;
    const health = await fetch(HEALTH, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: '{"data":{}}',
    });
    return health.ok;
  } catch {
    return false;
  }
}

/** The seed is only useful once the trigger has translated it (lang set on a seeded item). */
async function enriched(): Promise<boolean> {
  try {
    const doc = (await (
      await fetch(ITEM, { headers: { Authorization: "Bearer owner" } })
    ).json()) as { fields?: { lang?: unknown } };
    return doc.fields?.lang !== undefined;
  } catch {
    return false;
  }
}

for (let attempt = 0; attempt < 120 && !(await ready()); attempt++)
  await pause(1_000);
if (!(await ready())) {
  process.stderr.write(
    "Emulators did not become ready in 2 minutes; not seeding.\n",
  );
} else {
  await pause(3_000); // Firestore triggers register just after the HTTP functions
  const seed = spawnSync("npx", ["tsx", "scripts/emulator-seed.ts"], {
    stdio: "inherit",
    shell,
  });
  if (seed.status !== 0)
    process.stderr.write(
      "Seeding failed (see above); emulators keep running.\n",
    );
  for (let attempt = 0; attempt < 20 && !(await enriched()); attempt++)
    await pause(1_000);
  process.stdout.write(
    (await enriched())
      ? "Seed items enriched by onItemCreated.\n"
      : "Seed items were not enriched (triggers not ready?): run `npm run seed` again.\n",
  );
}

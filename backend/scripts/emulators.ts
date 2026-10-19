/**
 * `npm run emulators`: builds the functions, starts the Firebase Emulator Suite for the demo
 * project, waits until it is ready, then seeds it (scripts/emulator-seed.ts). Ctrl+C stops all.
 */
import { spawn, spawnSync } from "node:child_process";

const PROJECT = "demo-fridgelist";
const HUB = "http://127.0.0.1:4400/emulators";
const shell = process.platform === "win32";

const build = spawnSync("npm", ["--prefix", "functions", "run", "build"], { stdio: "inherit", shell });
if (build.status !== 0) process.exit(build.status ?? 1);

const firebase = spawn("firebase", ["emulators:start", "--project", PROJECT], { stdio: "inherit", shell });
firebase.on("exit", (code) => process.exit(code ?? 0));
process.on("SIGINT", () => firebase.kill("SIGINT"));

async function ready(): Promise<boolean> {
  try {
    const response = await fetch(HUB);
    if (!response.ok) return false;
    const running = (await response.json()) as Record<string, unknown>;
    return ["auth", "firestore", "functions"].every((name) => name in running);
  } catch {
    return false;
  }
}

for (let attempt = 0; attempt < 120 && !(await ready()); attempt++) {
  await new Promise((resolve) => setTimeout(resolve, 1_000));
}
if (!(await ready())) {
  process.stderr.write("Emulators did not become ready in 2 minutes; not seeding.\n");
} else {
  const seed = spawnSync("npx", ["tsx", "scripts/emulator-seed.ts"], { stdio: "inherit", shell });
  if (seed.status !== 0) process.stderr.write("Seeding failed (see above); emulators keep running.\n");
}

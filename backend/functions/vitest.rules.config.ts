import { defineConfig } from "vitest/config";

/** Security-rules tests: run inside `firebase emulators:exec` (npm run test:rules). */
export default defineConfig({
  test: {
    include: ["test/rules/**/*.test.ts"],
    environment: "node",
    // One emulator, shared state: files run one after another.
    fileParallelism: false,
    testTimeout: 20_000,
    hookTimeout: 30_000,
  },
});

import { defineConfig } from "vitest/config";

/** Security-rules and integration tests: run inside `firebase emulators:exec` (npm run test:emulator). */
export default defineConfig({
  test: {
    include: ["test/rules/**/*.test.ts", "test/integration/**/*.test.ts"],
    environment: "node",
    // One emulator, shared state: files run one after another.
    fileParallelism: false,
    testTimeout: 20_000,
    hookTimeout: 30_000,
  },
});

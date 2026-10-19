import { defineConfig } from "vitest/config";

/** Unit tests (no emulator). Rules tests have their own config: vitest.emulator.config.ts. */
export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    exclude: ["test/rules/**", "test/integration/**", "node_modules/**"],
    environment: "node",
  },
});

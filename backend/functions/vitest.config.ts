import { defineConfig } from "vitest/config";

/** Unit tests (no emulator). Rules tests have their own config: vitest.rules.config.ts. */
export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    exclude: ["test/rules/**", "node_modules/**"],
    environment: "node",
  },
});

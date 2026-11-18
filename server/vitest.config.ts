import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  // Resolve @overdrive/contracts to its TypeScript sources, so the tests work on a fresh checkout without
  // building it first. An alias, not the "source" export condition: some dependencies (the MCP client's
  // eventsource-parser) use that condition for their own .ts files, which Node refuses to load from node_modules.
  resolve: {
    alias: {
      "@overdrive/contracts": fileURLToPath(new URL("../contracts/src/index.ts", import.meta.url)),
    },
  },
  test: {
    // Against a real PostgreSQL (TEST_DATABASE_URL) the test files share one schema: take turns.
    fileParallelism: !process.env.TEST_DATABASE_URL,
  },
});

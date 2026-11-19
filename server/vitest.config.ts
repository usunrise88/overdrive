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
    // Every file starts its own in-process PostgreSQL (PGlite) and many wait on timers of a few seconds (the bot's polling, the
    // loops): on a machine with many cores one worker per core made the burst at the start starve those waits and a test now and
    // then failed on time alone. The suite takes as long with fewer workers.
    maxWorkers: 12,
    // Only an upper bound for a test that hangs: on a machine that is busy with other work a test of the polling bots
    // or the loops needs more than the default five seconds, and failing on time alone says nothing about the code.
    testTimeout: 20_000,
    hookTimeout: 30_000,
  },
});

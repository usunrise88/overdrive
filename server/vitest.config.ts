import { defineConfig } from "vitest/config";

export default defineConfig({
  // Resolve @overdrive/contracts to its TypeScript sources (its "source" export condition), so the
  // tests and the typecheck work on a fresh checkout without building it first.
  resolve: { conditions: ["source"] },
  ssr: { resolve: { conditions: ["source"] } },
  test: {
    // Against a real PostgreSQL (TEST_DATABASE_URL) the test files share one schema: take turns.
    fileParallelism: !process.env.TEST_DATABASE_URL,
  },
});

import { describeConfigError } from "./config.ts";
import { consoleLogger } from "./log.ts";
import { messageOf, startServer } from "./start.ts";

/** The process wrapper: environment in, signals out. Everything that can be tested is in `start.ts`. */
const log = consoleLogger(process.env.LOG_LEVEL);

try {
  const server = await startServer({ env: process.env, log });
  if (server.mode === "migrate-only") {
    // The report holds counts, hashes and key names, never a value.
    process.stdout.write(`${JSON.stringify(server.report, null, 2)}\n`);
    await server.close();
    process.exit(0);
  }
  for (const signal of ["SIGINT", "SIGTERM"] as const) {
    process.once(signal, () => {
      log.info({ signal }, "shutting down");
      void server.close().then(() => process.exit(0));
    });
  }
} catch (err) {
  const message =
    err instanceof Error && err.name === "ZodError" ? describeConfigError(err) : messageOf(err);
  log.error({}, `the server did not start: ${message}`);
  process.exit(1);
}

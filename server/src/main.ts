import { buildApp } from "./app.ts";
import { type Config, describeConfigError, loadConfig } from "./config.ts";
import { createPgDb } from "./db.ts";
import { migrate } from "./migrate.ts";

let config: Config;
try {
  config = loadConfig();
} catch (err) {
  console.error(`Invalid configuration:\n${describeConfigError(err)}`);
  process.exit(1);
}
const db = createPgDb(config.DATABASE_URL, (err) =>
  console.error("database connection error", err),
);
const app = buildApp({ config, db });

for (const signal of ["SIGINT", "SIGTERM"] as const) {
  process.once(signal, () => {
    app.log.info({ signal }, "shutting down");
    void app
      .close()
      .then(() => db.close())
      .then(() => process.exit(0));
  });
}

try {
  const applied = await migrate(db);
  if (applied.length > 0) app.log.info({ migrations: applied }, "database migrated");
  await app.listen({ host: config.HOST, port: config.PORT });
  // The upload worker and the plan refresher run only once the schema is there and the port is open.
  app.services.start();
} catch (err) {
  app.log.error(err);
  process.exit(1);
}

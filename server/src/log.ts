/**
 * The little the modules need from a logger: Fastify's (pino) satisfies it, and the tests pass one
 * that collects lines. Never log a secret: no API key, token or `authorization` header.
 */
export interface Logger {
  debug(fields: object, message?: string): void;
  info(fields: object, message?: string): void;
  warn(fields: object, message?: string): void;
  error(fields: object, message?: string): void;
}

const LEVELS = ["debug", "info", "warn", "error"] as const;

const plain = (value: unknown): unknown =>
  value instanceof Error ? { name: value.name, message: value.message } : value;

/**
 * A logger that writes one JSON line per message to stdout (stderr for errors), for the start-up code that runs before
 * Fastify's own logger exists. Levels below `level` are dropped; an `Error` in the fields is reduced to its name and
 * message (no stack, no `cause`), so a connection string inside a driver error's details is not printed.
 */
export function consoleLogger(level = "info"): Logger {
  const floor =
    level === "silent" || level === "fatal" ? LEVELS.length : LEVELS.indexOf(level as never);
  const min = floor < 0 ? 1 : floor;
  const write =
    (index: number) =>
    (fields: object, message?: string): void => {
      if (index < min) return;
      const body = Object.fromEntries(Object.entries(fields).map(([k, v]) => [k, plain(v)]));
      const line = JSON.stringify({
        level: LEVELS[index],
        time: Date.now(),
        ...body,
        msg: message,
      });
      if (index === 3) console.error(line);
      else console.log(line);
    };
  return { debug: write(0), info: write(1), warn: write(2), error: write(3) };
}

/** Drops everything. */
export const silentLogger: Logger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
};

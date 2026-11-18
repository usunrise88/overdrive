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

/** Drops everything. */
export const silentLogger: Logger = {
  debug() {},
  info() {},
  warn() {},
  error() {},
};

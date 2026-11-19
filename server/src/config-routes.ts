import { ROUTE_DEFAULT_STEP_METERS } from "@overdrive/contracts";
import { z } from "zod";

/**
 * The settings of the routes (spec §11.4, ADR 0036), as a zod shape that `config.ts` spreads into its schema. Every variable is
 * optional; the defaults stand in for a missing one.
 */

/** Empty values (compose passes `${VAR:-}`) count as not set. */
const blankIsUnset = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

/** How long the road routing engine may take to answer, in seconds. */
export const DEFAULT_BROUTER_TIMEOUT_SECONDS = 30;

const wholeNumber = (name: string, min: number, max: number, fallback: number) =>
  z.preprocess(
    blankIsUnset,
    z.coerce
      .number({ error: `${name} must be a whole number` })
      .int(`${name} must be a whole number`)
      .min(min, `${name} must be at least ${min}`)
      .max(max, `${name} must be at most ${max}`)
      .default(fallback),
  );

/** The fields `config.ts` adds to its schema. */
export const routesConfigShape = {
  // The address of the BRouter server (docs/deploy.md, section 20), such as http://brouter:17777. Empty means road routing is off:
  // create_route with mode waypoints says so; GPX and synthetic profiles need no engine.
  BROUTER_URL: z.preprocess(
    blankIsUnset,
    z
      .string()
      .trim()
      .transform((raw, ctx): string => {
        try {
          const url = new URL(raw);
          if (url.protocol !== "https:" && url.protocol !== "http:") throw new Error("scheme");
          if (url.username !== "" || url.password !== "") throw new Error("credentials");
          if (url.search !== "" || url.hash !== "") throw new Error("query");
          return `${url.origin}${url.pathname.replace(/\/+$/, "")}`;
        } catch {
          ctx.addIssue({
            code: "custom",
            message:
              "BROUTER_URL must be an http(s) address with no credentials, query or fragment, such as http://brouter:17777",
          });
          return z.NEVER;
        }
      })
      .optional(),
  ),
  // How long a routing request may take before the service gives up and says routing is unavailable.
  BROUTER_TIMEOUT_SECONDS: wholeNumber(
    "BROUTER_TIMEOUT_SECONDS",
    1,
    300,
    DEFAULT_BROUTER_TIMEOUT_SECONDS,
  ),
  // A route is resampled to one point every this many metres (the step grows for a route that would pass 5000 points).
  ROUTE_STEP_METERS: wholeNumber("ROUTE_STEP_METERS", 5, 200, ROUTE_DEFAULT_STEP_METERS),
};

/** The settings as `loadConfig` returns them (the defaults applied; `BROUTER_URL` absent when routing is off). */
export type RoutesConfig = z.infer<z.ZodObject<typeof routesConfigShape>>;

/** What the services read: every field optional, with the defaults above standing in for a missing one. */
export type RoutesSettings = Partial<RoutesConfig>;

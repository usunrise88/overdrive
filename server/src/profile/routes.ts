import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import { ProfilePatchError, type ProfileStore } from "./store.ts";

export interface ProfileRoutesOptions {
  store: ProfileStore;
}

/**
 * The rider's profile (spec §8.3, `contracts/schema/profile.schema.json`), mounted under `/v1`:
 *
 * - `GET /profile`: the profile as stored, or the defaults (no FTP, Coggan's zones, nothing known
 *   about the week) while nothing has been set.
 * - `PATCH /profile`: a partial update (`profile-patch.schema.json`), JSON or `application/merge-patch+json`.
 *   Answers the whole profile. A body that is not JSON is `400 invalid_json`; one that does not make a valid
 *   profile changes nothing and is `422 invalid_profile` with the reasons in the message.
 */
export function profileRoutes(app: FastifyInstance, options: ProfileRoutesOptions): void {
  const { store } = options;

  // JSON only: a plain-text body is a mistake to refuse, not a string to validate.
  app.removeContentTypeParser("text/plain");
  app.addContentTypeParser(
    "application/merge-patch+json",
    { parseAs: "string" },
    (_request, body, done) => {
      try {
        done(null, JSON.parse(body as string));
      } catch {
        done(Object.assign(new Error("The body is not valid JSON"), { statusCode: 400 }));
      }
    },
  );

  app.get("/profile", async (_request, reply) => {
    reply.header("cache-control", "no-store");
    return store.get();
  });

  app.patch("/profile", async (request, reply) => {
    reply.header("cache-control", "no-store");
    try {
      const { profile, changed } = await store.update(request.body);
      request.log.info({ device: request.device, changed }, "profile patched");
      return profile;
    } catch (error) {
      if (error instanceof ProfilePatchError) {
        throw new HttpError("invalid_profile", `Not a valid change: ${error.message}`);
      }
      throw error;
    }
  });
}

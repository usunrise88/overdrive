import { z } from "zod";
import { type Logger, silentLogger } from "../log.ts";
import type { TokenManager } from "./auth.ts";
import { describeBody, describeFailure, StravaError } from "./errors.ts";
import { type RateInfo, readRateInfo } from "./rate-limit.ts";

/**
 * A client of Strava's upload endpoint, and of nothing else (ADR 0020). Strava is write-only here
 * (spec §5.5, CLAUDE.md): its API terms forbid using its data in AI models, so this service posts
 * a finished ride and reads only what the upload protocol hands back about *that upload*: its id,
 * its status, its error and the id of the activity it became. There is no other method, on purpose:
 * no athlete, no activities, no streams, no segments. A test records every request the module
 * makes and fails on any other path.
 *
 * Plain `fetch`; the access token goes into one header and nowhere else.
 */

/** What the upload endpoint says about one upload (`POST /uploads` and `GET /uploads/{id}` answer alike). */
export interface UploadReply {
  /** Strava's id of the upload. */
  uploadId: string;
  /** `Your activity is ready.`, `… is still being processed.`, … Words for people; see {@link interpretReply}. */
  status: string | null;
  /** Why processing failed, in words (may have carried markup; it is plain text here). Null when it did not. */
  error: string | null;
  /** The id of the activity the upload became, once it has. */
  activityId: string | null;
  rate: RateInfo;
}

export interface UploadInput {
  /** The FIT file exactly as stored. */
  fit: Uint8Array;
  filename: string;
  /** Our id of the ride; Strava stores it with the upload and uses it, like the file's name, to tell a repeat. */
  externalId: string;
  name: string;
  description: string | null;
}

export interface StravaClient {
  /** `POST /api/v3/uploads`: a virtual ride, from the FIT file. Does not wait for processing. */
  upload(input: UploadInput): Promise<UploadReply>;
  /** `GET /api/v3/uploads/{id}`: the status of an upload we made. The only GET this module sends. */
  uploadStatus(uploadId: string): Promise<UploadReply>;
}

export interface StravaClientOptions {
  /** `https://www.strava.com` or the fake of the tests. */
  baseUrl: string;
  tokens: TokenManager;
  fetch?: typeof fetch;
  /** Per request (default 20 s; the upload gets three times that). */
  timeoutMs?: number;
  log?: Logger;
}

const DEFAULT_TIMEOUT_MS = 20_000;

const idLike = z.union([z.number(), z.string()]);

const replySchema = z.looseObject({
  id: idLike.nullish(),
  id_str: z.string().nullish(),
  error: z.string().nullish(),
  status: z.string().nullish(),
  activity_id: idLike.nullish(),
});

export function createStravaClient(options: StravaClientOptions): StravaClient {
  const baseUrl = options.baseUrl.replace(/\/+$/, "");
  const { tokens } = options;
  const doFetch = options.fetch ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  const log = options.log ?? silentLogger;

  /** Sends the request with an access token; a 401 gets one refresh of the token and one more try. */
  async function send(
    path: string,
    init: { method: "GET" | "POST"; body?: FormData; timeoutMs: number },
  ): Promise<{ text: string; status: number; headers: Headers }> {
    let token = await tokens.accessToken();
    for (let attempt = 1; ; attempt++) {
      let response: Response;
      try {
        response = await doFetch(`${baseUrl}${path}`, {
          method: init.method,
          headers: { authorization: `Bearer ${token}`, accept: "application/json" },
          ...(init.body === undefined ? {} : { body: init.body }),
          // A redirect would carry the token somewhere we did not choose.
          redirect: "error",
          signal: AbortSignal.timeout(init.timeoutMs),
        });
      } catch (cause) {
        throw new StravaError("unreachable", `Strava did not answer (${describeFailure(cause)})`);
      }
      const text = await response.text().catch(() => "");
      if (response.status === 401 && attempt === 1) {
        // The access token may have been revoked, or the clock of the cache is wrong: renew it once.
        log.debug({}, "Strava answered 401; renewing the access token and trying once more");
        token = await tokens.refresh();
        continue;
      }
      return { text, status: response.status, headers: response.headers };
    }
  }

  function fail(answer: { text: string; status: number; headers: Headers }, what: string): never {
    const { status } = answer;
    const rate = readRateInfo(answer.headers);
    const detail = describeBody(answer.text);
    const shown = detail === "" ? "" : `: ${detail}`;
    if (status === 429) {
      throw new StravaError("rate-limited", `Strava is limiting requests (HTTP 429)`, {
        status,
        detail,
        retryAfter: answer.headers.get("retry-after"),
        rate,
      });
    }
    if (status >= 500 || status === 408) {
      throw new StravaError("server", `Strava failed to answer ${what} (HTTP ${status})${shown}`, {
        status,
        detail,
        rate,
      });
    }
    if (status === 401) {
      throw new StravaError(
        "unauthorized",
        `Strava refused the access token (HTTP 401)${shown}. The token may lack the activity:write scope or the application may have been deauthorised: run strava-auth again, put the new STRAVA_REFRESH_TOKEN in .env and restart the server`,
        { status, detail, rate },
      );
    }
    if (status === 403) {
      throw new StravaError(
        "forbidden",
        `Strava refused ${what} (HTTP 403)${shown}. The account or the application may not upload: check that the Strava subscription is active, that the application is allowed to upload and that the token has the activity:write scope`,
        { status, detail, rate },
      );
    }
    throw new StravaError("rejected", `Strava refused ${what} (HTTP ${status})${shown}`, {
      status,
      detail,
      rate,
    });
  }

  function parseReply(
    answer: { text: string; status: number; headers: Headers },
    fallbackId: string | null,
  ): UploadReply {
    let body: unknown;
    try {
      body = JSON.parse(answer.text);
    } catch {
      body = null;
    }
    const parsed = replySchema.safeParse(body);
    const data = parsed.success ? parsed.data : null;
    const uploadId =
      data?.id_str ?? (data?.id === undefined || data.id === null ? null : String(data.id));
    const id = uploadId ?? fallbackId;
    if (data === null || id === null) {
      throw new StravaError("bad-response", "Strava answered the upload with no upload id", {
        status: answer.status,
        rate: readRateInfo(answer.headers),
      });
    }
    const error = data.error === undefined || data.error === null ? "" : describeBody(data.error);
    return {
      uploadId: id,
      status: data.status ?? null,
      error: error === "" ? null : error,
      activityId:
        data.activity_id === undefined || data.activity_id === null
          ? null
          : String(data.activity_id),
      rate: readRateInfo(answer.headers),
    };
  }

  return {
    async upload(input) {
      const form = new FormData();
      form.append(
        "file",
        new Blob([Uint8Array.from(input.fit)], { type: "application/octet-stream" }),
        input.filename,
      );
      form.append("data_type", "fit");
      form.append("name", input.name);
      if (input.description !== null) form.append("description", input.description);
      // A ride on a smart trainer, indoors: the activity is a virtual ride, flagged as on a trainer, not a commute.
      form.append("sport_type", "VirtualRide");
      form.append("trainer", "1");
      form.append("commute", "0");
      form.append("external_id", input.externalId);
      const answer = await send("/api/v3/uploads", {
        method: "POST",
        body: form,
        timeoutMs: timeoutMs * 3,
      });
      if (answer.status < 200 || answer.status >= 300) fail(answer, "the upload");
      return parseReply(answer, null);
    },

    async uploadStatus(uploadId) {
      const answer = await send(`/api/v3/uploads/${encodeURIComponent(uploadId)}`, {
        method: "GET",
        timeoutMs,
      });
      if (answer.status < 200 || answer.status >= 300) fail(answer, "the upload status");
      return parseReply(answer, uploadId);
    },
  };
}

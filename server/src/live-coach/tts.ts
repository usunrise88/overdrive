import { LIVE_CUE_AUDIO_MAX_BYTES, type LiveAudioFormat } from "@overdrive/contracts";

/**
 * A client of the rider's own OpenAI-compatible speech service (spec §12.1, ADR 0038): `POST {TTS_BASE_URL}/audio/speech` with a bearer
 * key and `{ model, voice, input, response_format, speed }`, answered with the audio. Plain `fetch`, no SDK.
 *
 * What it never does: log a URL, a header, the text or the key, put any of them in an error, follow a redirect (the key would go with it),
 * or retry: a cue is worth its answer within seconds or not at all, so a failure is a kind and a status code and the cue is dropped.
 * The only thing sent is the cue's text, at most 80 characters.
 *
 * Written from the public description of the API and the owner's own server (checked once, by hand: 200 `audio/mpeg`, 48 kHz mono, about
 * 27 KB for 34 characters, about 0.5 s to the first byte) and tested against a fake server that follows it; the tests never call the real one.
 */

export type TtsFailureKind =
  /** No answer in time (or the caller gave up). */
  | "timeout"
  /** Connection refused, DNS, TLS. */
  | "unreachable"
  /** 401 or 403: the key is not accepted. */
  | "unauthorized"
  /** Another 4xx: the request is refused. */
  | "rejected"
  /** 408 or 429. */
  | "rate-limited"
  /** 5xx. */
  | "server"
  /** A success with no audio, a type that is not audio, or a body that cannot be read. */
  | "bad-response"
  /** More audio than a cue can carry. */
  | "too-large";

export type TtsResult =
  | { ok: true; audio: Uint8Array; format: LiveAudioFormat }
  | { ok: false; kind: TtsFailureKind; status: number | null };

export interface SynthesizeOptions {
  /** Cancels the request: the cue's deadline passed. */
  signal?: AbortSignal | undefined;
  /** Shorter than the client's own timeout, for a caller with a deadline. */
  timeoutMs?: number | undefined;
}

export interface TtsClient {
  /** The format the requests ask for and the audio is in. */
  readonly format: LiveAudioFormat;
  /** One speech; never throws. */
  synthesize(text: string, options?: SynthesizeOptions): Promise<TtsResult>;
}

export interface TtsClientOptions {
  /** `https://tts.example.com/v1`: the address the path `/audio/speech` is added to. */
  baseUrl: string;
  /** Sent as `Authorization: Bearer …` when set. */
  apiKey?: string | undefined;
  model: string;
  voice: string;
  speed: number;
  format: LiveAudioFormat;
  /** Per request, in milliseconds. */
  timeoutMs: number;
  fetch?: typeof fetch | undefined;
}

/** What each format is called in a `Content-Type`, for the check that an answer is audio. */
const AUDIO_TYPE = /^(audio\/|application\/octet-stream|video\/ogg|application\/ogg)/i;

export function createTtsClient(options: TtsClientOptions): TtsClient {
  const endpoint = `${options.baseUrl.replace(/\/+$/, "")}/audio/speech`;
  const doFetch = options.fetch ?? fetch;

  return {
    format: options.format,
    async synthesize(text, call = {}) {
      const timeout = AbortSignal.timeout(Math.min(options.timeoutMs, call.timeoutMs ?? Infinity));
      const signal = call.signal === undefined ? timeout : AbortSignal.any([timeout, call.signal]);
      let response: Response;
      try {
        response = await doFetch(endpoint, {
          method: "POST",
          headers: {
            "content-type": "application/json",
            accept: "audio/*",
            ...(options.apiKey === undefined ? {} : { authorization: `Bearer ${options.apiKey}` }),
          },
          body: JSON.stringify({
            model: options.model,
            voice: options.voice,
            input: text,
            response_format: options.format,
            speed: options.speed,
          }),
          // A redirect would carry the key and the text somewhere the owner did not choose.
          redirect: "error",
          signal,
        });
      } catch (cause) {
        const name = cause instanceof Error ? cause.name : "";
        return {
          ok: false,
          kind: name === "TimeoutError" || name === "AbortError" ? "timeout" : "unreachable",
          status: null,
        };
      }
      const status = response.status;
      const fail = (kind: TtsFailureKind): TtsResult => {
        return { ok: false, kind, status };
      };
      if (status === 401 || status === 403) return fail("unauthorized");
      if (status === 408 || status === 429) return fail("rate-limited");
      if (status >= 500) return fail("server");
      if (status < 200 || status >= 300) return fail("rejected");
      if (!AUDIO_TYPE.test(response.headers.get("content-type") ?? "")) return fail("bad-response");
      const declared = Number(response.headers.get("content-length"));
      if (Number.isFinite(declared) && declared > LIVE_CUE_AUDIO_MAX_BYTES)
        return fail("too-large");
      let bytes: Uint8Array;
      try {
        bytes = new Uint8Array(await response.arrayBuffer());
      } catch {
        return { ok: false, kind: signal.aborted ? "timeout" : "bad-response", status };
      }
      if (bytes.length === 0) return fail("bad-response");
      if (bytes.length > LIVE_CUE_AUDIO_MAX_BYTES) return fail("too-large");
      return { ok: true, audio: bytes, format: options.format };
    },
  };
}

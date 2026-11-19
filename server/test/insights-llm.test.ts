import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { buildMorningFacts, buildRideFacts } from "../src/insights/facts.ts";
import { createLlmClient, stripReasoning, textOfCompletion } from "../src/insights/llm.ts";
import { PROMPTS, promptVersion } from "../src/insights/prompts/index.ts";
import { templateText } from "../src/insights/template.ts";
import { createValidator } from "../src/insights/validator.ts";
import { createNoteWriter } from "../src/insights/writer.ts";
import { FAKE_LLM_KEY, FAKE_LLM_MODEL, type FakeLlm, startFakeLlm } from "./helpers/fake-llm.ts";
import { morningInput, rideInput } from "./helpers/insights.ts";
import { collectingLogger, recordedSleep } from "./helpers/support.ts";

/**
 * The language-model client and the writer against a fake OpenAI-compatible server (ADR 0025): what is sent, what is retried, what
 * is refused, and the fallback to the template. Wait on conditions, never on the clock: timeouts are the client's own, short, and the
 * fake never answers a request that hangs until it is released.
 */

let llm: FakeLlm;

beforeAll(async () => {
  // The well-behaved fake writes a note that quotes only the facts: the template's own sentences, marked as the model's.
  llm = await startFakeLlm((facts) =>
    facts === null ? "No facts." : `From the model. ${templateText(facts as never)}`,
  );
});
afterAll(async () => {
  await llm.close();
});
beforeEach(() => {
  llm.reset();
});

const messages = [
  { role: "system" as const, content: "Be brief." },
  { role: "user" as const, content: "Say hello." },
];

function client(extra: Partial<Parameters<typeof createLlmClient>[0]> = {}) {
  const sleeping = recordedSleep();
  const logging = collectingLogger();
  return {
    sleeping,
    logging,
    client: createLlmClient({
      baseUrl: llm.url,
      apiKey: FAKE_LLM_KEY,
      model: FAKE_LLM_MODEL,
      timeoutMs: 5000,
      sleep: sleeping.sleep,
      log: logging.log,
      ...extra,
    }),
  };
}

describe("the client", () => {
  it("posts the model, the messages and the key to /chat/completions, and returns the text", async () => {
    llm.queue({ text: "Hello." });
    const { client: c } = client({ temperature: 0.2, maxTokens: 300 });
    expect(await c.complete(messages)).toEqual({ ok: true, text: "Hello." });
    expect(llm.calls).toHaveLength(1);
    const [call] = llm.calls;
    expect(call?.path).toBe("/v1/chat/completions");
    expect(call?.authorization).toBe(`Bearer ${FAKE_LLM_KEY}`);
    expect(call?.contentType).toBe("application/json");
    expect(call?.body).toEqual({
      model: FAKE_LLM_MODEL,
      messages,
      temperature: 0.2,
      max_tokens: 300,
      stream: false,
    });
  });

  it("sends no Authorization header when no key is set", async () => {
    llm.queue({ text: "Hello." });
    const { client: c } = client({ apiKey: undefined });
    expect((await c.complete(messages)).ok).toBe(true);
    expect(llm.calls[0]?.authorization).toBeUndefined();
  });

  it("takes the reasoning off a reasoning model's answer", async () => {
    llm.queue({ text: "<think>The rider is tired, so say rest.</think>\n\nRest today." });
    const { client: c } = client();
    expect(await c.complete(messages)).toEqual({ ok: true, text: "Rest today." });
    expect(stripReasoning("a</think>b")).toBe("b");
    expect(stripReasoning("<think>x</think> y <think>z</think>")).toBe("y");
  });

  it("reads an answer whose content is a list of text parts, and refuses one with no text", () => {
    const parts = {
      choices: [
        {
          message: {
            content: [
              { type: "text", text: "One " },
              { type: "text", text: "two" },
            ],
          },
        },
      ],
    };
    expect(textOfCompletion(parts)).toBe("One two");
    expect(textOfCompletion({ choices: [] })).toBeNull();
    expect(textOfCompletion({ choices: [{ message: { content: "  " } }] })).toBeNull();
    expect(textOfCompletion({ choices: [{ message: { content: null } }] })).toBeNull();
    expect(textOfCompletion("x")).toBeNull();
    expect(textOfCompletion(null)).toBeNull();
  });

  it("repeats after a 5xx with a growing wait, and succeeds", async () => {
    llm.queue({ status: 503 }, { status: 500 }, { text: "Finally." });
    const { client: c, sleeping } = client({ backoffMs: 1000 });
    expect(await c.complete(messages)).toEqual({ ok: true, text: "Finally." });
    expect(llm.calls).toHaveLength(3);
    expect(sleeping.waits).toEqual([1000, 2000]);
  });

  it("waits what Retry-After asks (at most the cap) after a 429", async () => {
    llm.queue(
      { status: 429, headers: { "retry-after": "7" } },
      { status: 429, headers: { "retry-after": "900" } },
      { text: "Ok." },
    );
    const { client: c, sleeping } = client({ maxBackoffMs: 20_000 });
    expect((await c.complete(messages)).ok).toBe(true);
    expect(sleeping.waits).toEqual([7000, 20_000]);
  });

  it("gives up after its attempts and says what kind of failure it was, with the status", async () => {
    llm.queue({ status: 500 }, { status: 502 }, { status: 504 }, { text: "never reached" });
    const { client: c } = client({ maxAttempts: 3 });
    expect(await c.complete(messages)).toEqual({ ok: false, kind: "server", status: 504 });
    expect(llm.calls).toHaveLength(3);
  });

  it.each([
    [401, "unauthorized"],
    [403, "unauthorized"],
    [404, "rejected"],
    [400, "rejected"],
    [422, "rejected"],
  ])("does not repeat a %i: it will not help", async (status, kind) => {
    llm.queue({ status });
    const { client: c, sleeping } = client();
    expect(await c.complete(messages)).toEqual({ ok: false, kind, status });
    expect(llm.calls).toHaveLength(1);
    expect(sleeping.waits).toEqual([]);
  });

  it("times out on an endpoint that never answers, and tries again", async () => {
    llm.queue({ hang: true }, { hang: true });
    const { client: c } = client({ timeoutMs: 60, maxAttempts: 2 });
    expect(await c.complete(messages)).toEqual({ ok: false, kind: "timeout", status: null });
    expect(llm.calls).toHaveLength(2);
  });

  it("reports a connection that is dropped, and one that nobody listens on, as unreachable", async () => {
    llm.queue({ drop: true });
    const { client: c } = client({ maxAttempts: 1 });
    expect(await c.complete(messages)).toEqual({ ok: false, kind: "unreachable", status: null });
    const gone = createLlmClient({
      baseUrl: "http://127.0.0.1:1/v1",
      model: "m",
      timeoutMs: 1000,
      maxAttempts: 1,
    });
    expect(await gone.complete(messages)).toEqual({ ok: false, kind: "unreachable", status: null });
  });

  it("treats garbage as a bad response: not JSON, no choices, no text", async () => {
    llm.queue(
      { status: 200, body: "<html>proxy error</html>" },
      { status: 200, body: { choices: [] } },
      { status: 200, body: { choices: [{ message: { content: "" } }] } },
    );
    const { client: c } = client({ maxAttempts: 1 });
    for (let i = 0; i < 3; i++) {
      expect(await c.complete(messages)).toEqual({ ok: false, kind: "bad-response", status: 200 });
    }
  });

  it("does not follow a redirect: the key and the health data would go with it", async () => {
    llm.queue({ status: 302, headers: { location: "http://127.0.0.1:1/elsewhere" } });
    const { client: c } = client({ maxAttempts: 1 });
    expect((await c.complete(messages)).ok).toBe(false);
    expect(llm.calls).toHaveLength(1);
  });

  it("puts the address, the key, the prompt and the answer in no log line", async () => {
    llm.queue(
      { status: 500 },
      { status: 401, body: { error: { message: `bad key ${FAKE_LLM_KEY} for Say hello.` } } },
    );
    const { client: c, logging } = client({ maxAttempts: 2 });
    await c.complete([{ role: "user", content: "Resting heart rate 58 bpm, Say hello." }]);
    const logged = logging.text();
    expect(logged).not.toContain(FAKE_LLM_KEY);
    expect(logged).not.toContain("127.0.0.1");
    expect(logged).not.toContain("Say hello");
    expect(logged).not.toContain("58");
    expect(logged).toContain("unauthorized");
  });
});

// ---- The writer -----------------------------------------------------------------------------------

const facts = buildMorningFacts(morningInput("caution"));

function writer(
  extra: {
    llm?: ReturnType<typeof client>["client"] | null;
    language?: string;
    denyWords?: string[];
  } = {},
) {
  const logging = collectingLogger();
  const made = client({ log: logging.log });
  return {
    logging,
    sleeping: made.sleeping,
    writer: createNoteWriter({
      llm: extra.llm === undefined ? made.client : extra.llm,
      language: extra.language ?? "en",
      validator: createValidator({ denyWords: extra.denyWords ?? [] }),
      log: logging.log,
    }),
  };
}

describe("the writer", () => {
  it("uses the model's text when it passes, and says which prompt wrote it", async () => {
    const { writer: w } = writer();
    const note = await w.write(facts);
    expect(note.source).toBe("llm");
    expect(note.requests).toBe(1);
    expect(note.promptVersion).toBe(promptVersion("morning"));
    expect(note.promptVersion).toBe("morning.v1+rules.v1");
    expect(note.text.startsWith("From the model. Caution this morning.")).toBe(true);
  });

  it("gives the model the rules, the language and the facts with their quotable numbers", async () => {
    const { writer: w } = writer({ language: "French" });
    await w.write(facts);
    const [call] = llm.calls;
    const system = call?.body.messages[0];
    const user = call?.body.messages[1];
    expect(system?.role).toBe("system");
    expect(system?.content).toContain("Write the note in this language: French.");
    expect(system?.content).toContain(
      'Quote a number only if it is in the facts\' "quotable" list',
    );
    expect(system?.content).toContain("No diagnosis, no medical condition");
    expect(system?.content).toContain(PROMPTS.morning.limit);
    expect(user?.role).toBe("user");
    expect(user?.content).toContain(PROMPTS.morning.instructions);
    const sent = JSON.parse((user?.content ?? "").split("Facts (JSON):\n")[1] ?? "{}");
    expect(sent).toEqual(JSON.parse(JSON.stringify(facts)));
    expect(sent.quotable.length).toBeGreaterThan(5);
  });

  it("asks once more with the violation named when the text quotes a number that was not supplied, and uses the second text", async () => {
    llm.queue(
      { text: "Resting heart rate is 71 bpm, so keep it short." },
      { text: "Resting heart rate is 58 bpm, so keep the hard parts short." },
    );
    const { writer: w } = writer();
    const note = await w.write(facts);
    expect(note).toMatchObject({
      source: "llm",
      requests: 2,
      text: "Resting heart rate is 58 bpm, so keep the hard parts short.",
    });
    const retry = llm.calls[1]?.body.messages ?? [];
    expect(retry).toHaveLength(4);
    expect(retry[2]).toEqual({
      role: "assistant",
      content: "Resting heart rate is 71 bpm, so keep it short.",
    });
    expect(retry[3]?.role).toBe("user");
    expect(retry[3]?.content).toContain('"71"');
    expect(retry[3]?.content).toContain("quotable");
  });

  it("falls back to the template when the second text is wrong too, and says so", async () => {
    llm.queue(
      { text: "Resting heart rate is 71 bpm." },
      { text: "Resting heart rate is 72 bpm." },
      { text: "never asked a third time" },
    );
    const { writer: w } = writer();
    const note = await w.write(facts);
    expect(llm.calls).toHaveLength(2);
    expect(note).toMatchObject({ source: "template", requests: 2, promptVersion: "template.v1" });
    expect(note.text).toBe(templateText(facts));
  });

  it("rejects medical wording the same way: a second try, then the template", async () => {
    llm.queue(
      { text: "This looks like an infection, so rest." },
      { text: "It may be an illness. Rest." },
    );
    const { writer: w } = writer();
    const note = await w.write(facts);
    expect(note.source).toBe("template");
    expect(llm.calls[1]?.body.messages[3]?.content).toContain('"infection"');
    expect(note.text).not.toMatch(/infection|illness/i);
  });

  it("accepts a second text that drops the medical wording", async () => {
    llm.queue(
      { text: "Possibly a virus. Rest." },
      { text: "Signals are off, so keep the hard parts short." },
    );
    const { writer: w } = writer();
    expect(await w.write(facts)).toMatchObject({ source: "llm", requests: 2 });
  });

  it("takes the owner's extra words into account", async () => {
    llm.queue({ text: "Sleep like a wizard, keep it short." }, { text: "Sleep like a wizard." });
    const { writer: w } = writer({ denyWords: ["wizard"] });
    expect((await w.write(facts)).source).toBe("template");
  });

  it("takes markdown off rather than rejecting it", async () => {
    llm.queue({
      text: "## Today\n**Resting heart rate** is 58 bpm, so keep the hard parts short.",
    });
    const { writer: w } = writer();
    expect((await w.write(facts)).text).toBe(
      "Today\nResting heart rate is 58 bpm, so keep the hard parts short.",
    );
  });

  it.each([
    ["a 500 on every try", [{ status: 500 }, { status: 500 }, { status: 500 }]],
    ["a refused key", [{ status: 401 }]],
    [
      "garbage",
      [
        { status: 200, body: "not json" },
        { status: 200, body: "not json" },
        { status: 200, body: "not json" },
      ],
    ],
    ["a dropped connection", [{ drop: true }, { drop: true }, { drop: true }]],
  ] as const)("writes the template when the endpoint fails with %s", async (_name, replies) => {
    llm.queue(...replies);
    const { writer: w } = writer();
    const note = await w.write(facts);
    expect(note).toMatchObject({
      source: "template",
      text: templateText(facts),
      promptVersion: "template.v1",
    });
  });

  it("writes the template when the endpoint times out", async () => {
    llm.queue({ hang: true }, { hang: true });
    const logging = collectingLogger();
    const made = createLlmClient({
      baseUrl: llm.url,
      model: "m",
      timeoutMs: 60,
      maxAttempts: 2,
      sleep: recordedSleep().sleep,
      log: logging.log,
    });
    const w = createNoteWriter({
      llm: made,
      language: "en",
      validator: createValidator(),
      log: logging.log,
    });
    expect((await w.write(facts)).source).toBe("template");
    expect(llm.calls).toHaveLength(2);
  });

  it("writes the template at once when no model is configured, and sends nothing anywhere", async () => {
    const { writer: w } = writer({ llm: null });
    const note = await w.write(facts);
    expect(note).toMatchObject({ source: "template", requests: 0, text: templateText(facts) });
    expect(llm.calls).toEqual([]);
    expect(w.usesModel).toBe(false);
  });

  it("writes each kind with its own prompt", async () => {
    const { writer: w } = writer();
    await w.write(buildRideFacts(rideInput()));
    const system = llm.calls[0]?.body.messages[0]?.content ?? "";
    const user = llm.calls[0]?.body.messages[1]?.content ?? "";
    expect(system).toContain(PROMPTS.ride.limit);
    expect(user).toContain(PROMPTS.ride.instructions);
    expect(promptVersion("ride")).toBe("ride.v2+rules.v1");
  });

  it("logs the kind, how it was written and how many requests, but no number, sentence, key or address", async () => {
    llm.queue(
      { text: "Resting heart rate is 71 bpm, probably an infection." },
      { text: "Resting heart rate is 58 bpm, keep the hard parts short." },
    );
    const { writer: w, logging } = writer();
    await w.write(facts);
    llm.queue({ status: 500 }, { status: 500 }, { status: 500 });
    await w.write(facts);
    const logged = logging.text();
    for (const secret of [
      "71",
      "58",
      "infection",
      "keep the hard parts",
      FAKE_LLM_KEY,
      "127.0.0.1",
      "Resting heart rate",
    ]) {
      expect(logged, secret).not.toContain(secret);
    }
    expect(logged).toContain('"kind":"morning"');
    expect(logged).toContain('"source":"llm"');
    expect(logged).toContain("model-server");
  });
});

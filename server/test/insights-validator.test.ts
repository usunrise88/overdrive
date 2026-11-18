import { describe, expect, it } from "vitest";
import { compileDenyList, DEFAULT_DENY_WORDS, deniedWords } from "../src/insights/deny.ts";
import {
  buildMorningFacts,
  buildRideFacts,
  buildWeekFacts,
  type Facts,
} from "../src/insights/facts.ts";
import {
  countViolations,
  createValidator,
  describeViolations,
  suppliedViolations,
} from "../src/insights/validator.ts";
import { morningInput, rideInput, weekInput } from "./helpers/insights.ts";

/**
 * The validator enforces what the prompt only asks (ADR 0025): a number that was not supplied, a date or time that was not supplied,
 * a word of the deny list. Tables of texts and what each is judged.
 */

const morning = buildMorningFacts(morningInput("caution"));
const ride = buildRideFacts(rideInput({ withRecords: true, watts: 245 }));
const week = buildWeekFacts(weekInput());
const validator = createValidator();

const tokens = (text: string, facts: Facts = morning) =>
  suppliedViolations(text, facts).map((v) => `${v.kind}:${v.token}`);

describe("numbers", () => {
  // The morning's facts: resting heart rate 58 bpm (usual 50 to 54), planned load 51, 60 min, IF 0.75, fitness 62, form -9.
  it.each([
    ["Resting heart rate is 58 bpm.", []],
    ["Your usual range is 50 to 54.", []],
    ["Planned load is 51 and the workout takes 60 minutes.", []],
    ["Intensity factor 0.75.", []],
    ["Form is -9, fitness 62.", []],
    ["Form is minus 9.", []],
    ["Form is −9.", []],
    ["It is 1 h 00 min long.", []],
    ["That is 1 hour long.", []],
    ["About 58, rounded from the reading.", []],
  ])("accepts %j", (text, expected) => {
    expect(tokens(text)).toEqual(expected);
  });

  it.each([
    ["Resting heart rate is 71 bpm.", ["number:71"]],
    ["Resting heart rate is 58.5 bpm.", ["number:58.5"]],
    ["Planned load is 5.1.", ["number:5.1"]],
    ["Intensity factor is 75%.", ["number:75"]],
    ["Load 51 plus 62 makes 113.", ["number:113"]],
    ["Twenty minutes at threshold.", ["number:Twenty"]],
    ["Take eleven days off.", ["number:eleven"]],
    ["That is about forty-five minutes.", ["number:forty-five"]],
    ["Do 4x6 minutes.", ["number:4", "number:6"]],
    ["Over 1,200 watts.", ["number:1,200"]],
    ["A pulse of 8,9 beats.", ["number:8,9"]],
  ])("rejects %j", (text, expected) => {
    expect(tokens(text)).toEqual(expected);
  });

  it("accepts a number written at the precision the text gives it, and only the rounding of it", () => {
    // 58 is a whole number in the facts; 58.0 is the same, 58.4 is not.
    expect(tokens("It was 58.0 bpm.")).toEqual([]);
    expect(tokens("It was 58.4 bpm.")).toEqual(["number:58.4"]);
    // 5.7 hours of sleep is supplied for the rest morning: "6" is its rounding, "5.7" itself, "5.65" no.
    const rest = buildMorningFacts(morningInput("rest"));
    expect(tokens("Sleep was 5.7 hours.", rest)).toEqual([]);
    expect(tokens("Sleep was about 6 hours.", rest)).toEqual([]);
    expect(tokens("Sleep was 5.65 hours.", rest)).toEqual(["number:5.65"]);
  });

  it("takes the numbers of the facts' own sentences and names as supplied, in digits and in words", () => {
    // The workout is called "Sweet spot 3x10", and the recovery reason says "second morning".
    expect(tokens("Today's Sweet spot 3x10 is on.")).toEqual([]);
    expect(tokens("Three by ten, in words, is the same workout.")).toEqual([]);
    expect(tokens("Try 4x10 instead.")).toEqual(["number:4"]);
    expect(tokens("Resting heart rate is up for the second morning.")).toEqual([]);
  });

  it.each([
    "1. Keep it short.\n2. Keep it easy.",
    "Zone 2 is fine, so is Z3.",
    "The last 7 days were light, and a week is short.",
    "Sleep over 24 hours is not the point.",
    "A VO2 session is not planned.",
    "A 7-day view.",
  ])("lets the trivial ones through: %j", (text) => {
    expect(tokens(text)).toEqual([]);
  });

  it("does not let a trivial number hide a real one", () => {
    expect(tokens("Zone 2 for 90 minutes.")).toEqual(["number:90"]);
    expect(tokens("1. Resting heart rate 99.")).toEqual(["number:99"]);
  });

  it("never lets the digits of a word count (VO2max, Z2, W')", () => {
    expect(tokens("VO2max and Z2 work with W' balance.")).toEqual([]);
  });
});

describe("dates and times", () => {
  it("accepts the dates the facts mention in any of the usual forms", () => {
    // The morning is 2026-11-18; the last ride was 2026-11-16.
    expect(tokens("On 18 November the plan is on.")).toEqual([]);
    expect(tokens("Today, Nov 18, is a sweet spot day.")).toEqual([]);
    expect(tokens("2026-11-18 is a Wednesday.")).toEqual([]);
    expect(tokens("Your last ride was on 16 Nov.")).toEqual([]);
    expect(tokens("Your last ride was on the 16th of November.")).toEqual(["number:16"]);
  });

  it("rejects a date the facts do not mention", () => {
    expect(tokens("On 21 November you could test.")).toEqual(["date:21 November"]);
    expect(tokens("See Dec 3.")).toEqual(["date:Dec 3"]);
    expect(tokens("2026-12-01 looks good.")).toEqual(["date:2026-12-01"]);
  });

  it("accepts any day of the week a weekly review is about, and no other", () => {
    expect(tokens("Mon 9 Nov to 15 Nov.", week)).toEqual([]);
    expect(tokens("On 12 November the best came.", week)).toEqual([]);
    expect(tokens("On 16 November nothing happened.", week)).toEqual(["date:16 November"]);
  });

  it("accepts a time of day the facts mention and a duration written as h:mm", () => {
    expect(tokens("You rode at 18:05.", ride)).toEqual([]);
    expect(tokens("You rode at 19:30.", ride)).toEqual(["time:19:30"]);
    // 60 minutes is in the facts: 1:00 is the same length.
    expect(tokens("It took 1:00.", ride)).toEqual([]);
    expect(tokens("It took 1:07.", ride)).toEqual(["time:1:07"]);
  });
});

describe("words that are not allowed", () => {
  const facts = morning;
  it.each([
    "This looks like an infection.",
    "You may be ill.",
    "That could be the flu.",
    "Sleep apnea can do that.",
    "A diagnosis is not possible, but it may be a disease.",
    "See a doctor if it goes on.",
    "Medical advice is needed.",
    "Signs of overtraining.",
    "Perhaps anemia.",
    "A fever, maybe.",
    "Pregnancy changes this.",
  ])("rejects %j", (text) => {
    const check = validator.check(text, facts);
    expect(check.ok).toBe(false);
    expect(check.violations.some((v) => v.kind === "deny")).toBe(true);
  });

  it.each([
    "Keep the hard parts short and stop early if it feels heavy.",
    "Resting heart rate is above your usual range for the second morning.",
    "The plan stays as it is; the fatigue is high, so ride easy.",
    "I will not say what it means.",
  ])("accepts %j", (text) => {
    expect(validator.check(text, facts).violations).toEqual([]);
  });

  it("matches whole words, and a stem as a prefix: 'will' is not 'ill', 'diagnosed' is 'diagnos*'", () => {
    const list = compileDenyList();
    expect(deniedWords("I will ride. A skill.", list)).toEqual([]);
    expect(deniedWords("Diagnosed, DIAGNOSIS and diagnose.", list)).toEqual([
      "diagnosed",
      "diagnosis",
      "diagnose",
    ]);
    expect(deniedWords("heart  attack", list)).toEqual(["heart attack"]);
  });

  it("takes the owner's words in addition to the built-in list", () => {
    const own = createValidator({ denyWords: ["Magic", "no pain"] });
    expect(own.check("Magic legs today.", morning).violations).toEqual([
      { kind: "deny", token: "magic" },
    ]);
    expect(own.check("There is no   pain, no gain.", morning).violations).toEqual([
      { kind: "deny", token: "no pain" },
    ]);
    // The built-in ones are still there, and the plain validator does not know the owner's.
    expect(own.check("An infection.", morning).ok).toBe(false);
    expect(validator.check("Magic legs today.", morning).ok).toBe(true);
  });

  it("has a list that is not empty and every entry is lower-case", () => {
    expect(DEFAULT_DENY_WORDS.length).toBeGreaterThan(30);
    for (const word of DEFAULT_DENY_WORDS) expect(word).toBe(word.toLowerCase());
  });
});

describe("the form of the text", () => {
  it("rejects an empty text, a text over the limit and a link", () => {
    expect(validator.check("   ", morning).violations.map((v) => v.kind)).toEqual(["form"]);
    expect(validator.check("a".repeat(1501), morning).violations.map((v) => v.kind)).toEqual([
      "form",
    ]);
    expect(
      validator.check("See https://example.com for more.", morning).violations.map((v) => v.kind),
    ).toEqual(["form"]);
    expect(validator.check("a".repeat(1500), morning).ok).toBe(true);
  });
});

describe("describing a rejection to the model", () => {
  it("names the offending tokens and nothing else of the text", () => {
    const text = "Your resting heart rate of 71 bpm points to an infection on 3 Dec, at 06:41.";
    const check = validator.check(text, morning);
    const said = describeViolations(check.violations);
    expect(said).toContain('"71"');
    expect(said).toContain('"infection"');
    expect(said).toContain('"3 Dec"');
    expect(said).toContain('"6:41"'.replace("6:41", "06:41"));
    expect(said).not.toContain("resting heart rate");
    expect(said).not.toContain("points to");
  });

  it("counts violations of each kind for the log, never their tokens", () => {
    const check = validator.check("An infection, 71 and 72 on 3 Dec.", morning);
    expect(countViolations(check.violations)).toEqual({
      number: 2,
      date: 1,
      time: 0,
      deny: 1,
      form: 0,
    });
  });
});

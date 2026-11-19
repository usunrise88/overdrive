import { compileDenyList, deniedWords } from "./deny.ts";
import type { Facts } from "./facts.ts";
import type { Quantity } from "./quotes.ts";

/**
 * What the check needs of a document of facts: its kind, and the numbers a text may quote. The facts of every kind of written note are
 * this, and so are the facts of a live cue (spec §12.1, ADR 0038), which is why the check takes this and not the union of the notes'.
 */
export interface CheckableFacts {
  kind: string;
  quotable: readonly Quantity[];
}

/**
 * The check that enforces, and does not merely ask for, the rule of spec §6.5: "quote only numbers that were supplied, make no
 * diagnosis, name no medical condition" (ADR 0025). After a model has written a note, this extracts every number from the text
 * and rejects the text when one of them is not in the facts it was given, or when a word of the deny list appears.
 *
 * What counts as supplied: the `quotable` numbers of the facts (compared at the precision the text gives them: "73" matches 73.4,
 * "73.4" does not match 73), every number and number word in the facts' own sentences and names (a workout called "3x10" may be
 * written "3x10"), the dates the facts mention (as ISO dates, or "18 November", "Nov 18") and the times of day they mention.
 * What is allowed without being supplied: list numbering ("1.", "2)"), "7 days" and "24 hours" and "a week", power zones 1 to 7,
 * and "VO2". Anything else, including a number the text derived by itself (76% from an intensity factor of 0.76; hours from
 * minutes) is a violation: the model writes, it does not calculate.
 *
 * Digits are checked in any language; number words only in English ("two", "twenty-five"; "one" is a pronoun as often as a
 * number and is never checked). A text in another language is held to the digits.
 */

export type ViolationKind = "number" | "date" | "time" | "deny" | "form";

export interface Violation {
  kind: ViolationKind;
  /** The offending number as written, date, time or denied word; for `form` a short description. Never other text of the note. */
  token: string;
}

export interface TextCheck {
  ok: boolean;
  violations: Violation[];
}

/** The longest note, the contract's limit. */
export const MAX_TEXT_LENGTH = 1500;

const NUMBER_WORDS: Record<string, number> = {
  zero: 0,
  two: 2,
  three: 3,
  four: 4,
  five: 5,
  six: 6,
  seven: 7,
  eight: 8,
  nine: 9,
  ten: 10,
  eleven: 11,
  twelve: 12,
  thirteen: 13,
  fourteen: 14,
  fifteen: 15,
  sixteen: 16,
  seventeen: 17,
  eighteen: 18,
  nineteen: 19,
  twenty: 20,
  thirty: 30,
  forty: 40,
  fifty: 50,
  sixty: 60,
  seventy: 70,
  eighty: 80,
  ninety: 90,
  hundred: 100,
  thousand: 1000,
  dozen: 12,
  twice: 2,
  thrice: 3,
};

const TENS = ["twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"];
const UNITS = ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine"];

const COMPOUND = new RegExp(`\\b(${TENS.join("|")})[- ](${UNITS.join("|")})\\b`, "gi");
const WORD = new RegExp(`\\b(${Object.keys(NUMBER_WORDS).join("|")})\\b`, "gi");

const MONTHS = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"];
const MONTH =
  "(Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|June?|July?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\.?";
const DAY_MONTH = new RegExp(`\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+${MONTH}(?![a-z])`, "gi");
const MONTH_DAY = new RegExp(`\\b${MONTH}\\s+(\\d{1,2})(?:st|nd|rd|th)?(?![\\d:])`, "gi");
const ISO_DATE = /\b(\d{4})-(\d{2})-(\d{2})\b/g;
const TIME = /(?<![\d:])(\d{1,2}):(\d{2})(?![\d:])/g;
const LIST_ORDINAL = /(^|\n)[ \t]*(\d{1,2})[.)][ \t]/g;
const NUMBER = /(?<![\p{L}\d.])(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+,\d{1,2}(?![\d,])|\d+(?:\.\d+)?)/gu;
/** Said without being supplied: a week, a day, the power zones, VO2. */
const TRIVIAL: RegExp[] = [
  /\b(?:7|seven)[- ]days?\b/gi,
  /\b24[- ]hours?\b/gi,
  /\b1[- ]weeks?\b/gi,
  /\bzone\s*[1-7]\b/gi,
  /\bZ[1-7]\b/g,
  /\bVO2(?:\s?max)?\b/gi,
  /\bW[′']/g,
];
const URL = /(?:https?:\/\/|www\.)/i;

/** Which keys of the facts hold ids and instants, not sentences: their digits are not numbers a note may quote. */
const NOT_SENTENCES = new Set([
  "id",
  "rideId",
  "quotable",
  "schema",
  "date",
  "from",
  "to",
  "previousBestDate",
]);

interface Allowed {
  /** Every quotable value, in absolute terms. */
  values: number[];
  /** `MM-DD` of every date the facts mention. */
  monthDays: Set<string>;
  /** Years of the dates the facts mention. */
  years: Set<number>;
  /** `H:MM` times of day the facts mention. */
  times: Set<string>;
  /** Minutes of every quantity in minutes: "1:05" is a duration form of 65. */
  minutes: Set<number>;
}

const cache = new WeakMap<object, Allowed>();

const pad2 = (n: number): string => String(n).padStart(2, "0");

function parseNumber(token: string): { value: number; decimals: number } {
  let text = token;
  if (/^\d{1,3}(?:,\d{3})+/.test(text)) text = text.replace(/,/g, "");
  else text = text.replace(",", ".");
  const dot = text.indexOf(".");
  return { value: Number(text), decimals: dot < 0 ? 0 : text.length - dot - 1 };
}

/** Takes the dates out of `text`, calling `onDate` with each (month 1-12, day, year or null); returns what is left. */
function withoutDates(
  text: string,
  onDate: (month: number, day: number, year: number | null, whole: string) => void,
): string {
  let rest = text.replace(ISO_DATE, (whole, y: string, m: string, d: string) => {
    onDate(Number(m), Number(d), Number(y), whole);
    return " ";
  });
  rest = rest.replace(DAY_MONTH, (whole, d: string, m: string) => {
    onDate(MONTHS.indexOf(m.slice(0, 3).toLowerCase()) + 1, Number(d), null, whole);
    return " ";
  });
  rest = rest.replace(MONTH_DAY, (whole, m: string, d: string) => {
    onDate(MONTHS.indexOf(m.slice(0, 3).toLowerCase()) + 1, Number(d), null, whole);
    return " ";
  });
  return rest;
}

/** Normalises "3 x 10" and "3×10" so both numbers are seen. */
const spaceProducts = (text: string): string => text.replace(/(\d)\s*[x×]\s*(?=\d)/gi, "$1 x ");

/** Every number and number word of `text`, as (value, decimals), after the dates and times are taken out. */
function numbersIn(text: string): { token: string; value: number; decimals: number }[] {
  const found: { token: string; value: number; decimals: number }[] = [];
  let rest = spaceProducts(text);
  rest = rest.replace(LIST_ORDINAL, "$1 ");
  for (const pattern of TRIVIAL) rest = rest.replace(pattern, " ");
  rest = rest.replace(COMPOUND, (whole, tens: string, unit: string) => {
    found.push({
      token: whole,
      value: (NUMBER_WORDS[tens.toLowerCase()] ?? 0) + (UNITS.indexOf(unit.toLowerCase()) + 1),
      decimals: 0,
    });
    return " ";
  });
  rest = rest.replace(WORD, (whole) => {
    found.push({ token: whole, value: NUMBER_WORDS[whole.toLowerCase()] ?? 0, decimals: 0 });
    return " ";
  });
  for (const match of rest.matchAll(NUMBER)) {
    const token = match[0];
    found.push({ token, ...parseNumber(token) });
  }
  return found;
}

function walk(node: unknown, key: string | null, visit: (text: string) => void): void {
  if (typeof node === "string") {
    if (key === null || !NOT_SENTENCES.has(key)) visit(node);
    return;
  }
  if (Array.isArray(node)) {
    for (const item of node) walk(item, key, visit);
    return;
  }
  if (typeof node === "object" && node !== null) {
    for (const [childKey, child] of Object.entries(node)) {
      if (childKey === "quotable") continue;
      walk(child, childKey, visit);
    }
  }
}

function allowedFrom(facts: CheckableFacts): Allowed {
  const hit = cache.get(facts);
  if (hit !== undefined) return hit;
  const allowed: Allowed = {
    values: [],
    monthDays: new Set(),
    years: new Set(),
    times: new Set(),
    minutes: new Set(),
  };
  for (const quantity of facts.quotable) {
    allowed.values.push(Math.abs(quantity.value));
    if (quantity.unit === "min") allowed.minutes.add(Math.round(quantity.value));
  }
  const addDate = (month: number, day: number, year: number | null): void => {
    allowed.monthDays.add(`${pad2(month)}-${pad2(day)}`);
    if (year !== null) allowed.years.add(year);
  };
  // The dates the facts name, wherever they are (the keys that hold only dates are read as dates, not as sentences).
  const dateStrings: string[] = [];
  const collect = (node: unknown): void => {
    if (typeof node === "string") {
      if (/^\d{4}-\d{2}-\d{2}/.test(node)) dateStrings.push(node.slice(0, 10));
    } else if (Array.isArray(node)) node.forEach(collect);
    else if (typeof node === "object" && node !== null) {
      for (const [key, child] of Object.entries(node)) if (key !== "quotable") collect(child);
    }
  };
  collect(facts);
  for (const date of dateStrings) withoutDates(date, (m, d, y) => addDate(m, d, y));
  // A week's note may name any day of that week.
  if (facts.kind === "week") {
    const from = Date.parse(`${(facts as Extract<Facts, { kind: "week" }>).week.from}T00:00:00Z`);
    for (let i = 0; i < 7; i++) {
      const day = new Date(from + i * 86_400_000);
      addDate(day.getUTCMonth() + 1, day.getUTCDate(), day.getUTCFullYear());
    }
  }
  // The facts' own sentences and names: whatever numbers and times they carry are supplied.
  walk(facts, null, (text) => {
    let rest = withoutDates(text, (m, d, y) => addDate(m, d, y));
    rest = rest.replace(TIME, (_whole, h: string, m: string) => {
      allowed.times.add(`${Number(h)}:${m}`);
      return " ";
    });
    for (const found of numbersIn(rest)) allowed.values.push(Math.abs(found.value));
  });
  for (const year of allowed.years) allowed.values.push(year);
  cache.set(facts, allowed);
  return allowed;
}

function isSupplied(value: number, decimals: number, allowed: Allowed): boolean {
  const target = Math.abs(value);
  const factor = 10 ** decimals;
  return allowed.values.some((have) => Math.round(have * factor) / factor === target);
}

/** The numbers, dates and times of `text` that the facts do not supply. Exposed for the tests. */
export function suppliedViolations(text: string, facts: CheckableFacts): Violation[] {
  const allowed = allowedFrom(facts);
  const violations: Violation[] = [];
  let rest = withoutDates(text, (month, day, _year, whole) => {
    if (!allowed.monthDays.has(`${pad2(month)}-${pad2(day)}`)) {
      violations.push({ kind: "date", token: whole.trim() });
    }
  });
  rest = rest.replace(TIME, (whole, h: string, m: string) => {
    const hours = Number(h);
    const minutes = Number(m);
    const clock = `${hours}:${m}`;
    if (!allowed.times.has(clock) && !allowed.minutes.has(hours * 60 + minutes)) {
      violations.push({ kind: "time", token: whole });
    }
    return " ";
  });
  for (const found of numbersIn(rest)) {
    if (!isSupplied(found.value, found.decimals, allowed)) {
      violations.push({ kind: "number", token: found.token });
    }
  }
  return violations;
}

export interface ValidatorOptions {
  /** Words added to the built-in deny list (`INSIGHT_DENY_WORDS`). */
  denyWords?: readonly string[];
}

export interface TextValidator {
  check(text: string, facts: CheckableFacts): TextCheck;
}

/** Makes the check with the deny list compiled once. */
export function createValidator(options: ValidatorOptions = {}): TextValidator {
  const list = compileDenyList(options.denyWords ?? []);
  return {
    check(text, facts) {
      const violations: Violation[] = [];
      const trimmed = text.trim();
      if (trimmed === "") violations.push({ kind: "form", token: "the text is empty" });
      if (trimmed.length > MAX_TEXT_LENGTH) {
        violations.push({
          kind: "form",
          token: `the text is longer than ${MAX_TEXT_LENGTH} characters`,
        });
      }
      if (URL.test(trimmed)) violations.push({ kind: "form", token: "the text contains a link" });
      for (const word of deniedWords(trimmed, list)) violations.push({ kind: "deny", token: word });
      violations.push(...suppliedViolations(trimmed, facts));
      return { ok: violations.length === 0, violations };
    },
  };
}

/** The violations in words, for the model on the retry. Names the offending tokens, never any other text. */
export function describeViolations(violations: readonly Violation[]): string {
  const numbers = violations.filter((v) => v.kind === "number").map((v) => `"${v.token}"`);
  const dates = violations.filter((v) => v.kind === "date").map((v) => `"${v.token}"`);
  const times = violations.filter((v) => v.kind === "time").map((v) => `"${v.token}"`);
  const deny = violations.filter((v) => v.kind === "deny").map((v) => `"${v.token}"`);
  const form = violations.filter((v) => v.kind === "form").map((v) => v.token);
  const parts: string[] = [];
  if (numbers.length > 0) {
    parts.push(
      `it quoted numbers that are not in the facts' quotable list: ${[...new Set(numbers)].join(", ")}`,
    );
  }
  if (dates.length > 0)
    parts.push(`it named dates that are not in the facts: ${[...new Set(dates)].join(", ")}`);
  if (times.length > 0)
    parts.push(`it named times that are not in the facts: ${[...new Set(times)].join(", ")}`);
  if (deny.length > 0) {
    parts.push(
      `it used words that are not allowed (no diagnosis, no medical condition): ${[...new Set(deny)].join(", ")}`,
    );
  }
  for (const note of form) parts.push(note);
  return parts.join("; ");
}

/** How many violations of each kind, for the log: counts, never the tokens (they may be the rider's numbers). */
export function countViolations(violations: readonly Violation[]): Record<ViolationKind, number> {
  const counts: Record<ViolationKind, number> = { number: 0, date: 0, time: 0, deny: 0, form: 0 };
  for (const violation of violations) counts[violation.kind]++;
  return counts;
}

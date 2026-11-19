import type { HrStep, LeafStep, Workout } from "@overdrive/contracts";
import { type DocStep, type DocValue, Unsupported } from "./workout-doc.ts";

/**
 * intervals.icu's workout text: the description of a planned workout (ADR 0016). Written from the
 * forum's "Workout builder" and its syntax quick guide, with no account to try it on.
 *
 *     Warm-up
 *     - 10m ramp 45-70%
 *
 *     Main set 3x
 *     - Sweet spot 10m 88% 90rpm
 *     - Recover 5m 50%
 *
 *     - 5m freeride
 *
 * A step is a line that starts with `-`: optional cue text, a duration (`1h2m30s`, `5m`, `90s`,
 * `1m30`), a target (`75%`, `80-90%`, `220w`, `Z2`, `70% HR`), and optionally `ramp`, `freeride` or
 * a cadence (`90rpm`, `90-100rpm`). A line that ends in `Nx` opens a repeat of the steps that follow,
 * up to the next blank line. Other lines are headings and notes. Everything is parsed into the shape
 * of `workout_doc`, so the converter treats text and parsed events alike.
 *
 * **A heart-rate step** (native `hr`, spec §11.2) has no native counterpart in this syntax: intervals.icu's heart-rate targets
 * are `70% HR`, `85% LTHR` and `Z2 HR`, never a number of beats, a step cannot carry a power band next to one (the documentation
 * does not say it can), and the controller's limits would be lost. So the step is written as a step with its **power limits as a
 * power range**, `- Zone by heart rate 40m 80-220w`, which intervals.icu shows and counts as a planned effort of 150 W on average,
 * and the target heart rate and the band travel in **a note after the steps** (a line intervals.icu treats as a note), one per
 * heart-rate step, numbered by the step lines in the order they are written, repeat bodies once:
 *
 *     Overdrive heart-rate step 1: 135 bpm, band 5, 80-220 W, label Zone 2 by heart rate
 *     Overdrive heart-rate step 2: 88% of threshold heart rate, 100-260 W
 *
 * The band is there only when the step gave one, and the label is there so that it keeps its digits (a cue loses the words that
 * start with one, because the parser might take them for a target); both make the way back exact.
 *
 * {@link parseHrNotes} reads them back and the converter applies them to the steps they number, when the step is still the one
 * written (its power range agrees); see ADR 0033.
 */

const NUMBER = String.raw`\d+(?:\.\d+)?`;
const DURATION_UNITS = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/i;
const MINUTES_SECONDS = /^(\d+)m(\d{1,2})$/i;
const TICKS = /^(?:(\d+)')?(?:(\d+)")?$/;
const DISTANCE = new RegExp(`^${NUMBER}(?:km|mtr|mi|yd)$`, "i");
const PERCENT = new RegExp(`^(${NUMBER})(?:-(${NUMBER}))?%$`);
const PERCENT_RANGE = new RegExp(`^(${NUMBER})%-(${NUMBER})%$`);
const WATTS = new RegExp(`^(${NUMBER})(?:-(${NUMBER}))?w$`, "i");
const ZONE = /^(c?)z(\d)(?:-c?z?(\d))?$/i;
const CADENCE = new RegExp(`^(${NUMBER})(?:-(${NUMBER}))?rpm$`, "i");
const BARE_RANGE = new RegExp(`^(${NUMBER})(?:-(${NUMBER}))?$`);
const ABSOLUTE_PACE = /^\d+:\d{2}(?:\/\w+)?$/;
const REPEAT_HEADER = /^(?:(.*?)\s*)?(\d+)x$/i;

/** Seconds, `"distance"` for a length, or null when the token is neither. */
function parseDuration(token: string): number | "distance" | null {
  if (DISTANCE.test(token)) return "distance";
  const units = DURATION_UNITS.exec(token);
  if (units !== null && token !== "") {
    return Number(units[1] ?? 0) * 3600 + Number(units[2] ?? 0) * 60 + Number(units[3] ?? 0);
  }
  const mixed = MINUTES_SECONDS.exec(token);
  if (mixed !== null) return Number(mixed[1]) * 60 + Number(mixed[2]);
  const ticks = TICKS.exec(token);
  if (ticks !== null && token !== "" && (ticks[1] !== undefined || ticks[2] !== undefined)) {
    return Number(ticks[1] ?? 0) * 60 + Number(ticks[2] ?? 0);
  }
  return null;
}

function range(first: string | undefined, second: string | undefined, units: string): DocValue {
  const a = Number(first);
  if (second === undefined) return { value: a, units };
  return { start: a, end: Number(second), units };
}

function parseStepLine(line: string, lineNumber: number): DocStep {
  if (line.includes("^")) {
    throw new Unsupported(`line ${lineNumber}: prompts at a time inside a step are not supported`);
  }
  const tokens = line
    .replace(/^-\s*/, "")
    .split(/\s+/)
    .map((token) => token.replace(/[,;]+$/, ""))
    .filter((token) => token !== "");
  const step: DocStep = {};
  const cue: string[] = [];
  let recognised = false;

  for (let i = 0; i < tokens.length; i++) {
    const token = tokens[i] ?? "";
    const lower = token.toLowerCase();
    const next = tokens[i + 1]?.toLowerCase();
    const suffix =
      next === "hr" || next === "lthr" || next === "pace" || next === "mmp" ? next : null;

    const duration = parseDuration(token);
    if (duration !== null) {
      if (duration === "distance") step.distance = 1;
      else if (step.duration === undefined) step.duration = duration;
      recognised = true;
      continue;
    }
    if (lower === "ramp") {
      step.ramp = true;
      recognised = true;
      continue;
    }
    if (lower === "freeride") {
      step.freeride = true;
      recognised = true;
      continue;
    }

    const percent = PERCENT_RANGE.exec(token) ?? PERCENT.exec(token);
    if (percent !== null) {
      const value = range(percent[1], percent[2], "%ftp");
      if (suffix === null) step.power = value;
      else if (suffix === "mmp") step.power = { ...value, units: "%mmp" };
      else if (suffix === "pace") step.pace = { ...value, units: "%pace" };
      else step.hr = { ...value, units: suffix === "hr" ? "%hr" : "%lthr" };
      if (suffix !== null) i++;
      recognised = true;
      continue;
    }
    const watts = WATTS.exec(token);
    if (watts !== null) {
      step.power = range(watts[1], watts[2], "w");
      recognised = true;
      continue;
    }
    const zone = ZONE.exec(token);
    if (zone !== null) {
      const custom = zone[1] !== "";
      if (suffix === "hr") step.hr = range(zone[2], zone[3], "hr_zone");
      else if (suffix === "pace") step.pace = range(zone[2], zone[3], "pace_zone");
      else step.power = range(zone[2], zone[3], custom ? "custom_zone" : "power_zone");
      if (suffix === "hr" || suffix === "pace") i++;
      recognised = true;
      continue;
    }
    const cadence = CADENCE.exec(token);
    if (cadence !== null) {
      step.cadence = range(cadence[1], cadence[2], "rpm");
      recognised = true;
      continue;
    }
    const bare = BARE_RANGE.exec(token);
    if (bare !== null && next === "rpm") {
      step.cadence = range(bare[1], bare[2], "rpm");
      i++;
      recognised = true;
      continue;
    }
    if (ABSOLUTE_PACE.test(token)) {
      step.pace = { value: 0, units: "pace" };
      if (suffix === "pace") i++;
      recognised = true;
      continue;
    }
    // Words before the first target are the step's cue; words after it are extra text.
    if (!recognised) cue.push(token);
  }

  if (cue.length > 0) step.text = cue.join(" ");
  if (step.duration === undefined && step.distance === undefined && !step.freeride) {
    throw new Unsupported(`line ${lineNumber}: no duration (\`${line.trim().slice(0, 60)}\`)`);
  }
  return step;
}

/**
 * Parses a description into steps. Lines that are neither steps nor a repeat's `Nx` header are
 * headings and notes and are ignored. A description with no step lines gives an empty list.
 */
export function parseWorkoutText(text: string): DocStep[] {
  const steps: DocStep[] = [];
  let group: DocStep | null = null;
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim();
    if (line === "") {
      group = null;
      continue;
    }
    if (line.startsWith("-")) {
      const step = parseStepLine(line, index + 1);
      if (group?.steps !== undefined) group.steps.push(step);
      else steps.push(step);
      continue;
    }
    const header = REPEAT_HEADER.exec(line);
    if (header !== null) {
      group = { reps: Number(header[2]), steps: [] };
      const cue = header[1]?.trim();
      if (cue !== undefined && cue !== "") group.text = cue;
      steps.push(group);
    } else {
      group = null;
    }
  }
  return steps;
}

// ---- Native workout to text ------------------------------------------------------------------

/** A cue is for reading: words that the parser would take for a target are dropped. */
export function cueOf(label: string | undefined): string {
  if (label === undefined) return "";
  return label
    .replace(/\^/g, " ")
    .split(/\s+/)
    .map((word) => word.replace(/^-+/, ""))
    .filter(
      (word) =>
        word !== "" &&
        !/^\d/.test(word) &&
        !/^c?z\d/i.test(word) &&
        !/^(ramp|freeride|hr|lthr|pace|mmp|rpm)$/i.test(word),
    )
    .join(" ");
}

/** `90` is `1m30s`, `600` is `10m`, `3600` is `1h`. */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h > 0 ? `${h}h` : ""}${m > 0 ? `${m}m` : ""}${s > 0 ? `${s}s` : ""}`;
}

/** 0.875 is `87.5`, 0.45 is `45`. */
function percent(fraction: number): string {
  return String(Math.round(fraction * 1000) / 10);
}

function renderLeaf(step: LeafStep): string {
  const cue = cueOf(step.label);
  const parts = [cue, formatDuration(step.duration)];
  if (step.type === "steady") parts.push(`${percent(step.target)}%`);
  else if (step.type === "ramp") parts.push("ramp", `${percent(step.from)}-${percent(step.to)}%`);
  else if (step.type === "hr") parts.push(`${step.power_min}-${step.power_max}w`);
  else parts.push("freeride");
  if (step.cadence !== undefined) parts.push(`${Math.round(step.cadence)}rpm`);
  return `- ${parts.filter((part) => part !== "").join(" ")}`;
}

/**
 * The workout as intervals.icu text, for creating or updating a planned workout. The description
 * comes first as a plain line (headings and notes are ignored by the parser); labels become cues.
 */
export function renderWorkoutText(workout: Workout): string {
  const lines: string[] = [];
  const blank = () => {
    if (lines.length > 0 && lines[lines.length - 1] !== "") lines.push("");
  };
  if (workout.description !== undefined) {
    const note = workout.description
      .replace(/\s+/g, " ")
      .trim()
      .replace(/^-+\s*/, "");
    // A note that ends in "3x" would open a repeat.
    if (note !== "") lines.push(/\d+x$/i.test(note) ? `${note}.` : note, "");
  }
  for (const step of workout.steps) {
    if (step.type === "repeat") {
      blank();
      const cue = cueOf(step.label);
      lines.push(`${cue === "" ? "" : `${cue} `}${step.count}x`);
      for (const inner of step.steps) {
        lines.push(renderLeaf(inner));
      }
      lines.push("");
    } else {
      lines.push(renderLeaf(step));
    }
  }
  while (lines[lines.length - 1] === "") lines.pop();
  // The step lines, in order: the heart-rate steps among them are numbered by their place.
  const leaves = workout.steps.flatMap((step) => (step.type === "repeat" ? step.steps : [step]));
  const notes = leaves.flatMap((step, index) =>
    step.type === "hr" ? [hrNote(index + 1, step)] : [],
  );
  if (notes.length > 0) lines.push("", ...notes);
  return lines.join("\n");
}

// ---- The note that carries a heart-rate step's target ----------------------------------------------------------

/** What a heart-rate note says about the step it numbers. */
export interface HrNote {
  /** 1-based, among the step lines in the order they are written (a repeat's body once). */
  step: number;
  targetBpm: number | null;
  /** A fraction of the threshold heart rate (0.88), when that is the target. */
  targetLthr: number | null;
  /** Null when the step did not give one (the default applies). */
  band: number | null;
  powerMin: number;
  powerMax: number;
  /** The step's label, whole; null when it had none. */
  label: string | null;
}

const HR_NOTE =
  /^Overdrive heart-rate step (\d+): (?:(\d+) bpm|(\d+(?:\.\d+)?)% of threshold heart rate)(?:, band (\d+))?, (\d+)-(\d+) W(?:, label (.+))?$/;

function hrNote(index: number, step: HrStep): string {
  const target =
    step.target_bpm !== undefined
      ? `${step.target_bpm} bpm`
      : `${percent(step.target_lthr ?? 0)}% of threshold heart rate`;
  const band = step.band === undefined ? "" : `, band ${step.band}`;
  const label = step.label?.replace(/\s+/g, " ").trim();
  const tail = label === undefined || label === "" ? "" : `, label ${label}`;
  return `Overdrive heart-rate step ${index}: ${target}${band}, ${step.power_min}-${step.power_max} W${tail}`;
}

/**
 * The heart-rate notes of a description, by the step they number. Lines that are not notes of this shape are ignored. A step
 * numbered twice keeps its first note.
 */
export function parseHrNotes(text: string | null | undefined): Map<number, HrNote> {
  const notes = new Map<number, HrNote>();
  for (const raw of (text ?? "").split(/\r?\n/)) {
    const match = HR_NOTE.exec(raw.trim());
    if (match === null) continue;
    const step = Number(match[1]);
    if (notes.has(step)) continue;
    notes.set(step, {
      step,
      targetBpm: match[2] === undefined ? null : Number(match[2]),
      targetLthr:
        match[3] === undefined ? null : Math.round((Number(match[3]) / 100) * 1000) / 1000,
      band: match[4] === undefined ? null : Number(match[4]),
      powerMin: Number(match[5]),
      powerMax: Number(match[6]),
      label: match[7] === undefined ? null : match[7].trim(),
    });
  }
  return notes;
}

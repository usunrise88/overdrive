import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import {
  addDaysToIsoDate,
  isIsoDate,
  type Reading,
  rangeDays,
  WELLNESS_MAX_RANGE_DAYS,
  type WellnessDay,
  type WellnessMetric,
} from "@overdrive/contracts";
import { z } from "zod";
import { localDate } from "../../time.ts";
import { METRIC_KEYS, METRIC_SPECS } from "../../wellness/metrics.ts";
import { RULE_MEANINGS } from "../../wellness/recovery.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const DEFAULT_DAYS = 14;
/** How long a refresh may take before the tool answers with what it has. */
const REFRESH_WAIT_MS = 10_000;

const round = (value: number | null, digits: number): number | null => {
  if (value === null) return null;
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
};

/** Minutes from local midnight (negative before it) as a clock time, `23:05`. */
export function clockOf(minutes: number | null): string | null {
  if (minutes === null) return null;
  const wrapped = ((Math.round(minutes) % 1440) + 1440) % 1440;
  return `${String(Math.floor(wrapped / 60)).padStart(2, "0")}:${String(wrapped % 60).padStart(2, "0")}`;
}

const SIDE_WORDS: Record<string, string> = { above: "above", below: "below" };

/** The metrics of a day that are outside their usual range, as facts: which one, on which side, for how many days. */
function outside(day: WellnessDay): { metric: string; side: string; days: number }[] {
  const found: { metric: string; side: string; days: number }[] = [];
  for (const key of METRIC_KEYS) {
    const reading: Reading = day[key];
    if (reading.deviation === "above" || reading.deviation === "below") {
      found.push({
        metric: key,
        side: SIDE_WORDS[reading.deviation] ?? reading.deviation,
        days: reading.streak,
      });
    }
  }
  return found;
}

export function registerWellnessTools(server: McpServer, ctx: ToolContext): void {
  const { services, now } = ctx;
  const timezone = services.timezone;
  const { wellness } = services;

  async function refreshFirst(): Promise<void> {
    const pending = wellness.refresh();
    if (pending === null) return;
    let timer: ReturnType<typeof setTimeout> | undefined;
    await Promise.race([
      pending,
      new Promise<void>((resolve) => {
        timer = setTimeout(resolve, REFRESH_WAIT_MS);
      }),
    ]);
    clearTimeout(timer);
  }

  defineTool(
    server,
    ctx,
    {
      name: "get_wellness",
      title: "Get daily wellness figures and baselines",
      kind: "read",
      description:
        "The rider's daily wellness figures from the watch (Health Connect) and intervals.icu, for the days between two dates, with the " +
        "personal baseline of each metric. Per day: restingHr (bpm), sleepHours (the night that ended that morning, asleep time), bedtime " +
        "and wake (local clock times), respiratoryRate (breaths per minute), spo2 (percent), sleepingHr (mean bpm during sleep, only when " +
        "the watch recorded enough samples), hrvRmssd (ms, only where a source writes it), steps, weightKg (body weight that day) and weightKg7d (the mean of the daily weights of the 7 days up to that day: the trend; it changes nothing in the rider's settings), and outsideUsualRange: the metrics outside " +
        "their usual range that day, on which side and for how many days in a row. The usual range of a metric is its mean over the 42 days " +
        "before, minus and plus one standard deviation, and exists only after 14 readings (baselinesAsOfLastDay holds the latest). Dates are " +
        "the rider's local days (YYYY-MM-DD); a night belongs to the morning it ends. Without dates: the last 14 days. At most 186 days. " +
        "detail full returns every reading with its source, usual range, deviation and streak per day instead. refresh true reads " +
        "intervals.icu first (it can take seconds). These are facts the server computed: quote them, do not recalculate. They say nothing " +
        "about the cause of a change and are not medical advice.",
      input: {
        from: dateField.optional().describe("First day. Default: 13 days before to."),
        to: dateField.optional().describe("Last day, included. Default: today."),
        detail: z
          .enum(["summary", "full"])
          .optional()
          .describe("summary (default) is compact; full has every reading with its baseline."),
        refresh: z
          .boolean()
          .optional()
          .describe("Read intervals.icu first (when configured). Default false."),
      },
    },
    async ({ from, to, detail, refresh }) => {
      const end = to ?? localDate(now(), timezone);
      if (!isIsoDate(end))
        throw new ToolError(`to ${end} is not a real calendar date, write YYYY-MM-DD`);
      const start = from ?? addDaysToIsoDate(end, -(DEFAULT_DAYS - 1));
      if (!isIsoDate(start)) {
        throw new ToolError(`from ${start} is not a real calendar date, write YYYY-MM-DD`);
      }
      const days = rangeDays(start, end) ?? 0;
      if (days < 1) throw new ToolError(`to ${end} is before from ${start}`);
      if (days > WELLNESS_MAX_RANGE_DAYS) {
        throw new ToolError(
          `the range spans ${days} days; at most ${WELLNESS_MAX_RANGE_DAYS} are served`,
        );
      }
      if (refresh === true) await refreshFirst();
      const answer = await wellness.wellness(start, end);
      if (detail === "full") return answer;

      const last = answer.days[answer.days.length - 1];
      const baselines: Record<string, { low: number; high: number } | null> = {};
      for (const key of METRIC_KEYS as readonly WellnessMetric[]) {
        const reading = last?.[key];
        baselines[key] =
          reading?.baselineLow == null || reading.baselineHigh == null
            ? null
            : { low: reading.baselineLow, high: reading.baselineHigh };
      }
      const hours = (seconds: number | null) => round(seconds === null ? null : seconds / 3600, 2);
      return {
        from: answer.from,
        to: answer.to,
        generatedAt: answer.generatedAt,
        stale: answer.stale,
        nightsLogged: answer.nightsLogged,
        nightsNeeded: answer.nightsNeeded,
        baselineDays: answer.baselineDays,
        units: {
          restingHr: METRIC_SPECS.restingHr.unit,
          sleepHours: "h",
          respiratoryRate: METRIC_SPECS.respiratoryRate.unit,
          spo2: METRIC_SPECS.spo2.unit,
          sleepingHr: METRIC_SPECS.sleepingHr.unit,
          hrvRmssd: METRIC_SPECS.hrvRmssd.unit,
          weightKg: "kg",
          weightKg7d: "kg",
        },
        baselinesAsOfLastDay: {
          restingHr: baselines.restingHr,
          sleepHours:
            baselines.sleepSeconds === null || baselines.sleepSeconds === undefined
              ? null
              : {
                  low: round(baselines.sleepSeconds.low / 3600, 2),
                  high: round(baselines.sleepSeconds.high / 3600, 2),
                },
          bedtime:
            answer.usual.bedtime === null
              ? null
              : {
                  low: clockOf(answer.usual.bedtime.low),
                  high: clockOf(answer.usual.bedtime.high),
                },
          wake:
            answer.usual.wake === null
              ? null
              : { low: clockOf(answer.usual.wake.low), high: clockOf(answer.usual.wake.high) },
          respiratoryRate: baselines.respiratoryRate,
          spo2: baselines.spo2,
          sleepingHr: baselines.sleepingHr,
          hrvRmssd: baselines.hrvRmssd,
        },
        days: answer.days.map((day) => ({
          date: day.date,
          restingHr: day.restingHr.value,
          sleepHours: hours(day.sleepSeconds.value),
          bedtime: clockOf(day.bedtimeMinutes.value),
          wake: clockOf(day.wakeMinutes.value),
          respiratoryRate: day.respiratoryRate.value,
          spo2: day.spo2.value,
          sleepingHr: day.sleepingHr.value,
          hrvRmssd: day.hrvRmssd.value,
          steps: day.steps,
          weightKg: day.weightKg,
          weightKg7d: day.weightKg7d,
          outsideUsualRange: outside(day),
        })),
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "get_recovery",
      title: "Get today's recovery state",
      kind: "read",
      description:
        "The rider's recovery state for this morning (or yesterday's while this morning's watch data has not arrived) and the facts behind " +
        "it: state is ready, caution or rest (from fixed rules over how far each signal is from the rider's own usual range, in the direction " +
        "that matters), learning (fewer than nightsNeeded nights logged: no verdict yet) or noData (no reading from last night). reason is one " +
        "sentence written from the rule that decided, guidance one short sentence of training advice, rule the row of the rule table that " +
        "fired (ruleMeaning says what it is). facts are the three or four signals that decided it, in decisive order: value, unit, the usual " +
        "range (baselineLow, baselineHigh), deviation (inside, above, below) and streak (days in a row outside the range on the same side). " +
        "The signals are resting heart rate (bpm), sleep (seconds), sleep timing (bedtimeMinutes: minutes from local midnight, negative " +
        "before it), respiratory rate, oxygen saturation, heart rate in sleep and, where a source writes it, heart-rate variability (the " +
        "7-day mean in ms). This is a training signal computed by code; it names no cause and is not medical advice. Quote it, do not " +
        "recalculate it. refresh true reads intervals.icu first.",
      input: {
        refresh: z
          .boolean()
          .optional()
          .describe("Read intervals.icu first (when configured). Default false."),
      },
    },
    async ({ refresh }) => {
      if (refresh === true) await refreshFirst();
      const answer = await wellness.recovery();
      return {
        ...answer,
        ruleMeaning: answer.rule === null ? null : RULE_MEANINGS[answer.rule],
      };
    },
  );
}

import { type ProgressTrend, validateProgressResponse } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import {
  computeProgress,
  ftpAtStart,
  ftpOn,
  monthsOf,
  type ProgressInputs,
  type ProgressRide,
  weeksOf,
} from "../src/progress/compute.ts";
import { addMonthsToIsoDate, resolveRange } from "../src/progress/range.ts";
import type { SteadyStretch } from "../src/progress/steady.ts";
import {
  type HeadlineCandidate,
  overallBestPower,
  pickHeadline,
  slopeOf,
  type TrendSpec,
  trendOf,
  verdictText,
} from "../src/progress/trend.ts";

/**
 * The Progress computation on hand-made inputs (spec §6.3 "Progress over time", ADR 0043): the trend rule with each direction and each
 * below-minimum case, the references, the weeks and months, partial month, missing weight, missing watch data, empty history and the
 * headline. Every figure is invented and every expected number is worked out in the comments. Every answer is also checked against
 * the contract's validator, so the two cannot drift.
 */

const TODAY = "2026-11-22"; // a Sunday

function inputs(overrides: Partial<ProgressInputs> = {}): ProgressInputs {
  return {
    generatedAt: new Date("2026-11-22T07:40:00Z"),
    stale: false,
    today: TODAY,
    range: { from: "2026-09-28", to: TODAY },
    rides: [],
    ftpHistory: [{ date: "2026-01-10", ftp: 257 }],
    heartRates: { threshold: 170, maximum: 190 },
    restingHr: new Map(),
    weightKg: new Map(),
    ...overrides,
  };
}

/** The answer, checked against the contract. */
function progress(overrides: Partial<ProgressInputs> = {}) {
  const answer = computeProgress(inputs(overrides));
  const checked = validateProgressResponse(answer);
  expect(checked, JSON.stringify(checked)).toMatchObject({ ok: true });
  return answer;
}

const stretch = (meanPower: number, meanHeartRate: number, seconds = 600): SteadyStretch => ({
  startSec: 100,
  seconds,
  meanPower,
  meanHeartRate,
  heartRateSeconds: seconds,
});

let counter = 0;
function ride(date: string, extra: Partial<ProgressRide> = {}): ProgressRide {
  counter++;
  return {
    rideId: `ride-${String(counter).padStart(4, "0")}`,
    startMs: Date.parse(`${date}T07:00:00Z`) + counter,
    date,
    name: "Free ride",
    best: {},
    drift: { available: false, reason: "short" },
    stats: null,
    ...extra,
  };
}

const withStretches = (date: string, stretches: SteadyStretch[]): ProgressRide =>
  ride(date, { stats: { stretches, efficiency: null } });

/** `n` consecutive days from `first`. */
function days(first: string, n: number): string[] {
  const start = Date.parse(`${first}T00:00:00Z`);
  return Array.from({ length: n }, (_, i) =>
    new Date(start + i * 86_400_000).toISOString().slice(0, 10),
  );
}

const bpmSpec = (over: Partial<TrendSpec> = {}): TrendSpec => ({
  subject: "Heart rate at the same power",
  verb: "is",
  unit: "bpm",
  betterWhen: "lower",
  minPoints: 4,
  steadyBand: 2,
  fitter: true,
  heartRate: true,
  ...over,
});

const points = (ys: number[], xs?: number[]) =>
  ys.map((y, i) => ({ x: xs?.[i] ?? i, y, date: `2026-10-${String(i + 1).padStart(2, "0")}` }));

describe("the trend rule", () => {
  const trend = (ys: number[], spec = bpmSpec(), xs?: number[]): ProgressTrend =>
    trendOf({ points: points(ys, xs), spec, ends: "points", reason: (n) => `${n} of 4 weeks` });

  it("fits a least-squares line and says improving when the heart rate falls by more than the band", () => {
    // 145, 144, 143, 142 against weeks 0 to 3: slope -1 bpm a week, fitted change -3 bpm over 3 weeks; band 2 bpm; lower is better.
    expect(slopeOf(points([145, 144, 143, 142]))).toBe(-1);
    const t = trend([145, 144, 143, 142]);
    expect(t).toMatchObject({
      direction: "improving",
      change: -3,
      from: 145,
      latest: 142,
      difference: -3,
      since: "2026-10-01",
      points: 4,
      minPoints: 4,
      steadyBand: 2,
      reason: null,
      verdictText: "Heart rate at the same power is trending down: fitter",
    });
    expect(t.note).toContain("heat, sleep and caffeine");
  });

  it("says steady inside the band", () => {
    // 140, 140.5, 140, 140.5: slope 0.1, change 0.3 < 2.
    const t = trend([140, 140.5, 140, 140.5]);
    expect(t).toMatchObject({ direction: "steady", change: 0.3, reason: null });
    expect(t.verdictText).toBe("Heart rate at the same power is steady");
  });

  it("says worsening, with no judging word, when the heart rate rises by more than the band", () => {
    // 140, 142, 144, 146: slope 2, change 6.
    const t = trend([140, 142, 144, 146]);
    expect(t).toMatchObject({ direction: "worsening", change: 6 });
    expect(t.verdictText).toBe("Heart rate at the same power is trending up");
    expect(t.verdictText).not.toMatch(/fitter|better|worse/);
  });

  it("is a change equal to the band, not inside it", () => {
    // Slope 2/3 over 3 weeks = exactly 2: not smaller than the band.
    expect(trend([140, 140.667, 141.333, 142], bpmSpec(), undefined).direction).toBe("worsening");
    expect(trend([140, 140.5, 141, 141.5]).direction).toBe("steady");
  });

  it("is unknown below the minimum number of points, and says how many there are", () => {
    for (const ys of [[], [140], [140, 139], [140, 139, 138]]) {
      const t = trend(ys);
      expect(t).toMatchObject({
        direction: "unknown",
        change: null,
        difference: null,
        points: ys.length,
        verdictText: "Not enough data yet",
        reason: `${ys.length} of 4 weeks`,
      });
    }
    // The points that exist are reported: the first and the last, with the day of the first.
    expect(trend([140, 139, 138])).toMatchObject({ from: 140, latest: 138, since: "2026-10-01" });
    expect(trend([])).toMatchObject({ from: null, latest: null, since: null });
  });

  it("measures time, not position: a missing week is a gap and not a squeeze", () => {
    // Weeks 0, 1, 3, 4: 145, 144, 142, 141. Mean x 2, mean y 143, covariance -10, variance 10: slope -1, change -1 x 4 = -4.
    const t = trend([145, 144, 142, 141], bpmSpec(), [0, 1, 3, 4]);
    expect(t).toMatchObject({ direction: "improving", change: -4 });
  });

  it("says better, not fitter, for the measures that are not heart rate against power", () => {
    const rhr = bpmSpec({ subject: "Resting heart rate", fitter: false });
    expect(trend([55, 54, 53, 52], rhr).verdictText).toBe(
      "Resting heart rate is trending down: better",
    );
    const drift = bpmSpec({ subject: "Aerobic drift", unit: "%", steadyBand: 1, fitter: false });
    expect(trend([7, 6, 5, 4], drift).verdictText).toBe("Aerobic drift is trending down: better");
    const perKg = bpmSpec({
      subject: "Watts per kilogram",
      verb: "are",
      betterWhen: "higher",
      fitter: false,
      heartRate: false,
    });
    const up = trend([3.2, 3.3, 3.4, 3.5], { ...perKg, steadyBand: 0.05 });
    expect(up.verdictText).toBe("Watts per kilogram are trending up: better");
    expect(up.note).toBeNull();
    expect(verdictText(perKg, "steady", 0)).toBe("Watts per kilogram are steady");
  });

  it("takes the ends of a ride measure as the mean of the first and the last five rides", () => {
    const spec: TrendSpec = {
      subject: "Efficiency factor",
      verb: "is",
      unit: "W/bpm",
      betterWhen: "higher",
      minPoints: 5,
      steadyBand: 0.02,
      fitter: true,
      heartRate: true,
    };
    const ys = [1.3, 1.32, 1.34, 1.36, 1.38, 1.4, 1.42];
    const t = trendOf({ points: points(ys), spec, ends: "rides", reason: () => "x" });
    // First five: mean 1.34; last five (rides 3 to 7): mean 1.38. The slope is 0.02 a ride: change 0.12.
    expect(t).toMatchObject({
      direction: "improving",
      change: 0.12,
      from: 1.34,
      latest: 1.38,
      difference: 0.04,
    });
    expect(t.verdictText).toBe("Efficiency factor is trending up: fitter");
  });
});

describe("best power over the three durations", () => {
  const line = (direction: "improving" | "worsening" | "steady" | "unknown"): ProgressTrend => ({
    direction,
    betterWhen: "higher",
    unit: "W",
    points: direction === "unknown" ? 1 : 3,
    minPoints: 3,
    change:
      direction === "unknown"
        ? null
        : direction === "worsening"
          ? -10
          : direction === "steady"
            ? 0
            : 10,
    difference: null,
    from: null,
    latest: null,
    since: null,
    steadyBand: 5,
    verdictText: "x",
    reason: direction === "unknown" ? "1 of 3 months have a 5-minute ride." : null,
    note: null,
  });
  const overall = (
    a: ProgressTrend["direction"],
    b: ProgressTrend["direction"],
    c: ProgressTrend["direction"],
  ) => overallBestPower([line(a), line(b), line(c)], { subject: "Best power" });

  it("is improving when at least two lines improve and none worsens", () => {
    expect(overall("improving", "improving", "steady")).toMatchObject({
      direction: "improving",
      lines: 3,
    });
    expect(overall("improving", "improving", "unknown")).toMatchObject({
      direction: "improving",
      lines: 2,
    });
    expect(overall("improving", "improving", "improving").verdictText).toBe(
      "Best power is trending up: better",
    );
  });

  it("is worsening the other way round, without a judging word", () => {
    const worse = overall("worsening", "steady", "worsening");
    expect(worse.direction).toBe("worsening");
    expect(worse.verdictText).toBe("Best power is trending down");
  });

  it("is steady when the lines disagree or only one moves", () => {
    expect(overall("improving", "worsening", "steady").direction).toBe("steady");
    expect(overall("improving", "steady", "steady").direction).toBe("steady");
    expect(overall("improving", "improving", "worsening").direction).toBe("steady");
    expect(overall("steady", "steady", "unknown")).toMatchObject({
      direction: "steady",
      verdictText: "Best power is steady",
    });
  });

  it("is unknown below two lines with a verdict, and says what the lines lack", () => {
    const t = overall("improving", "unknown", "unknown");
    expect(t).toMatchObject({ direction: "unknown", lines: 1, verdictText: "Not enough data yet" });
    expect(t.reason).toContain("1 of 3 months have a 5-minute ride.");
  });
});

describe("the headline", () => {
  const c = (
    measure: HeadlineCandidate["measure"],
    direction: HeadlineCandidate["direction"],
    clarity: number,
  ): HeadlineCandidate => ({
    measure,
    direction,
    verdictText: `${measure} ${direction}`,
    clarity,
  });

  it("is the non-steady verdict furthest outside its band", () => {
    expect(
      pickHeadline([
        c("aerobicEfficiency", "improving", 1.5),
        c("bestPower", "improving", 3),
        c("decoupling", "steady", 0),
      ]),
    ).toMatchObject({ measure: "bestPower", direction: "improving" });
  });

  it("breaks a tie by the page's order: heart rate at the same power first", () => {
    expect(
      pickHeadline([c("restingHeartRate", "worsening", 2), c("aerobicEfficiency", "improving", 2)])
        ?.measure,
    ).toBe("aerobicEfficiency");
  });

  it("is a steady verdict when nothing moves, the first by the page's order", () => {
    expect(
      pickHeadline([
        c("restingHeartRate", "steady", 0),
        c("bestPower", "steady", 0),
        c("weight", "unknown", 0),
      ])?.measure,
    ).toBe("bestPower");
  });

  it("is null when no measure has a verdict", () => {
    expect(pickHeadline([c("bestPower", "unknown", 0), c("weight", "unknown", 0)])).toBeNull();
    expect(pickHeadline([])).toBeNull();
  });

  it("does not let an unknown trend win, however its clarity reads", () => {
    expect(pickHeadline([c("bestPower", "unknown", 9), c("weight", "steady", 0)])?.measure).toBe(
      "weight",
    );
  });
});

describe("weeks, months and ranges", () => {
  it("runs weeks Monday to Sunday and includes every week the range touches", () => {
    expect(weeksOf("2026-10-26", "2026-11-01")).toEqual(["2026-10-26"]);
    expect(weeksOf("2026-11-01", "2026-11-02")).toEqual(["2026-10-26", "2026-11-02"]);
    expect(weeksOf("2026-12-28", "2027-01-04")).toEqual(["2026-12-28", "2027-01-04"]);
  });

  it("lists every month the range touches, across a year", () => {
    expect(monthsOf("2026-11-15", "2027-02-03")).toEqual([
      "2026-11",
      "2026-12",
      "2027-01",
      "2027-02",
    ]);
    expect(monthsOf("2026-11-01", "2026-11-30")).toEqual(["2026-11"]);
  });

  it("adds months and clamps to the end of a shorter one", () => {
    expect(addMonthsToIsoDate("2026-08-31", -6)).toBe("2026-02-28");
    expect(addMonthsToIsoDate("2028-08-31", -6)).toBe("2028-02-29");
    expect(addMonthsToIsoDate("2026-11-22", -60)).toBe("2021-11-22");
    expect(addMonthsToIsoDate("2026-01-15", -1)).toBe("2025-12-15");
  });

  it("resolves the range: the last six months by default, at most five years, no future start", () => {
    expect(resolveRange(undefined, undefined, TODAY)).toEqual({
      ok: true,
      from: "2026-05-22",
      to: TODAY,
    });
    expect(resolveRange("2026-01-01", undefined, TODAY)).toEqual({
      ok: true,
      from: "2026-01-01",
      to: TODAY,
    });
    expect(resolveRange("2021-11-22", TODAY, TODAY)).toMatchObject({ ok: true });
    expect(resolveRange("2021-11-21", TODAY, TODAY)).toMatchObject({
      ok: false,
      parameter: "range",
    });
    expect(resolveRange("2026-11-03", "2026-11-01", TODAY)).toMatchObject({
      ok: false,
      parameter: "range",
    });
    expect(resolveRange("2026-11-23", undefined, TODAY)).toMatchObject({
      ok: false,
      parameter: "from",
    });
    expect(resolveRange("June", undefined, TODAY)).toMatchObject({ ok: false, parameter: "from" });
    expect(resolveRange(undefined, "2026-02-30", TODAY)).toMatchObject({
      ok: false,
      parameter: "to",
    });
  });

  it("finds the FTP in force on a day, and the reference's FTP at the range's start", () => {
    const history = [
      { date: "2026-03-01", ftp: 240 },
      { date: "2026-06-01", ftp: 255 },
      { date: "2026-08-01", ftp: null },
      { date: "2026-09-01", ftp: 260 },
    ];
    expect(ftpOn(history, "2026-02-28")).toBeNull();
    expect(ftpOn(history, "2026-03-01")).toBe(240);
    expect(ftpOn(history, "2026-07-31")).toBe(255);
    expect(ftpOn(history, "2026-08-15")).toBeNull();
    expect(ftpOn(history, "2026-12-01")).toBe(260);
    // A range that starts before the profile had an FTP takes the earliest there is; one that starts when it was cleared, too.
    expect(ftpAtStart(history, "2026-01-01")).toBe(240);
    expect(ftpAtStart(history, "2026-08-15")).toBe(240);
    expect(ftpAtStart([], "2026-01-01")).toBeNull();
  });
});

describe("aerobic efficiency", () => {
  /** Six weeks from 28 Sep: each ride has two stretches at the reference power (180 W) and one at the reference heart rate (135 bpm). */
  const weekly = (): ProgressRide[] =>
    ["2026-09-30", "2026-10-07", "2026-10-14", "2026-10-21", "2026-10-28", "2026-11-04"].flatMap(
      (date, i) => [
        withStretches(date, [
          stretch(181, 146 - i), // within 5% of 180 W (171 to 189 W)
          stretch(175, 148 - i, 300), // within 5%
          stretch(150, 134.5, 420), // too easy for 180 W; 134.5 bpm is within 3 bpm of 135
        ]),
      ],
    );

  it("names the references: 70% of the FTP at the range's start to 5 W, 80% of the threshold heart rate to 5 bpm", () => {
    const answer = progress({ rides: weekly() });
    // 0.70 x 257 W = 179.9 W -> 180 W; 0.80 x 170 bpm = 136 bpm -> 135 bpm.
    expect(answer.aerobicEfficiency).toMatchObject({
      ftpAtStart: 257,
      referencePower: 180,
      referenceHeartRate: 135,
      referenceHeartRateBasis: "threshold",
    });
  });

  it("takes the median of the week's stretches inside the band, and says how many and how long", () => {
    const answer = progress({ rides: weekly() });
    const first = answer.aerobicEfficiency.weeks[0];
    // Week of 28 Sep: stretches at 181 W / 146 bpm and 175 W / 147... (i = 0: 146 and 148) -> median of 146 and 148 = 147.
    expect(first).toMatchObject({
      weekStart: "2026-09-28",
      heartRateAtPower: 147,
      atPower: { stretches: 2, seconds: 900 },
      // Only the 150 W stretch is within 3 bpm of 135 bpm: its power is the figure.
      powerAtHeartRate: 150,
      atHeartRate: { stretches: 1, seconds: 420 },
    });
    // 8 weeks: Mondays 28 Sep to 16 Nov; the last two have no ride: a gap, never a zero.
    expect(answer.aerobicEfficiency.weeks.map((w) => w.weekStart)).toEqual([
      "2026-09-28",
      "2026-10-05",
      "2026-10-12",
      "2026-10-19",
      "2026-10-26",
      "2026-11-02",
      "2026-11-09",
      "2026-11-16",
    ]);
    expect(answer.aerobicEfficiency.weeks[6]).toMatchObject({
      heartRateAtPower: null,
      powerAtHeartRate: null,
      atPower: { stretches: 0, seconds: 0 },
    });
  });

  it("judges the trend: heart rate at the same power falling a beat a week is improving and fitter", () => {
    const answer = progress({ rides: weekly() });
    // Medians 147, 146, 145, 144, 143, 142 over weeks 0 to 5: slope -1, change -5, band 2.
    expect(answer.aerobicEfficiency.trend).toMatchObject({
      direction: "improving",
      change: -5,
      from: 147,
      latest: 142,
      points: 6,
      minPoints: 4,
      since: "2026-09-28",
      verdictText: "Heart rate at the same power is trending down: fitter",
    });
    // Power at 135 bpm is 150 W every week: steady.
    expect(answer.aerobicEfficiency.powerTrend).toMatchObject({
      direction: "steady",
      change: 0,
      from: 150,
      latest: 150,
    });
    expect(answer.headline).toMatchObject({ measure: "aerobicEfficiency", direction: "improving" });
  });

  it("is unknown with three weeks of figures, and says how many weeks have one", () => {
    const answer = progress({ rides: weekly().slice(0, 3) });
    expect(answer.aerobicEfficiency.trend).toMatchObject({
      direction: "unknown",
      points: 3,
      reason: "3 of 4 weeks have a figure. Ride steady for 5 minutes or more to add one.",
      verdictText: "Not enough data yet",
    });
    expect(answer.headline).toBeNull();
  });

  it("uses a stretch only inside the band: 5% of the reference power, 3 bpm of the reference heart rate", () => {
    const answer = progress({
      rides: [
        withStretches("2026-10-01", [
          stretch(189, 150), // 189 W is exactly 5% over 180 W: counts
          stretch(189.1, 160), // just outside
          stretch(171, 138), // 171 W is exactly 5% under: counts; 138 bpm is exactly 3 over 135: counts
          stretch(160, 138.1), // outside both
        ]),
      ],
    });
    const week = answer.aerobicEfficiency.weeks[0];
    expect(week?.atPower.stretches).toBe(2);
    // Median of 150 and 138 bpm = 144.
    expect(week?.heartRateAtPower).toBe(144);
    expect(week?.atHeartRate.stretches).toBe(1);
    expect(week?.powerAtHeartRate).toBe(171);
  });

  it("takes the median of an even count as the mean of the middle two, and of an odd count as the middle", () => {
    const odd = progress({
      rides: [
        withStretches("2026-10-01", [stretch(180, 140), stretch(181, 150), stretch(179, 144)]),
      ],
    });
    expect(odd.aerobicEfficiency.weeks[0]?.heartRateAtPower).toBe(144);
    const even = progress({
      rides: [
        withStretches("2026-10-01", [
          stretch(180, 140),
          stretch(181, 150),
          stretch(179, 144),
          stretch(178, 146),
        ]),
      ],
    });
    expect(even.aerobicEfficiency.weeks[0]?.heartRateAtPower).toBe(145);
  });

  it("never moves the references when the FTP changes during the range", () => {
    const answer = progress({
      ftpHistory: [
        { date: "2026-01-10", ftp: 257 },
        { date: "2026-11-01", ftp: 300 },
      ],
      rides: weekly(),
    });
    expect(answer.aerobicEfficiency.referencePower).toBe(180);
  });

  it("takes the earliest FTP when the range began before the profile had one", () => {
    const answer = progress({ ftpHistory: [{ date: "2026-10-15", ftp: 260 }] });
    // 0.70 x 260 W = 182 W -> 180 W.
    expect(answer.aerobicEfficiency).toMatchObject({ ftpAtStart: 260, referencePower: 180 });
  });

  it("falls back to the maximum heart rate, and says so", () => {
    const answer = progress({ heartRates: { threshold: null, maximum: 188 } });
    // 0.80 x 188 = 150.4 -> 150.
    expect(answer.aerobicEfficiency).toMatchObject({
      referenceHeartRate: 150,
      referenceHeartRateBasis: "maximum",
    });
  });

  it("has no reference, and says why, without an FTP or without a heart rate", () => {
    const noFtp = progress({ ftpHistory: [], rides: weekly() });
    expect(noFtp.aerobicEfficiency).toMatchObject({ ftpAtStart: null, referencePower: null });
    expect(noFtp.aerobicEfficiency.trend.reason).toBe(
      "There is no FTP to set a reference power from.",
    );
    expect(noFtp.aerobicEfficiency.weeks.every((w) => w.heartRateAtPower === null)).toBe(true);
    const noHr = progress({ heartRates: { threshold: null, maximum: null }, rides: weekly() });
    expect(noHr.aerobicEfficiency).toMatchObject({
      referenceHeartRate: null,
      referenceHeartRateBasis: null,
    });
    expect(noHr.aerobicEfficiency.powerTrend.reason).toBe(
      "There is no threshold or maximum heart rate to set a reference heart rate from.",
    );
    // The other half still works.
    expect(noHr.aerobicEfficiency.trend.direction).toBe("improving");
  });

  it("leaves out a ride whose stretches are not worked out yet, without failing", () => {
    const answer = progress({ rides: [ride("2026-10-01")] });
    expect(answer.aerobicEfficiency.weeks[0]).toMatchObject({ heartRateAtPower: null });
  });
});

describe("best power per month", () => {
  const curve = (date: string, d300: number | undefined, d1200?: number, d3600?: number) =>
    ride(date, {
      name: `Ride ${date}`,
      best: {
        ...(d300 === undefined ? {} : { d300 }),
        ...(d1200 === undefined ? {} : { d1200 }),
        ...(d3600 === undefined ? {} : { d3600 }),
      },
    });

  it("takes the month's best of each duration, the later ride on a tie, and marks the current month partial", () => {
    const answer = progress({
      range: { from: "2026-09-01", to: TODAY },
      rides: [
        curve("2026-09-12", 300, 270),
        curve("2026-09-20", 310, 265),
        curve("2026-10-03", 320, 280, 240),
        curve("2026-10-18", 315, 280),
        curve("2026-11-07", 330),
      ],
    });
    const [sep, oct, nov] = answer.bestPower.months;
    expect(answer.bestPower.months.map((m) => [m.month, m.partial])).toEqual([
      ["2026-09", false],
      ["2026-10", false],
      ["2026-11", true],
    ]);
    expect(sep?.d300).toMatchObject({
      watts: 310,
      date: "2026-09-20",
      rideName: "Ride 2026-09-20",
    });
    expect(sep?.d1200).toMatchObject({ watts: 270, date: "2026-09-12" });
    expect(sep?.d3600).toBeNull();
    // Two rides with 280 W for 20 minutes: the later one.
    expect(oct?.d1200).toMatchObject({ watts: 280, date: "2026-10-18" });
    expect(oct?.d3600).toMatchObject({ watts: 240, date: "2026-10-03" });
    expect(nov?.d300).toMatchObject({ watts: 330 });
    expect(nov?.d1200).toBeNull();
  });

  it("puts the rider's weight behind watts per kilogram: the 7-day average at the end of the best ride's day, else the month's mean", () => {
    const weights = new Map<string, number>([
      ["2026-09-16", 72],
      ["2026-09-19", 74], // the best 5 minutes of September is on the 20th: the days 14 to 20 are 72 and 74, mean 73
      ["2026-10-25", 71],
      ["2026-10-26", 72], // nothing in the week before 18 Oct: the month's mean, 71.5
    ]);
    const answer = progress({
      range: { from: "2026-09-01", to: TODAY },
      weightKg: weights,
      rides: [
        curve("2026-09-20", 292),
        curve("2026-10-18", undefined, 280),
        curve("2026-11-07", 330),
      ],
    });
    const [sep, oct, nov] = answer.bestPower.months;
    // 292 / 73 = 4.0.
    expect(sep?.d300).toMatchObject({ weightKg: 73, wattsPerKg: 4 });
    // 280 / 71.5 = 3.916.
    expect(oct?.d1200).toMatchObject({ weightKg: 71.5, wattsPerKg: 3.92 });
    // No weight in November: no watts per kilogram, never a guess.
    expect(nov?.d300).toMatchObject({ watts: 330, weightKg: null, wattsPerKg: null });
  });

  it("judges each duration over the months that are over: the partial month is not in the trend", () => {
    const rides = [
      ["2026-06-10", 300, 280],
      ["2026-07-10", 306, 281],
      ["2026-08-10", 312, 279],
      ["2026-09-10", 318, 280],
      ["2026-10-10", 324, 281],
    ].map(([date, d300, d1200]) => curve(date as string, d300 as number, d1200 as number));
    // November so far is lower: it is not a point of the trend.
    rides.push(curve("2026-11-03", 200, 200));
    const answer = progress({ range: { from: "2026-06-01", to: TODAY }, rides });
    // 300, 306, ..., 324 over months 0 to 4: slope 6, change 24; band 2% of 300 = 6 W.
    expect(answer.bestPower.trends.d300).toMatchObject({
      direction: "improving",
      change: 24,
      from: 300,
      latest: 324,
      points: 5,
      minPoints: 3,
      steadyBand: 6,
      verdictText: "Best 5-minute power is trending up: better",
    });
    // 280, 281, 279, 280, 281: slope 0.1, change 0.4, band 5.6 W.
    expect(answer.bestPower.trends.d1200).toMatchObject({ direction: "steady", steadyBand: 5.6 });
    // No 60-minute ride: unknown.
    expect(answer.bestPower.trends.d3600).toMatchObject({
      direction: "unknown",
      points: 0,
      reason: "0 of 3 months have a 60-minute ride.",
    });
    // One line improves, one is steady, one is unknown: not two lines improving.
    expect(answer.bestPower.trend).toMatchObject({
      direction: "steady",
      lines: 2,
      verdictText: "Best power is steady",
    });
  });

  it("is unknown with two months, and the partial month does not make a third", () => {
    const answer = progress({
      range: { from: "2026-09-01", to: TODAY },
      rides: [curve("2026-09-10", 300), curve("2026-10-10", 310), curve("2026-11-10", 320)],
    });
    expect(answer.bestPower.trends.d300).toMatchObject({
      direction: "unknown",
      points: 2,
      reason: "2 of 3 months have a 5-minute ride.",
    });
  });

  it("uses the whole of a month that the range only touches", () => {
    // The range starts on the 25th of October: October's best is still the best of the month, including the 3rd.
    const answer = progress({
      range: { from: "2026-10-25", to: TODAY },
      rides: [curve("2026-10-03", 320), curve("2026-10-26", 300)],
    });
    expect(answer.bestPower.months[0]).toMatchObject({ month: "2026-10" });
    expect(answer.bestPower.months[0]?.d300).toMatchObject({ watts: 320, date: "2026-10-03" });
  });
});

describe("efficiency factor and aerobic drift", () => {
  const efRide = (date: string, value: number) =>
    ride(date, {
      name: "Threshold 2x20",
      stats: {
        stretches: [],
        efficiency: { value, normalizedPower: 240, averageHeartRate: 170, seconds: 2400 },
      },
    });
  const driftRide = (date: string, driftPercent: number) =>
    ride(date, {
      drift: {
        available: true,
        driftPercent,
        efFirst: 1.4,
        efSecond: 1.3,
        startSec: 0,
        endSec: 3000,
        seconds: 3000,
        variabilityIndex: 1.02,
      },
    });

  it("gives one value per ride and the mean of the last five", () => {
    const values = [1.3, 1.32, 1.34, 1.36, 1.38, 1.4, 1.42];
    const answer = progress({
      rides: values.map((v, i) => efRide(`2026-10-${String(i + 1).padStart(2, "0")}`, v)),
    });
    expect(answer.efficiencyFactor.rides.map((r) => r.value)).toEqual(values);
    // From the fifth ride on: 1.34 (rides 1 to 5), 1.36, 1.38.
    expect(answer.efficiencyFactor.rolling5.map((r) => r.value)).toEqual([1.34, 1.36, 1.38]);
    expect(answer.efficiencyFactor.trend).toMatchObject({
      direction: "improving",
      change: 0.12,
      from: 1.34,
      latest: 1.38,
      since: "2026-10-01",
      verdictText: "Efficiency factor is trending up: fitter",
    });
  });

  it("is unknown with four rides, and says how many", () => {
    const answer = progress({
      rides: [1.3, 1.32, 1.34, 1.36].map((v, i) => efRide(`2026-10-0${i + 1}`, v)),
    });
    expect(answer.efficiencyFactor.trend).toMatchObject({
      direction: "unknown",
      reason: "4 of 5 steady rides so far.",
      from: null,
      latest: null,
    });
    expect(answer.efficiencyFactor.rolling5).toEqual([]);
    expect(answer.efficiencyFactor.rides).toHaveLength(4);
  });

  it("only counts the rides in the range, oldest first", () => {
    const answer = progress({
      range: { from: "2026-10-10", to: TODAY },
      rides: [efRide("2026-10-20", 1.4), efRide("2026-10-05", 1.3), efRide("2026-10-12", 1.35)],
    });
    expect(answer.efficiencyFactor.rides.map((r) => r.date)).toEqual(["2026-10-12", "2026-10-20"]);
  });

  it("reads the aerobic drift as the existing metric worked it out, rides with one only", () => {
    const rides = [7, 6, 5, 4, 3, 2].map((d, i) => driftRide(`2026-10-0${i + 1}`, d));
    rides.push(ride("2026-10-09")); // no drift: not a point
    const answer = progress({ rides });
    expect(answer.decoupling.rides.map((r) => r.driftPercent)).toEqual([7, 6, 5, 4, 3, 2]);
    // Six rides: rolling means of the last five from the fifth: (7+6+5+4+3)/5 = 5, (6+5+4+3+2)/5 = 4.
    expect(answer.decoupling.rolling5.map((r) => r.value)).toEqual([5, 4]);
    // Slope -1 a ride, change -5, band 1 point; lower is better.
    expect(answer.decoupling.trend).toMatchObject({
      direction: "improving",
      change: -5,
      from: 5,
      latest: 4,
      verdictText: "Aerobic drift is trending down: better",
    });
    expect(answer.decoupling.trend.verdictText).not.toContain("fitter");
  });

  it("says worsening plainly when the drift rises", () => {
    const answer = progress({
      rides: [2, 3, 4, 5, 6].map((d, i) => driftRide(`2026-10-0${i + 1}`, d)),
    });
    expect(answer.decoupling.trend).toMatchObject({
      direction: "worsening",
      verdictText: "Aerobic drift is trending up",
    });
  });
});

describe("resting heart rate", () => {
  /** Resting heart rate on `nights` days of each week from 28 Sep, `mean(week)` each night. */
  function nightly(
    nights: (week: number) => number,
    mean: (week: number) => number,
    weeks = 8,
  ): Map<string, number> {
    const map = new Map<string, number>();
    for (let w = 0; w < weeks; w++) {
      const monday = days("2026-09-28", 7 * weeks)[w * 7] as string;
      for (const day of days(monday, nights(w))) map.set(day, mean(w));
    }
    return map;
  }

  it("is the weekly mean of the nights, from three nights, and falls by a beat a week", () => {
    const answer = progress({
      restingHr: nightly(
        (w) => (w === 3 ? 2 : 6),
        (w) => 55 - w,
      ),
    });
    expect(answer.restingHeartRate.weeks[0]).toEqual({
      weekStart: "2026-09-28",
      mean: 55,
      nights: 6,
    });
    // Two nights are not enough for a figure, and they are counted.
    expect(answer.restingHeartRate.weeks[3]).toEqual({
      weekStart: "2026-10-19",
      mean: null,
      nights: 2,
    });
    // Weeks 0, 1, 2, 4, 5, 6, 7 with 55, 54, 53, 51, 50, 49, 48: slope -1 a week over a span of 7 weeks.
    expect(answer.restingHeartRate.trend).toMatchObject({
      direction: "improving",
      change: -7,
      from: 55,
      latest: 48,
      points: 7,
      verdictText: "Resting heart rate is trending down: better",
    });
  });

  it("is unknown with three weeks of three nights", () => {
    const answer = progress({
      restingHr: nightly(
        (w) => (w < 3 ? 3 : 2),
        () => 52,
      ),
    });
    expect(answer.restingHeartRate.trend).toMatchObject({
      direction: "unknown",
      points: 3,
      reason: "3 of 4 weeks have 3 nights with the watch.",
    });
  });

  it("says nothing without the watch: every week has no figure and no night", () => {
    const answer = progress({ restingHr: new Map() });
    expect(answer.restingHeartRate.weeks.every((w) => w.mean === null && w.nights === 0)).toBe(
      true,
    );
    expect(answer.restingHeartRate.trend).toMatchObject({
      direction: "unknown",
      points: 0,
      from: null,
    });
  });

  it("is worsening, with no judging word, when it rises", () => {
    const answer = progress({
      restingHr: nightly(
        () => 7,
        (w) => 50 + w,
      ),
    });
    expect(answer.restingHeartRate.trend).toMatchObject({
      direction: "worsening",
      verdictText: "Resting heart rate is trending up",
    });
    expect(answer.headline).toMatchObject({ measure: "restingHeartRate", direction: "worsening" });
  });
});

describe("weight and watts per kilogram", () => {
  /** Two weighings on the Monday and Tuesday of each week from 28 Sep, `kg(week)` on both. */
  function weighings(kg: (week: number) => number, weeks = 8, per = 2): Map<string, number> {
    const map = new Map<string, number>();
    for (let w = 0; w < weeks; w++) {
      const monday = days("2026-09-28", 7 * weeks)[w * 7] as string;
      for (const day of days(monday, per)) map.set(day, kg(w));
    }
    return map;
  }

  it("is the weekly mean of the weighings, from two, with the FTP in force that week", () => {
    const answer = progress({
      ftpHistory: [
        { date: "2026-01-10", ftp: 250 },
        { date: "2026-10-12", ftp: 260 },
      ],
      weightKg: weighings(() => 72),
    });
    const weeks = answer.weight.weeks;
    // The week of 5 Oct ends on the 11th: 250 W. The week of 12 Oct ends on the 18th: 260 W.
    expect(weeks[1]).toMatchObject({
      weekStart: "2026-10-05",
      kg: 72,
      weighings: 2,
      ftp: 250,
      wattsPerKg: 3.47,
    });
    expect(weeks[2]).toMatchObject({ weekStart: "2026-10-12", kg: 72, ftp: 260, wattsPerKg: 3.61 });
    // 250 / 72 = 3.472; 260 / 72 = 3.611.
  });

  it("leaves a week with one weighing without a figure, and counts it", () => {
    const map = weighings(() => 72);
    map.delete("2026-10-06");
    const answer = progress({ weightKg: map });
    expect(answer.weight.weeks[1]).toMatchObject({
      kg: null,
      weighings: 1,
      ftp: 257,
      wattsPerKg: null,
    });
  });

  it("judges only watts per kilogram, and reports the weight without a verdict", () => {
    // 74.0 falling by 0.5 kg a week for 8 weeks at a fixed 257 W: 257 / 74 = 3.473 ... 257 / 70.5 = 3.645.
    const answer = progress({ weightKg: weighings((w) => 74 - w * 0.5) });
    expect(answer.weight.trend).toMatchObject({
      direction: "improving",
      unit: "W/kg",
      from: 3.47,
      latest: 3.65,
      points: 8,
      verdictText: "Watts per kilogram are trending up: better",
    });
    // The weight is reported plainly: 74.0 to 70.5, 3.5 kg less over 8 weekly means; no word of judgement in it.
    expect(answer.weight.weightChange).toEqual({
      points: 8,
      from: 74,
      latest: 70.5,
      difference: -3.5,
      since: "2026-09-28",
    });
    expect(JSON.stringify(answer.weight)).not.toMatch(/fitter|worse|bad/);
  });

  it("is steady when the weight and the FTP hold", () => {
    const answer = progress({ weightKg: weighings(() => 72) });
    expect(answer.weight.trend).toMatchObject({
      direction: "steady",
      change: 0,
      verdictText: "Watts per kilogram are steady",
    });
  });

  it("has no watts per kilogram without an FTP, and still reports the weight", () => {
    const answer = progress({ ftpHistory: [], weightKg: weighings((w) => 72 - w * 0.1) });
    expect(answer.weight.weeks.every((w) => w.ftp === null && w.wattsPerKg === null)).toBe(true);
    expect(answer.weight.trend).toMatchObject({
      direction: "unknown",
      points: 0,
      reason: "0 of 4 weeks have 2 weighings and an FTP.",
    });
    expect(answer.weight.weightChange).toMatchObject({
      points: 8,
      from: 72,
      latest: 71.3,
      difference: -0.7,
    });
  });

  it("has no weight change with fewer than two weekly figures, and no weight at all without the scale", () => {
    expect(progress({ weightKg: weighings(() => 72, 1) }).weight.weightChange).toBeNull();
    const none = progress({});
    expect(none.weight.weightChange).toBeNull();
    expect(none.weight.weeks.every((w) => w.kg === null && w.weighings === 0)).toBe(true);
  });

  it("is dated by the end of a week that is still going: the FTP in force today", () => {
    const answer = progress({
      range: { from: "2026-11-16", to: "2026-11-18" },
      today: "2026-11-18",
      ftpHistory: [
        { date: "2026-01-10", ftp: 250 },
        { date: "2026-11-20", ftp: 999 },
      ],
      weightKg: new Map([
        ["2026-11-16", 72],
        ["2026-11-17", 72],
      ]),
    });
    expect(answer.weight.weeks[0]).toMatchObject({ ftp: 250, wattsPerKg: 3.47 });
  });
});

describe("nothing at all, and the whole picture", () => {
  it("answers an empty history with unknown verdicts, no headline and a valid body", () => {
    const answer = progress({ ftpHistory: [], heartRates: { threshold: null, maximum: null } });
    expect(answer.headline).toBeNull();
    expect(answer.stale).toBe(false);
    expect(
      answer.bestPower.months.every((m) => m.d300 === null && m.d1200 === null && m.d3600 === null),
    ).toBe(true);
    for (const trend of [
      answer.bestPower.trends.d300,
      answer.aerobicEfficiency.trend,
      answer.efficiencyFactor.trend,
      answer.decoupling.trend,
      answer.restingHeartRate.trend,
      answer.weight.trend,
    ]) {
      expect(trend).toMatchObject({
        direction: "unknown",
        points: 0,
        change: null,
        verdictText: "Not enough data yet",
      });
    }
  });

  it("passes the staleness of the watch through, and the range as given", () => {
    const answer = progress({ stale: true });
    expect(answer.stale).toBe(true);
    expect(answer.range).toEqual({ from: "2026-09-28", to: TODAY });
    expect(answer.generatedAt).toBe("2026-11-22T07:40:00Z");
  });
});

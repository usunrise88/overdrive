import type { RideRecord, RideSample } from "@overdrive/contracts";
import { describe, expect, it } from "vitest";
import { aerobicDrift } from "../src/metrics/drift.ts";
import {
  confidenceFor,
  differencePercent,
  differsEnough,
  estimateFtp,
} from "../src/metrics/ftp.ts";
import { fitnessSeries, type LoadEntry } from "../src/metrics/load.ts";
import {
  bestEfforts,
  meanMaximalPower,
  type RideCurve,
  type RidePowerPoint,
} from "../src/metrics/power-curve.ts";
import { computeRideMetrics } from "../src/metrics/ride-metrics.ts";
import { sampleBalanceKj, wPrimeBalance } from "../src/metrics/wbal.ts";
import { constant, syntheticRide } from "./helpers/synthetic-rides.ts";

/**
 * The expected numbers below are worked out from the formulas in the comments (and, where a sum
 * is too long for the head, with an independent calculator), never by running the code under test.
 */

const sample = (power: number | null, extra: Partial<RideSample> = {}): RideSample => ({
  timeOffsetSeconds: 1,
  ...(power === null ? {} : { power }),
  ...extra,
});
const samplesOf = (powers: (number | null)[], paused: number[] = []): RideSample[] =>
  powers.map((power, i) => ({
    ...sample(power),
    timeOffsetSeconds: i + 1,
    ...(paused.includes(i + 1) ? { paused: true } : {}),
  }));

describe("mean-maximal power", () => {
  it("takes the best window of each duration the ride is long enough for", () => {
    // 100 100 100 | 300 300 300 300 300 | 100 100 100 100 (12 s).
    // 1, 2, 3 and 5 s: five 300 W seconds run together, so 300 W.
    // 8 s: the best 8-second window holds all five of them and three of 100 W: (5*300 + 3*100) / 8 = 225.
    // 10 s: (5*300 + 5*100) / 10 = 200. 15 s and longer: the ride is only 12 s.
    const curve = meanMaximalPower(
      samplesOf([100, 100, 100, 300, 300, 300, 300, 300, 100, 100, 100, 100]),
    );
    expect(curve).toEqual([
      { durationSec: 1, power: 300 },
      { durationSec: 2, power: 300 },
      { durationSec: 3, power: 300 },
      { durationSec: 5, power: 300 },
      { durationSec: 8, power: 225 },
      { durationSec: 10, power: 200 },
    ]);
  });

  it("counts a pause and a missing reading as 0 W: the window is the ride's own clock", () => {
    // 100 100 100 300 300 [gap] [paused, 300 W recorded] 300 100 100 100 100 -> the timeline is
    // 100 100 100 300 300 0 0 300 100 100 100 100.
    // 1 s: 300. 2 s: seconds 4-5, (300+300)/2 = 300. 3 s: seconds 3-5, (100+300+300)/3 = 233.3.
    // 5 s: seconds 4-8, (300+300+0+0+300)/5 = 180. 8 s: seconds 1-8, (3*100 + 2*300 + 0 + 0 + 300)/8 = 150.
    // 10 s: every window of ten holds 1400 W-seconds (seconds 1-10: 300 + 600 + 0 + 0 + 300 + 200 = 1400), so 140.
    const powers = [100, 100, 100, 300, 300, null, 300, 300, 100, 100, 100, 100];
    const curve = meanMaximalPower(samplesOf(powers, [7]));
    const by = (d: number) => curve.find((p) => p.durationSec === d)?.power;
    expect(by(1)).toBe(300);
    expect(by(2)).toBe(300);
    expect(by(3)).toBe(233.3);
    expect(by(5)).toBe(180);
    expect(by(8)).toBe(150);
    expect(by(10)).toBe(140);
  });

  it("has no curve when no power was measured, and none from a ride that is only paused", () => {
    expect(meanMaximalPower(samplesOf([null, null, null]))).toEqual([]);
    expect(meanMaximalPower(samplesOf([200, 200, 200], [1, 2, 3]))).toEqual([]);
    expect(meanMaximalPower([])).toEqual([]);
  });

  it("covers every one of the 25 durations a ride is long enough for", () => {
    // A ride of one hour at a constant 200 W: every duration up to 3600 s (23 of the 25) is 200 W.
    const curve = meanMaximalPower(
      syntheticRide({ id: "r", startEpochMillis: 1, seconds: 3600, power: constant(200) }).samples,
    );
    expect(curve).toHaveLength(23);
    expect(curve.every((p) => p.power === 200)).toBe(true);
    expect(curve[curve.length - 1]?.durationSec).toBe(3600);
    expect(curve.map((p) => p.durationSec).slice(0, 12)).toEqual([
      1, 2, 3, 5, 8, 10, 15, 20, 30, 45, 60, 90,
    ]);
  });

  describe("across rides", () => {
    const ride = (id: string, start: number, points: [number, number][]): RideCurve => ({
      rideId: id,
      date: `2026-11-${String(start).padStart(2, "0")}`,
      startEpochMillis: start,
      curve: points.map(([durationSec, power]) => ({ durationSec, power })) as RidePowerPoint[],
    });

    it("takes the highest power for each duration with its ride and day, ascending, and leaves out what nobody has", () => {
      const best = bestEfforts([
        ride("a", 10, [
          [60, 400],
          [300, 300],
        ]),
        ride("b", 12, [
          [60, 380],
          [300, 310],
          [1200, 260],
        ]),
      ]);
      expect(best).toEqual([
        { durationSec: 60, power: 400, rideId: "a", date: "2026-11-10" },
        { durationSec: 300, power: 310, rideId: "b", date: "2026-11-12" },
        { durationSec: 1200, power: 260, rideId: "b", date: "2026-11-12" },
      ]);
    });

    it("gives a tie to the later ride", () => {
      const best = bestEfforts([ride("early", 10, [[300, 300]]), ride("late", 12, [[300, 300]])]);
      expect(best[0]?.rideId).toBe("late");
    });
  });
});

describe("daily load, fitness, fatigue and form", () => {
  // k42 = 1 - exp(-1/42) = 0.0235283, k7 = 1 - exp(-1/7) = 0.1331221, and each day
  //   average = average_yesterday + (load - average_yesterday) * k      (0 the day before the first load)
  //   form    = fitness - fatigue                                       (the same day)
  const ride = (date: string, load: number): LoadEntry => ({ date, kind: "cycling", load });

  it("seeds at 0 on the first day with load and runs on through the rest days", () => {
    // One load of 100 on 2 Nov.
    //  2 Nov: ctl = 100*k42 = 2.3528, atl = 100*k7 = 13.3122, tsb = 2.3528 - 13.3122 = -10.9594
    //  3 Nov: ctl = 2.3528*(1-k42) = 2.2975, atl = 13.3122*(1-k7) = 11.5401, tsb = -9.2427
    //  4 Nov: ctl = 2.2434, atl = 10.0038, tsb = -7.7604       5 Nov: ctl = 2.1906, atl = 8.6721, tsb = -6.4815
    const { days, firstLoadDate } = fitnessSeries(
      [ride("2026-11-02", 100)],
      "2026-11-01",
      "2026-11-05",
    );
    expect(firstLoadDate).toBe("2026-11-02");
    expect(days.map(({ date, load, ctl, atl, tsb }) => [date, load, ctl, atl, tsb])).toEqual([
      ["2026-11-01", 0, 0, 0, 0],
      ["2026-11-02", 100, 2.4, 13.3, -11],
      ["2026-11-03", 0, 2.3, 11.5, -9.2],
      ["2026-11-04", 0, 2.2, 10, -7.8],
      ["2026-11-05", 0, 2.2, 8.7, -6.5],
    ]);
  });

  it("starts from the whole history, not from the first day asked for", () => {
    const { days } = fitnessSeries([ride("2026-11-02", 100)], "2026-11-04", "2026-11-05");
    expect(days.map((d) => [d.date, d.ctl, d.atl])).toEqual([
      ["2026-11-04", 2.2, 10],
      ["2026-11-05", 2.2, 8.7],
    ]);
  });

  it("adds the sources of a day, keeps cycling and other apart, and counts a rest day", () => {
    // 1 Nov: 100. 2 Nov: rest. 3 Nov: 30 from a ride and 20 from another activity, 50 in all.
    //  1 Nov: ctl 2.3528, atl 13.3122.  2 Nov: 2.2975, 11.5401.
    //  3 Nov: ctl = 2.2975 + (50 - 2.2975)*k42 = 2.2975 + 1.1224 = 3.4199; atl = 11.5401 + (50 - 11.5401)*k7 = 11.5401 + 5.1199 = 16.6600; tsb = -13.2401
    const { days } = fitnessSeries(
      [
        ride("2026-11-01", 100),
        ride("2026-11-03", 30),
        { date: "2026-11-03", kind: "other", load: 20 },
      ],
      "2026-11-01",
      "2026-11-03",
    );
    expect(days[1]).toMatchObject({ load: 0, ctl: 2.3, atl: 11.5, tsb: -9.2 });
    expect(days[2]).toEqual({
      date: "2026-11-03",
      load: 50,
      loadCycling: 30,
      loadOther: 20,
      ctl: 3.4,
      atl: 16.7,
      tsb: -13.2,
    });
  });

  it("settles at the load of a rider who trains the same every day", () => {
    // 100 a day for 210 days: ctl = 100*(1 - exp(-210/42)) = 100*(1 - e^-5) = 99.3262, atl = 100.0000.
    const entries = Array.from({ length: 210 }, (_, i) => {
      const day = new Date(Date.UTC(2026, 0, 1) + i * 86_400_000).toISOString().slice(0, 10);
      return ride(day, 100);
    });
    const { days } = fitnessSeries(entries, "2026-07-29", "2026-07-29");
    expect(days[0]).toMatchObject({ date: "2026-07-29", ctl: 99.3, atl: 100, tsb: -0.7 });
  });

  it("is all zero with no load, ignores what comes after the range, and does not count a negative or non-finite load", () => {
    expect(fitnessSeries([], "2026-11-01", "2026-11-03")).toEqual({
      firstLoadDate: null,
      days: ["2026-11-01", "2026-11-02", "2026-11-03"].map((date) => ({
        date,
        load: 0,
        loadCycling: 0,
        loadOther: 0,
        ctl: 0,
        atl: 0,
        tsb: 0,
      })),
    });
    const later = fitnessSeries([ride("2026-11-10", 80)], "2026-11-01", "2026-11-03");
    expect(later.firstLoadDate).toBeNull();
    expect(later.days.every((d) => d.ctl === 0)).toBe(true);
    const odd = fitnessSeries(
      [ride("2026-11-01", -50), ride("2026-11-01", Number.NaN), ride("2026-11-02", 40)],
      "2026-11-01",
      "2026-11-02",
    );
    expect(odd.days[0]?.load).toBe(0);
    expect(odd.days[1]?.load).toBe(40);
  });
});

describe("eFTP", () => {
  const curve = (rideId: string, day: number, points: Record<number, number>): RideCurve => ({
    rideId,
    date: `2026-11-${String(day).padStart(2, "0")}`,
    startEpochMillis: day,
    curve: Object.entries(points).map(([d, power]) => ({
      durationSec: Number(d),
      power,
    })) as RidePowerPoint[],
  });

  it("is 95% of the best 20-minute power when two rides have a comparable effort", () => {
    // Best 20 minutes: 300 W; 0.95 * 300 = 285. A second ride at 280 W is above 90% of 300 (270): two rides back it, low confidence.
    const result = estimateFtp([curve("a", 10, { 1200: 300 }), curve("b", 12, { 1200: 280 })]);
    expect(result).toEqual({
      ok: true,
      estimate: {
        value: 285,
        basis: { durationSec: 1200, power: 300, rideId: "a", date: "2026-11-10" },
        confidence: "low",
        supportingRides: 2,
      },
    });
  });

  it("does not count one unusual ride: a second comparable effort is needed", () => {
    // The second ride's 20 minutes (250 W) is under 90% of 300 W (270 W).
    const result = estimateFtp([curve("a", 10, { 1200: 300 }), curve("b", 12, { 1200: 250 })]);
    expect(result.ok).toBe(false);
    if (!result.ok)
      expect(result.reason).toMatch(/Only 1 ride in the last 42 days has a 20-minute effort/);
  });

  it("says why when no ride has 20 minutes of power, or there is no ride", () => {
    const short = estimateFtp([curve("a", 10, { 300: 320 })]);
    expect(short.ok === false && short.reason).toBe(
      "No ride in the last 42 days has 20 minutes of power",
    );
    const none = estimateFtp([]);
    expect(none.ok === false && none.reason).toBe("No ride in the last 42 days");
  });

  it("takes the 60-minute power as it is when that gives more", () => {
    // 20 min: best 250 -> 237.5. 60 min: best 245 (the second ride's 240 is above 90%: 220.5) -> 245 as it is. 245 wins.
    const result = estimateFtp([
      curve("a", 10, { 1200: 250, 3600: 245 }),
      curve("b", 12, { 1200: 245, 3600: 240 }),
    ]);
    expect(result.ok && result.estimate.value).toBe(245);
    expect(result.ok && result.estimate.basis).toEqual({
      durationSec: 3600,
      power: 245,
      rideId: "a",
      date: "2026-11-10",
    });
  });

  it("rounds half up to whole watts: 310 * 0.95 = 294.5 is 295", () => {
    const result = estimateFtp([curve("a", 10, { 1200: 310 }), curve("b", 12, { 1200: 300 })]);
    expect(result.ok && result.estimate.value).toBe(295);
  });

  it("is more confident with more rides behind the effort: 2 low, 3 to 4 medium, 5 or more high", () => {
    expect([2, 3, 4, 5, 9].map(confidenceFor)).toEqual(["low", "medium", "medium", "high", "high"]);
    const rides = Array.from({ length: 5 }, (_, i) => curve(`r${i}`, 10 + i, { 1200: 300 - i }));
    expect(estimateFtp(rides)).toMatchObject({
      ok: true,
      estimate: { confidence: "high", supportingRides: 5 },
    });
  });

  it("is worth suggesting from 3% away from the FTP, exactly, and always when there is no FTP", () => {
    // |value - ftp| * 100 >= 3 * ftp: 206 against 200 is exactly 3%; 205 is 2.5%; 194 is exactly 3% below; 195 is 2.5%.
    expect(differsEnough(206, 200)).toBe(true);
    expect(differsEnough(205, 200)).toBe(false);
    expect(differsEnough(194, 200)).toBe(true);
    expect(differsEnough(195, 200)).toBe(false);
    expect(differsEnough(285, 277)).toBe(false); // 8 W is 2.89%
    expect(differsEnough(285, 276)).toBe(true); // 9 W is 3.26%
    expect(differsEnough(100, null)).toBe(true);
    // 285 against 270 is 15/270 = 5.5556%, one decimal 5.6.
    expect(differencePercent(285, 270)).toBe(5.6);
    expect(differencePercent(285, null)).toBeNull();
  });
});

describe("aerobic drift", () => {
  /** A ride's samples: constant power, heart rate from a function of the second. */
  function ride(
    seconds: number,
    power: (s: number) => number | null,
    heartRate: (s: number) => number | null,
    extra: (s: number) => Partial<RideSample> = () => ({}),
  ): RideRecord {
    return syntheticRide({
      id: "drift",
      startEpochMillis: 1,
      seconds,
      power,
      heartRate,
      paused: (s) => extra(s).paused === true,
      step: (s) => (s <= 600 ? 0 : 1),
    });
  }

  it("is how much the efficiency factor fell from the first half to the second", () => {
    // 3000 s at 200 W; heart rate 140 in the first 1500 s and 147 in the next 1500 s.
    // Steady power, so normalised power is 200 W in both halves. EF = 200/140 = 1.4286, then 200/147 = 1.3605.
    // Drift = (1.4286 - 1.3605) / 1.4286 = 4.76% (also 1 - 140/147 = 7/147).
    const record = ride(3000, constant(200), (s) => (s <= 1500 ? 140 : 147));
    const result = aerobicDrift(record.samples, [{ startSeconds: 0, endSeconds: 3000 }]);
    expect(result).toEqual({
      available: true,
      driftPercent: 4.8,
      efFirst: 1.429,
      efSecond: 1.361,
      startSec: 0,
      endSec: 3000,
      seconds: 3000,
      variabilityIndex: 1,
    });
  });

  it("measures the long steady lap of a structured ride when the whole ride is not steady", () => {
    // 600 s at 100 W (heart rate 100), then 2400 s at 200 W (140 for 1200 s, 147 for 1200 s).
    // The whole ride's variability index is 189.96 / 180 = 1.055, above 1.05; the lap (seconds 600 to 3000) is 40 minutes and steady.
    const record = ride(
      3000,
      (s) => (s <= 600 ? 100 : 200),
      (s) => (s <= 600 ? 100 : s <= 1800 ? 140 : 147),
    );
    const result = aerobicDrift(record.samples, record.laps);
    expect(result).toMatchObject({
      available: true,
      startSec: 600,
      endSec: 3000,
      driftPercent: 4.8,
      efFirst: 1.429,
      efSecond: 1.361,
    });
  });

  it("is skipped, with the reason, for a ride under 40 minutes", () => {
    const record = ride(1500, constant(200), constant(140));
    expect(aerobicDrift(record.samples, record.laps)).toEqual({
      available: false,
      reason:
        "The ride is 25 minutes long; aerobic drift needs a steady stretch of at least 40 minutes",
    });
  });

  it("is skipped for a ride that is not steady: variability index above 1.05", () => {
    // 60 s at 100 W then 60 s at 300 W, repeated: normalised power 239.1, average 200, index 1.196.
    const record = ride(
      3000,
      (s) => (Math.floor((s - 1) / 60) % 2 === 0 ? 100 : 300),
      constant(140),
    );
    const result = aerobicDrift(record.samples, [{ startSeconds: 0, endSeconds: 3000 }]);
    expect(result.available).toBe(false);
    if (!result.available)
      expect(result.reason).toMatch(/not steady: its variability index is 1\.2, above 1\.05/);
  });

  it("is skipped when heart rate or power is missing, or the ride is paused, for more than 5% of it", () => {
    const noHeart = ride(3000, constant(200), (s) => (s % 10 < 2 ? null : 140)); // 20% without heart rate
    const result = aerobicDrift(noHeart.samples, [{ startSeconds: 0, endSeconds: 3000 }]);
    expect(result.available).toBe(false);
    if (!result.available) expect(result.reason).toMatch(/only 80% of its 50 minutes/);

    // 5% paused is the limit: 150 of 3000 s still measures, 151 does not.
    const paused = (n: number) =>
      ride(3000, constant(200), constant(140), (s) => ({ paused: s <= n }));
    expect(aerobicDrift(paused(150).samples, []).available).toBe(true);
    expect(aerobicDrift(paused(151).samples, []).available).toBe(false);
  });

  it("is skipped for a ride with no samples", () => {
    expect(aerobicDrift([], [])).toEqual({ available: false, reason: "The ride has no samples" });
  });
});

describe("W′ balance", () => {
  // Skiba 2012, integral model, 1 Hz:  E(t) = E(t-1) * exp(-1/tau) + max(P - CP, 0);  balance = W′ - E.
  //   tau = 546 * exp(-0.01 * D_CP) + 316,  D_CP = CP - mean of the powers below CP.
  const watts = (powers: number[], paused: number[] = []): RideSample[] =>
    samplesOf(powers, paused);

  it("follows the model: 10 s at 300 W over a CP of 200 W, then 20 s at 100 W", () => {
    // D_CP = 200 - 100 = 100 W, tau = 546*e^-1 + 316 = 516.86 s, r = e^(-1/516.86) = 0.998068.
    // Each of the first 10 s uses 100 J; E(10) = 100 * (1 - r^10)/(1 - r) = 991.35 J, balance 19008.65 J (the lowest, at t = 10).
    // Then E decays by r each second: 19027.65 J at t = 20, 19046.28 J at t = 30.
    const result = wPrimeBalance(watts([...Array(10).fill(300), ...Array(20).fill(100)]), {
      cpWatts: 200,
      wPrimeJoules: 20_000,
    });
    expect(result.dCpWatts).toBeCloseTo(100, 6);
    expect(result.tauSeconds).toBeCloseTo(516.86, 2);
    expect(result.minBalanceJoules).toBeCloseTo(19008.65, 1);
    expect(result.minAtSeconds).toBe(10);
    expect(result.balanceJoules[19]).toBeCloseTo(19027.65, 1);
    expect(result.balanceJoules[29]).toBeCloseTo(19046.28, 1);
    // Every fifth second, in kilojoules to two decimals.
    expect(sampleBalanceKj(result, 5)).toEqual([19.5, 19.01, 19.02, 19.03, 19.04, 19.05]);
  });

  it("uses all of W′ nowhere when the rider stays under CP, and tau follows how far below", () => {
    // All 100 W over CP 200: D_CP = 100, tau = 516.86; the balance never leaves W′.
    const under = wPrimeBalance(watts(Array(50).fill(100)), { cpWatts: 200, wPrimeJoules: 20_000 });
    expect(under.minBalanceJoules).toBe(20_000);
    expect(under.minAtSeconds).toBe(0);
    expect(under.tauSeconds).toBeCloseTo(516.86, 2);
  });

  it("has D_CP of 0 and tau of 862 s when no second is below CP", () => {
    // 20 s at 250 W over CP 200: 50 J a second, tau = 546 + 316 = 862 s; the lowest balance is at the end, 19010.94 J.
    const above = wPrimeBalance(watts(Array(20).fill(250)), { cpWatts: 200, wPrimeJoules: 20_000 });
    expect(above.dCpWatts).toBe(0);
    expect(above.tauSeconds).toBe(862);
    expect(above.balanceJoules[0]).toBe(19_950);
    expect(above.minBalanceJoules).toBeCloseTo(19010.94, 1);
    expect(above.minAtSeconds).toBe(20);
  });

  it("lets a pause recover W′ without counting it among the seconds below CP", () => {
    // 10 s at 300 W, then 20 s paused: no measured second is below CP, so D_CP = 0 and tau = 862 s.
    // The lowest balance is 19005.20 J at t = 10, and the pause gives back 19016.68 J at t = 20 and 19028.02 J at t = 30.
    const result = wPrimeBalance(
      watts(
        [...Array(10).fill(300), ...Array(20).fill(300)],
        Array.from({ length: 20 }, (_, i) => 11 + i),
      ),
      { cpWatts: 200, wPrimeJoules: 20_000 },
    );
    expect(result.dCpWatts).toBe(0);
    expect(result.tauSeconds).toBe(862);
    expect(result.minBalanceJoules).toBeCloseTo(19005.2, 1);
    expect(result.minAtSeconds).toBe(10);
    expect(result.balanceJoules[19]).toBeCloseTo(19016.68, 1);
    expect(result.balanceJoules[29]).toBeCloseTo(19028.02, 1);
  });

  it("goes below 0 when W′ is exceeded", () => {
    // 100 s at 400 W over CP 200 with W′ 10 kJ: 200 J a second, over 19 kJ expended by the end.
    const result = wPrimeBalance(watts(Array(100).fill(400)), {
      cpWatts: 200,
      wPrimeJoules: 10_000,
    });
    expect(result.minBalanceJoules).toBeLessThan(0);
    expect(result.minAtSeconds).toBe(100);
  });
});

describe("what is kept per ride", () => {
  it("is the load of the ride's own FTP, its curve and its drift", () => {
    // One hour at a constant 200 W with an FTP of 250: NP = 200, IF = 0.8, TSS = 3600 * 200 * 0.8 / (250 * 3600) * 100 = 64.0.
    const record = syntheticRide({
      id: "one-hour",
      startEpochMillis: 1,
      seconds: 3600,
      power: constant(200),
      heartRate: (s) => (s <= 1800 ? 140 : 147),
    });
    const metrics = computeRideMetrics(record);
    expect(metrics).toMatchObject({
      durationSeconds: 3600,
      movingSeconds: 3600,
      ftp: 250,
      load: 64,
      normalizedWatts: 200,
      averageWatts: 200,
    });
    expect(metrics.powerCurve.find((p) => p.durationSec === 1200)?.power).toBe(200);
    expect(metrics.drift).toMatchObject({ available: true, driftPercent: 4.8 });
  });

  it("computes a year of hour-long rides in about a second, once, when they are stored", () => {
    // Ten different hours, each used forty times: 400 rides of 3600 samples. Only the computation is timed, not the making of the rides.
    const records = Array.from({ length: 10 }, (_, i) =>
      syntheticRide({
        id: `ride-${i}`,
        startEpochMillis: 1_700_000_000_000 + i * 86_400_000,
        seconds: 3600,
        power: (s) => 180 + ((s * 7 + i) % 60),
        heartRate: (s) => 120 + (s % 30),
      }),
    );
    const started = performance.now();
    for (let i = 0; i < 400; i++) computeRideMetrics(records[i % records.length] as RideRecord);
    const ms = performance.now() - started;
    // 400 one-hour rides (1.4 million samples) take under a second here (about 2 ms a ride); bounded at 4 s for a loaded machine.
    // This is the once-per-ride cost: a request reads the stored rows (see metrics-service.test.ts).
    expect(ms).toBeLessThan(4000);
  });
});

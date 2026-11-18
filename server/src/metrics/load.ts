import { isoDateToEpochDay } from "@overdrive/contracts";

/**
 * Daily training load, fitness (CTL), fatigue (ATL) and form (TSB) (spec §6.3).
 *
 * Load comes from **sources**. A source says, for each local day, how much training stress it
 * contributed and whether that is `cycling` (a ride: power-based, counts everywhere) or `other`
 * (another activity, heart-rate based, spec §6.7: counts toward fatigue, form and recovery but never
 * toward the power curve, eFTP or drift, which read rides only). Rides are the one source this
 * stage implements; the others register a {@link LoadSourceProvider} with the metrics service.
 *
 * The averages are the textbook exponentially weighted ones (ADR 0022):
 *
 *     k   = 1 - exp(-1 / tau)            tau = 42 days for fitness, 7 days for fatigue
 *     avg = avg_yesterday + (load_today - avg_yesterday) * k
 *     tsb = ctl - atl                    on the same day, at the end of it
 *
 * Seed: both averages are 0 on the day before the first day with any load, and the series runs day
 * by day from there, rest days (load 0) included. Before the first day all three values are 0. There
 * is no acute:chronic workload ratio (spec §6.3: its use is disputed, Impellizzeri et al. 2020).
 */

export type LoadKind = "cycling" | "other";

/** What a source contributes to one local day. A day may have several entries. */
export interface LoadEntry {
  /** The rider's local day, `YYYY-MM-DD`. */
  date: string;
  kind: LoadKind;
  /** Training stress, 100 being one hour at threshold; not negative. */
  load: number;
}

/**
 * Where load comes from. `entries` returns every entry from the first day the source has anything
 * up to and including `untilDate`: fitness depends on all the history, not on a window.
 */
export interface LoadSourceProvider {
  /** For the log and for telling the sources apart. */
  readonly name: string;
  entries(untilDate: string): Promise<LoadEntry[]>;
}

/** The time constant of fitness, in days. */
export const FITNESS_DAYS = 42;
/** The time constant of fatigue, in days. */
export const FATIGUE_DAYS = 7;

const K_FITNESS = 1 - Math.exp(-1 / FITNESS_DAYS);
const K_FATIGUE = 1 - Math.exp(-1 / FATIGUE_DAYS);

const round1 = (value: number): number => Math.round(value * 10) / 10;

export interface FitnessDay {
  date: string;
  load: number;
  loadCycling: number;
  loadOther: number;
  ctl: number;
  atl: number;
  tsb: number;
}

export interface FitnessSeries {
  /** The first local day with any entry on or before `to` (not only inside the range), or null. */
  firstLoadDate: string | null;
  /** One per day from `from` to `to`, loads and averages rounded to one decimal. */
  days: FitnessDay[];
}

/** The sums per local day of the entries on or before `until`, by kind. Negative or non-finite loads count as 0. */
export function dailyLoads(
  entries: readonly LoadEntry[],
  until: string,
): Map<string, { cycling: number; other: number }> {
  const days = new Map<string, { cycling: number; other: number }>();
  for (const entry of entries) {
    if (entry.date > until) continue;
    const day = days.get(entry.date) ?? { cycling: 0, other: 0 };
    const load = Number.isFinite(entry.load) && entry.load > 0 ? entry.load : 0;
    if (entry.kind === "cycling") day.cycling += load;
    else day.other += load;
    days.set(entry.date, day);
  }
  return days;
}

const epochDayToIsoDate = (day: number): string =>
  new Date(day * 86_400_000).toISOString().slice(0, 10);

/**
 * Daily load, fitness, fatigue and form for every day from `from` to `to` (both included, real
 * dates, `from` not after `to`), from entries that may reach back any distance before `from`.
 * Entries after `to` are ignored.
 */
export function fitnessSeries(
  entries: readonly LoadEntry[],
  from: string,
  to: string,
): FitnessSeries {
  const fromDay = isoDateToEpochDay(from);
  const toDay = isoDateToEpochDay(to);
  if (fromDay === null || toDay === null || toDay < fromDay) {
    throw new Error(`Not a range of calendar dates: ${from} to ${to}`);
  }
  const daily = dailyLoads(entries, to);
  let firstLoadDate: string | null = null;
  for (const date of daily.keys()) {
    if (firstLoadDate === null || date < firstLoadDate) firstLoadDate = date;
  }
  const firstLoadDay = firstLoadDate === null ? null : isoDateToEpochDay(firstLoadDate);

  let ctl = 0;
  let atl = 0;
  const days: FitnessDay[] = [];
  // From the first day with load, so the averages have seen all the history; from `from` when that is later.
  const startDay = firstLoadDay !== null && firstLoadDay < fromDay ? firstLoadDay : fromDay;
  for (let day = startDay; day <= toDay; day++) {
    const date = epochDayToIsoDate(day);
    const today = daily.get(date);
    const cycling = today?.cycling ?? 0;
    const other = today?.other ?? 0;
    const load = cycling + other;
    // Before the first day with any load the averages are the seed, 0, and stay 0.
    if (firstLoadDay !== null && day >= firstLoadDay) {
      ctl += (load - ctl) * K_FITNESS;
      atl += (load - atl) * K_FATIGUE;
    }
    if (day >= fromDay) {
      days.push({
        date,
        load: round1(load),
        loadCycling: round1(cycling),
        loadOther: round1(other),
        ctl: round1(ctl),
        atl: round1(atl),
        tsb: round1(ctl - atl),
      });
    }
  }
  return { firstLoadDate, days };
}

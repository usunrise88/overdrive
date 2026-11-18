/**
 * The numbers a written note may quote (spec §6.5, ADR 0025). Every number that goes into the facts goes through a
 * {@link Quotes} collector, which also lists it, with a label and a unit, in the facts' `quotable` array: the facts cannot hold a
 * number that is not quotable, and the validator (`validator.ts`) rejects a text that quotes a number that is not in them.
 */

/** One number the text may quote, with what it is and its unit (`W`, `bpm`, `min`, `load`, `%`, `h`, `kJ`, `days`, `sessions`, ...). */
export interface Quantity {
  label: string;
  value: number;
  unit: string;
}

export function roundTo(value: number, digits: number): number {
  const factor = 10 ** digits;
  const rounded = Math.round(value * factor) / factor;
  // -0 is "0" in a text and a different value to a comparison.
  return rounded === 0 ? 0 : rounded;
}

export class Quotes {
  readonly list: Quantity[] = [];
  private readonly seen = new Set<string>();

  /**
   * Registers a number rounded to `digits` decimals and returns it, so that the facts hold exactly the number that is quotable.
   * `null` and `undefined` stay `null` (there is nothing to quote).
   */
  n(label: string, value: number | null | undefined, unit: string, digits = 0): number | null {
    if (value === null || value === undefined || !Number.isFinite(value)) return null;
    const rounded = roundTo(value, digits);
    const key = `${label}\u0000${rounded}\u0000${unit}`;
    if (!this.seen.has(key)) {
      this.seen.add(key);
      this.list.push({ label, value: rounded, unit });
    }
    return rounded;
  }

  /**
   * A length of time in whole minutes, also registered as hours and minutes and as decimal hours when it is an hour or more, so that
   * a text may say "75 minutes", "1 h 15 min" or "1.3 hours" and nothing else.
   */
  minutes(label: string, seconds: number | null | undefined): number | null {
    if (seconds === null || seconds === undefined || !Number.isFinite(seconds)) return null;
    const total = Math.round(seconds / 60);
    this.n(label, total, "min");
    if (total >= 60) {
      this.n(`${label} (hours part)`, Math.floor(total / 60), "h");
      this.n(`${label} (minutes part)`, total % 60, "min");
      this.n(`${label} (in hours)`, total / 60, "h", 1);
    }
    return total;
  }
}

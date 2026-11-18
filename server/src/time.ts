const formatters = new Map<string, Intl.DateTimeFormat>();

/** Whether `name` is an IANA time zone this runtime knows (`Europe/Paris`, `UTC`). */
export function isTimeZone(name: string): boolean {
  try {
    new Intl.DateTimeFormat("en-CA", { timeZone: name });
    return true;
  } catch {
    return false;
  }
}

/** The calendar date, `YYYY-MM-DD`, that `instant` falls on in `timeZone`. */
export function localDate(instant: Date | number, timeZone: string): string {
  let formatter = formatters.get(timeZone);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    });
    formatters.set(timeZone, formatter);
  }
  const parts = Object.fromEntries(
    formatter.formatToParts(instant).map((part) => [part.type, part.value]),
  );
  return `${parts.year}-${parts.month}-${parts.day}`;
}

const offsetFormatters = new Map<string, Intl.DateTimeFormat>();

/** How far `timeZone`'s clock is ahead of UTC at `instant`, in milliseconds. */
function offsetMillis(instant: number, timeZone: string): number {
  let formatter = offsetFormatters.get(timeZone);
  if (formatter === undefined) {
    formatter = new Intl.DateTimeFormat("en-CA", {
      timeZone,
      hourCycle: "h23",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
    });
    offsetFormatters.set(timeZone, formatter);
  }
  const parts = Object.fromEntries(
    formatter.formatToParts(instant).map((part) => [part.type, Number(part.value)]),
  ) as Record<string, number>;
  const asUtc = Date.UTC(
    parts.year as number,
    (parts.month as number) - 1,
    parts.day as number,
    parts.hour as number,
    parts.minute as number,
    parts.second as number,
  );
  return asUtc - Math.floor(instant / 1000) * 1000;
}

/**
 * The instant, in epoch milliseconds, at which the calendar day `date` (`YYYY-MM-DD`) begins in
 * `timeZone`: local midnight, or the first instant of the day where a clock change skips midnight.
 */
export function dayStartMillis(date: string, timeZone: string): number {
  const [year, month, day] = date.split("-").map(Number) as [number, number, number];
  const midnightAsUtc = Date.UTC(year, month - 1, day);
  // The offset at midnight depends on the instant, which depends on the offset: two rounds settle it
  // except across a change that happens at midnight itself.
  let guess = midnightAsUtc - offsetMillis(midnightAsUtc, timeZone);
  guess = midnightAsUtc - offsetMillis(guess, timeZone);
  return guess;
}

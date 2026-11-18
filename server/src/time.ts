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

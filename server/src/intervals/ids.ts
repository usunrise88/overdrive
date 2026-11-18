/** The id of the planned workout made from intervals.icu event `eventId`: `icu-123`. */
export function plannedIdFor(eventId: number | string): string {
  return `icu-${eventId}`;
}

/** The intervals.icu event id inside a planned workout id, or null when the id is not one of ours. */
export function eventIdOf(plannedId: string): number | null {
  const match = /^icu-(\d{1,15})$/.exec(plannedId);
  return match === null ? null : Number(match[1]);
}

/** The calendar sports a bike workout can be planned under; others (Run, Swim, …) are not for the tablet. */
const CYCLING_TYPES = new Set([
  "Ride",
  "VirtualRide",
  "GravelRide",
  "MountainBikeRide",
  "EMountainBikeRide",
  "TrackRide",
]);

/** An event without a sport counts as a ride: the tablet is a bike app. */
export function isCyclingType(type: string | null | undefined): boolean {
  return type === null || type === undefined || type === "" || CYCLING_TYPES.has(type);
}

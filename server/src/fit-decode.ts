import { checkFit } from "./fit.ts";

/**
 * A small FIT decoder for the few messages an outdoor ride needs (spec §11.8, ADR 0026): `file_id`, `sport`, `session`, `lap`,
 * `record` and `activity`. Everything else, and every developer field, is skipped by its size.
 *
 * It is our own and not Garmin's `@garmin/fitsdk`: that SDK's licence is proprietary (non-transferable, no redistribution, its
 * files "Confidential Information of Garmin"), which does not fit a public repository and a published server image. The format
 * is described by `docs/protocols/fit.md`; the field numbers below were checked against the published profile (21.222).
 *
 * Layout: a 12- or 14-byte header, `dataSize` bytes of records, a CRC-16. A record starts with a header byte: bit 7 set is a
 * compressed-timestamp data message (bits 5-6 the local type, bits 0-4 a time offset); otherwise bit 6 set is a definition
 * message (bit 5: developer field definitions follow) and clear is a data message of the local type in bits 0-3. A definition
 * names the global message number, the byte order and each field's number, size and base type.
 *
 * Nothing here quotes a value in an error: reasons name a structure and, at most, a size or an index.
 */

/** The FIT epoch, 1989-12-31T00:00:00Z, in Unix milliseconds. */
const FIT_EPOCH_MS = 631_065_600_000;
/** Timestamps below this are not dates (the profile's minimum of `date_time`). */
const MIN_DATE_TIME = 0x1000_0000;
/** A file with more records than this is refused: it bounds the memory a 16 MiB upload can take (about 110 hours at 1 Hz). */
export const MAX_FIT_RECORDS = 400_000;
const SEMICIRCLES_TO_DEGREES = 180 / 2 ** 31;

/** The `file_id` type of an activity file. */
export const FIT_FILE_ACTIVITY = 4;
/** `sport` enum values this service cares about. */
export const FIT_SPORT_CYCLING = 2;
export const FIT_SPORT_E_BIKING = 21;

/** One `record`: the readings of a moment. A field the file did not give (or marked invalid) is absent. */
export interface FitRecord {
  /** Unix milliseconds. */
  timeMs: number;
  heartRate?: number;
  cadence?: number;
  power?: number;
  /** Metres per second. */
  speed?: number;
  /** Cumulative metres. */
  distance?: number;
  /** Metres. */
  altitude?: number;
  /** Degrees. */
  latitude?: number;
  longitude?: number;
}

export interface FitSession {
  /** Unix milliseconds. */
  startMs: number | null;
  /** The session's `timestamp`: its end. */
  endMs: number | null;
  sport: number | null;
  subSport: number | null;
  elapsedSeconds: number | null;
  timerSeconds: number | null;
  distanceMeters: number | null;
  ascentMeters: number | null;
  averageHeartRate: number | null;
  maxHeartRate: number | null;
}

export interface FitLap {
  startMs: number | null;
  endMs: number | null;
  elapsedSeconds: number | null;
  distanceMeters: number | null;
}

export interface FitFile {
  /** `file_id.type` (4 is an activity), null when the file has no `file_id` or does not say. */
  fileType: number | null;
  /** `file_id.time_created`, Unix milliseconds. */
  timeCreatedMs: number | null;
  /** The `sport` messages: the sport and sub-sport the device says the activity is. */
  sports: { sport: number | null; subSport: number | null }[];
  sessions: FitSession[];
  laps: FitLap[];
  records: FitRecord[];
  /** The `activity` message's `num_sessions`, null when there is none. */
  declaredSessions: number | null;
}

/** A file that cannot be read as FIT: its framing, its CRC or its structure is wrong. */
export class FitDecodeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "FitDecodeError";
  }
}

// ---- Base types ---------------------------------------------------------------------------------------------------

interface BaseType {
  size: number;
  /** Reads one value; null when it is the type's invalid value. */
  read(view: DataView, offset: number, little: boolean): number | null;
}

const numberOrNull = (value: number, invalid: number): number | null =>
  value === invalid ? null : value;

const BASE_TYPES: Record<number, BaseType> = {
  0: { size: 1, read: (v, o) => numberOrNull(v.getUint8(o), 0xff) }, // enum
  1: { size: 1, read: (v, o) => numberOrNull(v.getInt8(o), 0x7f) }, // sint8
  2: { size: 1, read: (v, o) => numberOrNull(v.getUint8(o), 0xff) }, // uint8
  3: { size: 2, read: (v, o, l) => numberOrNull(v.getInt16(o, l), 0x7fff) }, // sint16
  4: { size: 2, read: (v, o, l) => numberOrNull(v.getUint16(o, l), 0xffff) }, // uint16
  5: { size: 4, read: (v, o, l) => numberOrNull(v.getInt32(o, l), 0x7fff_ffff) }, // sint32
  6: { size: 4, read: (v, o, l) => numberOrNull(v.getUint32(o, l), 0xffff_ffff) }, // uint32
  7: { size: 1, read: () => null }, // string
  8: { size: 4, read: (v, o, l) => (Number.isNaN(v.getFloat32(o, l)) ? null : v.getFloat32(o, l)) },
  9: { size: 8, read: (v, o, l) => (Number.isNaN(v.getFloat64(o, l)) ? null : v.getFloat64(o, l)) },
  10: { size: 1, read: (v, o) => numberOrNull(v.getUint8(o), 0) }, // uint8z
  11: { size: 2, read: (v, o, l) => numberOrNull(v.getUint16(o, l), 0) }, // uint16z
  12: { size: 4, read: (v, o, l) => numberOrNull(v.getUint32(o, l), 0) }, // uint32z
  13: { size: 1, read: (v, o) => numberOrNull(v.getUint8(o), 0xff) }, // byte
  14: { size: 8, read: () => null }, // sint64: not needed
  15: { size: 8, read: () => null }, // uint64: not needed
  16: { size: 8, read: () => null }, // uint64z: not needed
};

// ---- What is read of which message ---------------------------------------------------------------------------------

type Key =
  | "timestamp"
  | "fileType"
  | "timeCreated"
  | "sport"
  | "subSport"
  | "startTime"
  | "elapsed"
  | "timer"
  | "distance"
  | "ascent"
  | "avgHr"
  | "maxHr"
  | "numSessions"
  | "latitude"
  | "longitude"
  | "altitude"
  | "enhancedAltitude"
  | "heartRate"
  | "cadence"
  | "power"
  | "speed"
  | "enhancedSpeed";

const MESG_FILE_ID = 0;
const MESG_SPORT = 12;
const MESG_SESSION = 18;
const MESG_LAP = 19;
const MESG_RECORD = 20;
const MESG_ACTIVITY = 34;

/** `message number -> field number -> what it is`. */
const FIELDS: Record<number, Record<number, Key>> = {
  [MESG_FILE_ID]: { 0: "fileType", 4: "timeCreated" },
  [MESG_SPORT]: { 0: "sport", 1: "subSport" },
  [MESG_SESSION]: {
    253: "timestamp",
    2: "startTime",
    5: "sport",
    6: "subSport",
    7: "elapsed",
    8: "timer",
    9: "distance",
    22: "ascent",
    16: "avgHr",
    17: "maxHr",
  },
  [MESG_LAP]: { 253: "timestamp", 2: "startTime", 7: "elapsed", 9: "distance" },
  [MESG_RECORD]: {
    253: "timestamp",
    0: "latitude",
    1: "longitude",
    2: "altitude",
    78: "enhancedAltitude",
    3: "heartRate",
    4: "cadence",
    5: "distance",
    6: "speed",
    73: "enhancedSpeed",
    7: "power",
  },
  [MESG_ACTIVITY]: { 253: "timestamp", 1: "numSessions" },
};

interface FieldDef {
  num: number;
  size: number;
  baseType: BaseType | null;
  key: Key | null;
}

interface Definition {
  little: boolean;
  global: number;
  fields: FieldDef[];
  /** Bytes of the developer fields, which are skipped. */
  developerBytes: number;
  /** Bytes of the whole data message after its header byte. */
  size: number;
}

const toMs = (fitSeconds: number | null): number | null =>
  fitSeconds === null || fitSeconds < MIN_DATE_TIME ? null : FIT_EPOCH_MS + fitSeconds * 1000;

/** Decodes the messages of an activity out of a FIT file. Throws {@link FitDecodeError} when the file is not readable. */
export function decodeFit(bytes: Uint8Array, options: { maxRecords?: number } = {}): FitFile {
  const maxRecords = options.maxRecords ?? MAX_FIT_RECORDS;
  const check = checkFit(bytes);
  if (!check.ok) throw new FitDecodeError(check.reason);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const end = check.headerSize + check.dataSize;

  const file: FitFile = {
    fileType: null,
    timeCreatedMs: null,
    sports: [],
    sessions: [],
    laps: [],
    records: [],
    declaredSessions: null,
  };
  const definitions: (Definition | undefined)[] = new Array<Definition | undefined>(16);
  let lastTimestamp = 0;
  let pos = check.headerSize;

  const need = (count: number, what: string): void => {
    if (pos + count > end) throw new FitDecodeError(`The file is cut off in ${what}`);
  };

  while (pos < end) {
    const header = view.getUint8(pos);
    pos += 1;

    if ((header & 0x80) === 0 && (header & 0x40) !== 0) {
      // A definition message.
      const hasDeveloperFields = (header & 0x20) !== 0;
      const local = header & 0x0f;
      need(5, "a definition");
      const little = view.getUint8(pos + 1) === 0;
      const global = view.getUint16(pos + 2, little);
      const count = view.getUint8(pos + 4);
      pos += 5;
      need(count * 3, "a definition");
      if (count === 0) throw new FitDecodeError("A definition has no fields");
      const known = FIELDS[global];
      const fields: FieldDef[] = [];
      let size = 0;
      for (let i = 0; i < count; i++) {
        const num = view.getUint8(pos);
        const fieldSize = view.getUint8(pos + 1);
        const type = BASE_TYPES[view.getUint8(pos + 2) & 0x1f];
        pos += 3;
        if (fieldSize === 0) throw new FitDecodeError("A definition has a field of size 0");
        if (type !== undefined && fieldSize % type.size !== 0) {
          throw new FitDecodeError("A field's size is not a multiple of its type's size");
        }
        size += fieldSize;
        // Only a single value is read: an array of a field we know is left alone.
        const key = known?.[num] ?? null;
        const scalar = type !== undefined && fieldSize === type.size;
        fields.push({
          num,
          size: fieldSize,
          baseType: scalar ? type : null,
          key: scalar ? key : null,
        });
      }
      let developerBytes = 0;
      if (hasDeveloperFields) {
        need(1, "a definition");
        const developers = view.getUint8(pos);
        pos += 1;
        need(developers * 3, "a definition");
        for (let i = 0; i < developers; i++) developerBytes += view.getUint8(pos + 1 + i * 3);
        pos += developers * 3;
      }
      definitions[local] = { little, global, fields, developerBytes, size: size + developerBytes };
      continue;
    }

    // A data message, with or without a compressed timestamp.
    const compressed = (header & 0x80) !== 0;
    const local = compressed ? (header >> 5) & 0x03 : header & 0x0f;
    const definition = definitions[local];
    if (definition === undefined) throw new FitDecodeError("A data message has no definition");
    need(definition.size, "a data message");

    const values = new Map<Key, number>();
    let offset = pos;
    for (const field of definition.fields) {
      if (field.key !== null && field.baseType !== null) {
        const value = field.baseType.read(view, offset, definition.little);
        if (value !== null) values.set(field.key, value);
      }
      offset += field.size;
    }
    pos += definition.size;

    // The reference for compressed timestamps is the last full timestamp of any message.
    let timestamp = values.get("timestamp") ?? null;
    if (timestamp !== null && timestamp >= MIN_DATE_TIME) lastTimestamp = timestamp;
    if (compressed && lastTimestamp > 0) {
      const delta = header & 0x1f;
      const base = lastTimestamp - (lastTimestamp & 0x1f);
      timestamp = base + delta + (delta < (lastTimestamp & 0x1f) ? 0x20 : 0);
      lastTimestamp = timestamp;
    }

    switch (definition.global) {
      case MESG_FILE_ID:
        file.fileType = values.get("fileType") ?? file.fileType;
        file.timeCreatedMs = toMs(values.get("timeCreated") ?? null) ?? file.timeCreatedMs;
        break;
      case MESG_SPORT:
        file.sports.push({
          sport: values.get("sport") ?? null,
          subSport: values.get("subSport") ?? null,
        });
        break;
      case MESG_SESSION:
        file.sessions.push({
          startMs: toMs(values.get("startTime") ?? null),
          endMs: toMs(timestamp),
          sport: values.get("sport") ?? null,
          subSport: values.get("subSport") ?? null,
          elapsedSeconds: scaled(values.get("elapsed"), 1000),
          timerSeconds: scaled(values.get("timer"), 1000),
          distanceMeters: scaled(values.get("distance"), 100),
          ascentMeters: values.get("ascent") ?? null,
          averageHeartRate: values.get("avgHr") ?? null,
          maxHeartRate: values.get("maxHr") ?? null,
        });
        break;
      case MESG_LAP:
        file.laps.push({
          startMs: toMs(values.get("startTime") ?? null),
          endMs: toMs(timestamp),
          elapsedSeconds: scaled(values.get("elapsed"), 1000),
          distanceMeters: scaled(values.get("distance"), 100),
        });
        break;
      case MESG_RECORD: {
        const timeMs = toMs(timestamp);
        if (timeMs === null) break;
        if (file.records.length >= maxRecords) {
          throw new FitDecodeError(`The file has more than ${maxRecords} records`);
        }
        file.records.push(recordOf(timeMs, values));
        break;
      }
      case MESG_ACTIVITY:
        file.declaredSessions = values.get("numSessions") ?? file.declaredSessions;
        break;
    }
  }
  return file;
}

function scaled(value: number | undefined, divisor: number): number | null {
  return value === undefined ? null : value / divisor;
}

function recordOf(timeMs: number, values: Map<Key, number>): FitRecord {
  const record: FitRecord = { timeMs };
  const heartRate = values.get("heartRate");
  if (heartRate !== undefined) record.heartRate = heartRate;
  const cadence = values.get("cadence");
  if (cadence !== undefined) record.cadence = cadence;
  const power = values.get("power");
  if (power !== undefined) record.power = power;
  // The enhanced fields are the profile's 32-bit versions of speed and altitude: they win when both are there.
  const enhancedSpeed = values.get("enhancedSpeed");
  const speed = values.get("speed");
  if (enhancedSpeed !== undefined) record.speed = enhancedSpeed / 1000;
  else if (speed !== undefined) record.speed = speed / 1000;
  const distance = values.get("distance");
  if (distance !== undefined) record.distance = distance / 100;
  const enhancedAltitude = values.get("enhancedAltitude");
  const altitude = values.get("altitude");
  if (enhancedAltitude !== undefined) record.altitude = enhancedAltitude / 5 - 500;
  else if (altitude !== undefined) record.altitude = altitude / 5 - 500;
  const latitude = values.get("latitude");
  const longitude = values.get("longitude");
  if (latitude !== undefined && longitude !== undefined) {
    record.latitude = latitude * SEMICIRCLES_TO_DEGREES;
    record.longitude = longitude * SEMICIRCLES_TO_DEGREES;
  }
  return record;
}

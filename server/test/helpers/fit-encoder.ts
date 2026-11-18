import { fitCrc16 } from "../../src/fit.ts";

/**
 * A FIT encoder for the tests (ADR 0026): it writes the bytes of a FIT file from a description, with the framing and both CRCs
 * right, so the service's decoder is tested against files written by something other than itself. It knows the base types, both
 * byte orders, developer fields and compressed-timestamp headers, and `syntheticRide` builds a whole activity (`file_id`, `record`s,
 * `lap`, `session`, `activity`) from functions of the second. Nothing in a file is real: no recording, no position of anyone.
 */

export type TypeName =
  | "enum"
  | "sint8"
  | "uint8"
  | "sint16"
  | "uint16"
  | "sint32"
  | "uint32"
  | "uint8z"
  | "uint16z"
  | "uint32z"
  | "byte";

const TYPES: Record<TypeName, { number: number; size: number; invalid: number }> = {
  enum: { number: 0, size: 1, invalid: 0xff },
  sint8: { number: 1, size: 1, invalid: 0x7f },
  uint8: { number: 2, size: 1, invalid: 0xff },
  sint16: { number: 3, size: 2, invalid: 0x7fff },
  uint16: { number: 4, size: 2, invalid: 0xffff },
  sint32: { number: 5, size: 4, invalid: 0x7fff_ffff },
  uint32: { number: 6, size: 4, invalid: 0xffff_ffff },
  uint8z: { number: 10, size: 1, invalid: 0 },
  uint16z: { number: 11, size: 2, invalid: 0 },
  uint32z: { number: 12, size: 4, invalid: 0 },
  byte: { number: 13, size: 1, invalid: 0xff },
};

export interface FieldSpec {
  num: number;
  type: TypeName;
  /** An array of this many values (the field's size is the type's times this). Default 1. */
  count?: number;
}

export interface DeveloperFieldSpec {
  num: number;
  size: number;
  developerIndex?: number;
}

export interface DefineOptions {
  bigEndian?: boolean;
  developerFields?: DeveloperFieldSpec[];
}

/** The seconds of the FIT epoch (1989-12-31T00:00:00Z) at a Unix time. */
export const fitSeconds = (unixMs: number): number => Math.floor(unixMs / 1000) - 631_065_600;

interface Definition {
  fields: FieldSpec[];
  bigEndian: boolean;
  developerFields: DeveloperFieldSpec[];
}

export class FitBuilder {
  private readonly chunks: Buffer[] = [];
  private readonly definitions = new Map<number, Definition>();

  /** A definition message for local type `local` and global message number `global`. */
  define(local: number, global: number, fields: FieldSpec[], options: DefineOptions = {}): this {
    const developerFields = options.developerFields ?? [];
    const bigEndian = options.bigEndian ?? false;
    this.definitions.set(local, { fields, bigEndian, developerFields });
    const body = Buffer.alloc(
      6 + fields.length * 3 + (developerFields.length > 0 ? 1 + developerFields.length * 3 : 0),
    );
    body.writeUInt8(0x40 | (developerFields.length > 0 ? 0x20 : 0) | local, 0);
    body.writeUInt8(0, 1); // reserved
    body.writeUInt8(bigEndian ? 1 : 0, 2);
    if (bigEndian) body.writeUInt16BE(global, 3);
    else body.writeUInt16LE(global, 3);
    body.writeUInt8(fields.length, 5);
    let at = 6;
    for (const field of fields) {
      const type = TYPES[field.type];
      body.writeUInt8(field.num, at);
      body.writeUInt8(type.size * (field.count ?? 1), at + 1);
      body.writeUInt8(type.size > 1 ? 0x80 | type.number : type.number, at + 2);
      at += 3;
    }
    if (developerFields.length > 0) {
      body.writeUInt8(developerFields.length, at);
      at += 1;
      for (const field of developerFields) {
        body.writeUInt8(field.num, at);
        body.writeUInt8(field.size, at + 1);
        body.writeUInt8(field.developerIndex ?? 0, at + 2);
        at += 3;
      }
    }
    this.chunks.push(body);
    return this;
  }

  /**
   * A data message of local type `local`: one value per field in the order of the definition (null is the type's invalid value;
   * an array field takes an array), then the bytes of the developer fields if there are any (zeros by default).
   */
  data(
    local: number,
    values: (number | null | readonly number[])[],
    options: { compressedOffset?: number; developerBytes?: Uint8Array } = {},
  ): this {
    const definition = this.definitions.get(local);
    if (definition === undefined) throw new Error(`no definition for local type ${local}`);
    const parts: Buffer[] = [];
    const header =
      options.compressedOffset === undefined
        ? local
        : 0x80 | (local << 5) | (options.compressedOffset & 0x1f);
    parts.push(Buffer.from([header]));
    for (const [index, field] of definition.fields.entries()) {
      const type = TYPES[field.type];
      const given = values[index];
      const count = field.count ?? 1;
      const list = Array.isArray(given)
        ? given
        : Array.from({ length: count }, () => given ?? null);
      for (const value of list as (number | null)[]) {
        const buffer = Buffer.alloc(type.size);
        const raw = value === null || value === undefined ? type.invalid : value;
        writeInteger(buffer, type.number, raw, definition.bigEndian);
        parts.push(buffer);
      }
    }
    const developerSize = definition.developerFields.reduce((a, f) => a + f.size, 0);
    if (developerSize > 0)
      parts.push(Buffer.from(options.developerBytes ?? new Uint8Array(developerSize)));
    this.chunks.push(Buffer.concat(parts));
    return this;
  }

  /** The bytes of the records so far, without header or CRC. */
  records(): Buffer {
    return Buffer.concat(this.chunks);
  }

  /** The file: header (14 bytes by default, 12 with `headerSize: 12`), the records, the CRC-16. */
  build(options: { headerSize?: 12 | 14 } = {}): Buffer {
    return frameFit(this.records(), options.headerSize ?? 14);
  }
}

function writeInteger(buffer: Buffer, typeNumber: number, value: number, bigEndian: boolean): void {
  switch (typeNumber) {
    case 0:
    case 2:
    case 10:
    case 13:
      buffer.writeUInt8(value, 0);
      return;
    case 1:
      buffer.writeInt8(value, 0);
      return;
    case 3:
      if (bigEndian) buffer.writeInt16BE(value, 0);
      else buffer.writeInt16LE(value, 0);
      return;
    case 4:
    case 11:
      if (bigEndian) buffer.writeUInt16BE(value, 0);
      else buffer.writeUInt16LE(value, 0);
      return;
    case 5:
      if (bigEndian) buffer.writeInt32BE(value, 0);
      else buffer.writeInt32LE(value, 0);
      return;
    case 6:
    case 12:
      if (bigEndian) buffer.writeUInt32BE(value, 0);
      else buffer.writeUInt32LE(value, 0);
      return;
    default:
      throw new Error(`unsupported base type ${typeNumber}`);
  }
}

/** Wraps records in a FIT header and CRC. */
export function frameFit(data: Uint8Array, headerSize: 12 | 14 = 14): Buffer {
  const bytes = Buffer.alloc(headerSize + data.length + 2);
  bytes.writeUInt8(headerSize, 0);
  bytes.writeUInt8(0x20, 1); // protocol 2.0
  bytes.writeUInt16LE(21222, 2); // profile version
  bytes.writeUInt32LE(data.length, 4);
  bytes.write(".FIT", 8, "ascii");
  if (headerSize === 14) bytes.writeUInt16LE(fitCrc16(bytes, 0, 12), 12);
  bytes.set(data, headerSize);
  bytes.writeUInt16LE(fitCrc16(bytes, 0, bytes.length - 2), bytes.length - 2);
  return bytes;
}

// ---- A whole synthetic ride -----------------------------------------------------------------------------------

export interface SyntheticFitRide {
  /** Unix milliseconds of the first record. */
  startMs: number;
  /** One record per second for this many seconds. */
  seconds: number;
  /** FIT `sport` of the session (2 is cycling, 1 running). Default 2. */
  sport?: number;
  subSport?: number;
  /** FIT `file_id.type` (4 is an activity). Default 4. */
  fileType?: number;
  heartRate?: (second: number) => number | null;
  power?: (second: number) => number | null;
  cadence?: (second: number) => number | null;
  /** Metres per second. Default 7.5. */
  speed?: (second: number) => number;
  /** Metres. Default: flat at 100 m. */
  altitude?: (second: number) => number | null;
  /** Seconds (1-based) at which no record is written: the timer was stopped. */
  skip?: (second: number) => boolean;
  /** Leave out the position fields. Default false. */
  noPosition?: boolean;
  /** Leave out the session message. */
  noSession?: boolean;
  /** Leave out the records. */
  noRecords?: boolean;
  /** Write `session.total_ascent` (m). */
  totalAscent?: number;
  /** Write the 32-bit enhanced speed and altitude as well as the 16-bit ones. */
  enhanced?: boolean;
  /** Big-endian definitions. */
  bigEndian?: boolean;
  /** Add a developer field to the record definition. */
  developerField?: boolean;
  /** Compressed-timestamp headers for the records after the first (the timestamp field is left out). */
  compressedTimestamps?: boolean;
  /** Additional sessions after this one: their `seconds`, sport and a gap in seconds before each. */
  more?: { seconds: number; sport?: number; gapSeconds?: number }[];
  headerSize?: 12 | 14;
}

/** A synthetic outdoor ride as FIT bytes. */
export function syntheticRide(spec: SyntheticFitRide): Buffer {
  const builder = new FitBuilder();
  const little = spec.bigEndian !== true;
  const bigEndian = !little;
  const developerFields = spec.developerField === true ? [{ num: 0, size: 4 }] : [];
  const start = fitSeconds(spec.startMs);

  builder
    .define(
      0,
      0,
      [
        { num: 0, type: "enum" },
        { num: 1, type: "uint16" },
        { num: 2, type: "uint16" },
        { num: 3, type: "uint32z" },
        { num: 4, type: "uint32" },
      ],
      { bigEndian },
    )
    .data(0, [spec.fileType ?? 4, 255, 1, 4242, start]);

  const recordFields: FieldSpec[] = [
    ...(spec.compressedTimestamps === true ? [] : [{ num: 253, type: "uint32" } as FieldSpec]),
    { num: 3, type: "uint8" },
    { num: 4, type: "uint8" },
    { num: 5, type: "uint32" },
    { num: 6, type: "uint16" },
    { num: 7, type: "uint16" },
    { num: 2, type: "uint16" },
    ...(spec.noPosition === true
      ? []
      : ([
          { num: 0, type: "sint32" },
          { num: 1, type: "sint32" },
        ] as FieldSpec[])),
    ...(spec.enhanced === true
      ? ([
          { num: 73, type: "uint32" },
          { num: 78, type: "uint32" },
        ] as FieldSpec[])
      : []),
  ];

  let distance = 0;
  const writeSession = (index: number, from: number, seconds: number, sport: number): void => {
    // Records of this stretch.
    if (spec.noRecords !== true) {
      builder.define(1, 20, recordFields, { bigEndian, developerFields });
      let first = true;
      for (let second = 1; second <= seconds; second++) {
        const global = from + second;
        if (spec.skip?.(global) === true) continue;
        const speed = spec.speed?.(global) ?? 7.5;
        distance += speed;
        const altitude = spec.altitude === undefined ? 100 : spec.altitude(global);
        const values: (number | null)[] = [];
        const compressed = spec.compressedTimestamps === true && !first;
        if (spec.compressedTimestamps !== true) values.push(start + global);
        values.push(
          spec.heartRate?.(global) ?? null,
          spec.cadence?.(global) ?? null,
          Math.round(distance * 100),
          Math.round(speed * 1000),
          spec.power?.(global) ?? null,
          altitude === null ? null : Math.round((altitude + 500) * 5),
        );
        if (spec.noPosition !== true) {
          // Near the equator and the prime meridian, in semicircles: nobody's position.
          values.push(
            Math.round(((0.001 * global) / 180) * 2 ** 31),
            Math.round(((0.0005 * global) / 180) * 2 ** 31),
          );
        }
        if (spec.enhanced === true) {
          values.push(
            Math.round(speed * 1000),
            altitude === null ? null : Math.round((altitude + 500) * 5),
          );
        }
        if (spec.compressedTimestamps === true && first) {
          // The first record carries a full timestamp through its own definition.
          builder.define(2, 20, [{ num: 253, type: "uint32" }, ...recordFields], {
            bigEndian,
            developerFields,
          });
          builder.data(2, [start + global, ...values]);
          first = false;
          continue;
        }
        builder.data(
          1,
          values,
          compressed
            ? { compressedOffset: (start + global) & 0x1f, developerBytes: new Uint8Array(4) }
            : { developerBytes: new Uint8Array(4) },
        );
      }
    }
    if (spec.noSession === true) return;
    const startFit = start + from + 1;
    const endFit = start + from + seconds;
    builder.define(
      3,
      19,
      [
        { num: 253, type: "uint32" },
        { num: 2, type: "uint32" },
        { num: 7, type: "uint32" },
        { num: 9, type: "uint32" },
        { num: 254, type: "uint16" },
      ],
      { bigEndian },
    );
    builder.data(3, [endFit, startFit, seconds * 1000, Math.round(distance * 100), index]);
    builder.define(
      4,
      18,
      [
        { num: 253, type: "uint32" },
        { num: 2, type: "uint32" },
        { num: 5, type: "enum" },
        { num: 6, type: "enum" },
        { num: 7, type: "uint32" },
        { num: 8, type: "uint32" },
        { num: 9, type: "uint32" },
        { num: 22, type: "uint16" },
      ],
      { bigEndian },
    );
    builder.data(4, [
      endFit,
      startFit,
      sport,
      spec.subSport ?? 0,
      seconds * 1000,
      seconds * 1000,
      Math.round(distance * 100),
      spec.totalAscent ?? null,
    ]);
  };

  writeSession(0, 0, spec.seconds, spec.sport ?? 2);
  let offset = spec.seconds;
  for (const [i, extra] of (spec.more ?? []).entries()) {
    offset += extra.gapSeconds ?? 0;
    writeSession(i + 1, offset, extra.seconds, extra.sport ?? 2);
    offset += extra.seconds;
  }

  builder
    .define(
      5,
      34,
      [
        { num: 253, type: "uint32" },
        { num: 1, type: "uint16" },
        { num: 2, type: "enum" },
      ],
      { bigEndian },
    )
    .data(5, [start + offset, 1 + (spec.more?.length ?? 0), 0]);
  return builder.build({ headerSize: spec.headerSize ?? 14 });
}

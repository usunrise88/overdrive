import { describe, expect, it } from "vitest";
import { checkFit, fitCrc16 } from "../src/fit.ts";
import { decodeFit, FitDecodeError } from "../src/fit-decode.ts";
import { fitFixture } from "./helpers/app.ts";
import { FitBuilder, fitSeconds, frameFit, syntheticRide } from "./helpers/fit-encoder.ts";

const START = Date.UTC(2027, 2, 14, 9, 0, 0);

function refusal(bytes: Uint8Array): string {
  try {
    decodeFit(bytes);
  } catch (error) {
    if (error instanceof FitDecodeError) return error.message;
    throw error;
  }
  throw new Error("expected the file to be refused");
}

describe("decoding our own FIT files", () => {
  it("reads the golden file the tablet's writer makes: messages, fields and scales", () => {
    const file = decodeFit(fitFixture("fit-golden-3s.fit"));
    expect(file.fileType).toBe(4);
    const t0 = Date.UTC(2026, 10, 18, 10, 0, 0);
    expect(file.timeCreatedMs).toBe(t0);
    expect(file.records.map((r) => r.timeMs)).toEqual([t0, t0 + 1000, t0 + 2000]);
    expect(file.records.map((r) => r.heartRate)).toEqual([120, 121, 122]);
    expect(file.records.map((r) => r.cadence)).toEqual([90, 91, 92]);
    expect(file.records.map((r) => r.power)).toEqual([200, 210, 220]);
    expect(file.records.map((r) => r.distance)).toEqual([0, 8, 16.1]);
    // The 16-bit speed and the enhanced one say the same; the enhanced one is read.
    expect(file.records.map((r) => r.speed)).toEqual([8, 8.1, 8.2]);
    expect(file.records[0]?.latitude).toBeUndefined();
    expect(file.sessions).toHaveLength(1);
    expect(file.sessions[0]).toMatchObject({
      startMs: t0,
      endMs: t0 + 2000,
      sport: 2,
      subSport: 58,
      elapsedSeconds: 3,
      timerSeconds: 3,
      distanceMeters: 16.1,
      averageHeartRate: 121,
      maxHeartRate: 122,
    });
    expect(file.laps).toHaveLength(1);
    expect(file.laps[0]).toMatchObject({ startMs: t0, elapsedSeconds: 3, distanceMeters: 16.1 });
    expect(file.declaredSessions).toBe(1);
  });

  it("refuses the minimal fixture: its four bytes are a data message that no definition announced", () => {
    expect(refusal(fitFixture("minimal.fit"))).toMatch(/no definition/);
  });
});

describe("decoding files written by the test encoder", () => {
  it("reads every field of a record: heart rate, cadence, power, speed, distance, altitude and position", () => {
    const bytes = syntheticRide({
      startMs: START,
      seconds: 5,
      heartRate: (s) => 100 + s,
      power: (s) => 150 + s,
      cadence: (s) => 80 + s,
      speed: () => 6,
      altitude: (s) => 200 + s,
    });
    const file = decodeFit(bytes);
    expect(file.records).toHaveLength(5);
    const second = file.records[1];
    expect(second).toMatchObject({
      timeMs: START + 2000,
      heartRate: 102,
      cadence: 82,
      power: 152,
      speed: 6,
      distance: 12,
    });
    expect(second?.altitude).toBeCloseTo(202, 1);
    expect(second?.latitude).toBeCloseTo(0.002, 5);
    expect(second?.longitude).toBeCloseTo(0.001, 5);
    expect(file.sessions[0]).toMatchObject({ sport: 2, startMs: START + 1000, elapsedSeconds: 5 });
  });

  it("treats a field's invalid value as absent", () => {
    const file = decodeFit(
      syntheticRide({
        startMs: START,
        seconds: 4,
        heartRate: (s) => (s === 2 ? null : 120),
        power: () => null,
      }),
    );
    expect(file.records.map((r) => r.heartRate)).toEqual([120, undefined, 120, 120]);
    expect(file.records.every((r) => r.power === undefined)).toBe(true);
    expect(file.records.every((r) => r.cadence === undefined)).toBe(true);
  });

  it("reads the 32-bit enhanced speed and altitude, and prefers them", () => {
    const file = decodeFit(
      syntheticRide({
        startMs: START,
        seconds: 3,
        enhanced: true,
        speed: () => 12.5,
        altitude: () => 1234.4,
      }),
    );
    expect(file.records[0]?.speed).toBe(12.5);
    expect(file.records[0]?.altitude).toBeCloseTo(1234.4, 1);
  });

  it("reads a big-endian file and a 12-byte header", () => {
    const file = decodeFit(
      syntheticRide({
        startMs: START,
        seconds: 4,
        bigEndian: true,
        headerSize: 12,
        heartRate: () => 133,
      }),
    );
    expect(file.records).toHaveLength(4);
    expect(file.records[3]).toMatchObject({ heartRate: 133, timeMs: START + 4000, distance: 30 });
    expect(file.sessions[0]?.sport).toBe(2);
  });

  it("skips developer fields by their size", () => {
    const file = decodeFit(
      syntheticRide({ startMs: START, seconds: 6, developerField: true, heartRate: () => 140 }),
    );
    expect(file.records).toHaveLength(6);
    expect(file.records.every((r) => r.heartRate === 140)).toBe(true);
    expect(file.sessions).toHaveLength(1);
  });

  it("reads compressed-timestamp headers: the time of each record follows from the one before", () => {
    // 100 records cross several 32-second rollovers of the five-bit offset.
    const file = decodeFit(
      syntheticRide({
        startMs: START + 7000,
        seconds: 100,
        compressedTimestamps: true,
        heartRate: (s) => 90 + (s % 50),
      }),
    );
    expect(file.records).toHaveLength(100);
    for (const [i, record] of file.records.entries()) {
      expect(record.timeMs, `record ${i}`).toBe(START + 7000 + (i + 1) * 1000);
      expect(record.heartRate).toBe(90 + ((i + 1) % 50));
    }
  });

  it("keeps a gap: no record is invented for the seconds the timer was stopped", () => {
    const file = decodeFit(
      syntheticRide({
        startMs: START,
        seconds: 20,
        skip: (s) => s > 8 && s <= 14,
        heartRate: () => 120,
      }),
    );
    expect(file.records).toHaveLength(14);
    expect(file.records[7]?.timeMs).toBe(START + 8000);
    expect(file.records[8]?.timeMs).toBe(START + 15_000);
  });

  it("ignores messages it does not know, by size", () => {
    const builder = new FitBuilder()
      .define(0, 0, [{ num: 0, type: "enum" }])
      .data(0, [4])
      // device_info (23): not read.
      .define(1, 23, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint32z" },
        { num: 2, type: "uint16" },
      ])
      .data(1, [fitSeconds(START), 77, 255])
      .define(2, 20, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint8" },
        { num: 200, type: "uint16", count: 3 },
      ])
      .data(2, [fitSeconds(START) + 1, 111, [1, 2, 3]]);
    const file = decodeFit(builder.build());
    expect(file.records).toEqual([{ timeMs: START + 1000, heartRate: 111 }]);
  });

  it("leaves records without a real time out, and reads none of an array field it knows", () => {
    const builder = new FitBuilder()
      .define(0, 20, [
        { num: 253, type: "uint32" },
        { num: 3, type: "uint8" },
      ])
      // A clock that was never set: seconds since 1989 that are not a date.
      .data(0, [1000, 120])
      .data(0, [fitSeconds(START), 121]);
    const file = decodeFit(builder.build());
    expect(file.records).toEqual([{ timeMs: START, heartRate: 121 }]);
  });
});

describe("files that are not FIT, or are broken", () => {
  const good = syntheticRide({ startMs: START, seconds: 30, heartRate: () => 130 });

  it("refuses a truncated file, a file with a wrong CRC and a wrong header", () => {
    expect(refusal(good.subarray(0, good.length - 10))).toMatch(/bytes/);
    const flipped = Buffer.from(good);
    flipped[40] = (flipped[40] ?? 0) ^ 0xff;
    expect(refusal(flipped)).toMatch(/CRC/);
    expect(refusal(Buffer.from("not a FIT file at all, plain text"))).toMatch(/Header|FIT/);
    expect(refusal(Buffer.alloc(0))).toMatch(/Too short/);
    const noSignature = Buffer.from(good);
    noSignature.write("FIX!", 8, "ascii");
    expect(refusal(noSignature)).toMatch(/\.FIT/);
  });

  it("refuses a file with good framing and nonsense inside", () => {
    // A data message of local type 3 without a definition.
    expect(refusal(frameFit(Uint8Array.from([0x03, 1, 2, 3])))).toMatch(/no definition/);
    // A definition without fields.
    expect(refusal(frameFit(Uint8Array.from([0x40, 0, 0, 0, 0, 0])))).toMatch(/no fields/);
    // A definition cut off after its header.
    expect(refusal(frameFit(Uint8Array.from([0x40, 0, 0, 20, 0, 3, 253, 4])))).toMatch(/cut off/);
    // A field whose size is not a multiple of its type's.
    expect(refusal(frameFit(Uint8Array.from([0x40, 0, 0, 20, 0, 1, 253, 3, 0x86])))).toMatch(
      /multiple/,
    );
    // A field of size 0.
    expect(refusal(frameFit(Uint8Array.from([0x40, 0, 0, 20, 0, 1, 253, 0, 0x86])))).toMatch(
      /size 0/,
    );
  });

  it("refuses a data message cut off by the end of the records", () => {
    const builder = new FitBuilder().define(0, 20, [
      { num: 253, type: "uint32" },
      { num: 3, type: "uint8" },
    ]);
    const records = Buffer.concat([builder.records(), Buffer.from([0x00, 1, 2])]);
    expect(refusal(frameFit(records))).toMatch(/cut off/);
  });

  it("refuses a file with more records than it will hold", () => {
    expect(() => decodeFit(good, { maxRecords: 10 })).toThrow(/more than 10 records/);
    expect(decodeFit(good, { maxRecords: 30 }).records).toHaveLength(30);
  });

  it("never quotes what the file holds in a refusal", () => {
    const secret = syntheticRide({ startMs: START, seconds: 10, heartRate: () => 187 });
    const flipped = Buffer.from(secret);
    flipped[flipped.length - 1] = (flipped[flipped.length - 1] ?? 0) ^ 0xff;
    expect(refusal(flipped)).not.toContain("187");
  });

  it("agrees with the framing check on every file it reads", () => {
    for (const bytes of [good, fitFixture("fit-golden-3s.fit")]) {
      expect(checkFit(bytes).ok).toBe(true);
      expect(fitCrc16(bytes, 0, bytes.length - 2)).toBe(bytes.readUInt16LE(bytes.length - 2));
      expect(() => decodeFit(bytes)).not.toThrow();
    }
  });
});

describe("files with more than one session", () => {
  it("reads every session and every record, in the order of the file", () => {
    const file = decodeFit(
      syntheticRide({
        startMs: START,
        seconds: 60,
        more: [
          { seconds: 40, sport: 1, gapSeconds: 120 },
          { seconds: 30, sport: 2, gapSeconds: 60 },
        ],
        heartRate: () => 125,
      }),
    );
    expect(file.sessions.map((s) => s.sport)).toEqual([2, 1, 2]);
    expect(file.sessions.map((s) => s.elapsedSeconds)).toEqual([60, 40, 30]);
    expect(file.laps).toHaveLength(3);
    expect(file.records).toHaveLength(130);
    expect(file.declaredSessions).toBe(3);
    const [first, second, third] = file.sessions;
    expect(second?.startMs).toBe((first?.endMs ?? 0) + 120_000 + 1000);
    expect(third?.startMs).toBeGreaterThan(second?.endMs ?? 0);
  });
});

import { describe, expect, it } from "vitest";
import { checkFit, fitCrc16 } from "../src/fit.ts";
import { fitFixture } from "./helpers/app.ts";
import { buildFit } from "./helpers/fit.ts";

function reason(bytes: Uint8Array): string {
  const check = checkFit(bytes);
  if (check.ok) throw new Error("expected the file to be refused");
  return check.reason;
}

describe("fitCrc16", () => {
  it("is CRC-16/ARC: the standard check value, and the two CRCs of the golden file", () => {
    const text = Buffer.from("123456789", "ascii");
    expect(fitCrc16(text, 0, text.length)).toBe(0xbb3d);
    const golden = fitFixture("fit-golden-3s.fit");
    expect(fitCrc16(golden, 0, 12)).toBe(0xe18a);
    expect(fitCrc16(golden, 0, golden.length - 2)).toBe(0x349c);
  });
});

describe("checkFit", () => {
  it("accepts the file our writer makes (14-byte header)", () => {
    expect(checkFit(fitFixture("fit-golden-3s.fit"))).toEqual({
      ok: true,
      headerSize: 14,
      dataSize: 360,
    });
  });

  it("accepts the 12-byte header", () => {
    expect(checkFit(fitFixture("minimal.fit"))).toMatchObject({ ok: true, headerSize: 12 });
    expect(checkFit(buildFit({ headerSize: 12 }))).toMatchObject({ ok: true, headerSize: 12 });
  });

  it("accepts a header CRC of zero, which means it was not computed", () => {
    const bytes = buildFit();
    bytes.writeUInt16LE(0, 12);
    bytes.writeUInt16LE(fitCrc16(bytes, 0, bytes.length - 2), bytes.length - 2); // the file CRC covers the header
    expect(checkFit(bytes)).toMatchObject({ ok: true });
  });

  it("does not look inside the records", () => {
    const noise = Uint8Array.from({ length: 1000 }, (_, i) => (i * 37) & 0xff);
    expect(checkFit(buildFit({ data: noise }))).toMatchObject({ ok: true, dataSize: 1000 });
  });

  it.each([
    ["nothing", () => Buffer.alloc(0), /Too short/],
    ["a few bytes", () => Buffer.from("FIT"), /Too short/],
    [
      "a header size that is neither 12 nor 14",
      () => {
        const bytes = buildFit();
        bytes[0] = 13;
        return bytes;
      },
      /expected 12 or 14/,
    ],
    [
      "no .FIT signature",
      () => {
        const bytes = buildFit();
        bytes.write("FIT.", 8, "ascii");
        return bytes;
      },
      /\.FIT/,
    ],
    [
      "a data size that leaves the file short",
      () => buildFit().subarray(0, buildFit().length - 1),
      /header says/,
    ],
    [
      "a file with bytes after the CRC",
      () => Buffer.concat([buildFit(), Buffer.from([0])]),
      /header says/,
    ],
    ["a data size of zero", () => buildFit({ data: new Uint8Array(0) }), /no data records/],
    [
      "a damaged header CRC",
      () => {
        const bytes = buildFit();
        bytes.writeUInt16LE(bytes.readUInt16LE(12) ^ 1, 12);
        return bytes;
      },
      /header CRC/,
    ],
    [
      "a damaged record",
      () => {
        const bytes = buildFit();
        bytes[16] = (bytes[16] ?? 0) ^ 0xff;
        return bytes;
      },
      /file CRC/,
    ],
    [
      "a damaged file CRC",
      () => {
        const bytes = buildFit();
        bytes[bytes.length - 1] = (bytes[bytes.length - 1] ?? 0) ^ 1;
        return bytes;
      },
      /file CRC/,
    ],
    ["a JSON document", () => Buffer.from('{"schema":1}'.padEnd(40, " ")), /Header size/],
  ])("refuses %s", (_what, make, expected) => {
    expect(reason(make())).toMatch(expected);
  });
});

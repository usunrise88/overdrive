/**
 * What the service checks of an uploaded FIT file (ADR 0014): only its framing, never its
 * messages. The file is the tablet's own `FitWriter` output and is forwarded as it is.
 *
 * Layout (docs/protocols/fit.md section 1): a header of 12 or 14 bytes (size, protocol version,
 * profile version, data size as u32 little endian, ".FIT", and in the 14-byte form a header CRC),
 * then `dataSize` bytes of records, then a CRC-16 of everything before it.
 */

export type FitCheck =
  | { ok: true; headerSize: 12 | 14; dataSize: number }
  | { ok: false; reason: string };

const CRC_TABLE: readonly number[] = Array.from({ length: 256 }, (_, n) => {
  let crc = n;
  for (let bit = 0; bit < 8; bit++) crc = crc & 1 ? (crc >>> 1) ^ 0xa001 : crc >>> 1;
  return crc;
});

/** CRC-16/ARC (reflected polynomial 0xA001, initial value 0, no final XOR) of `bytes[from, to)`. */
export function fitCrc16(bytes: Uint8Array, from: number, to: number): number {
  let crc = 0;
  for (let i = from; i < to; i++) {
    crc = (crc >>> 8) ^ (CRC_TABLE[(crc ^ (bytes[i] ?? 0)) & 0xff] ?? 0);
  }
  return crc;
}

/** Checks the header, the sizes and both CRCs. A zero header CRC means "not computed" and passes. */
export function checkFit(bytes: Uint8Array): FitCheck {
  if (bytes.length < 12)
    return { ok: false, reason: `Too short for a FIT file (${bytes.length} bytes)` };
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const headerSize = view.getUint8(0);
  if (headerSize !== 12 && headerSize !== 14) {
    return { ok: false, reason: `Header size is ${headerSize}, expected 12 or 14` };
  }
  const signature = String.fromCharCode(...bytes.subarray(8, 12));
  if (signature !== ".FIT") return { ok: false, reason: 'The header does not say ".FIT"' };
  const dataSize = view.getUint32(4, true);
  if (dataSize === 0) return { ok: false, reason: "The file has no data records" };
  const expected = headerSize + dataSize + 2;
  if (bytes.length !== expected) {
    return {
      ok: false,
      reason: `The header says ${expected} bytes (header ${headerSize} + data ${dataSize} + CRC 2), the body has ${bytes.length}`,
    };
  }
  if (headerSize === 14) {
    const stored = view.getUint16(12, true);
    if (stored !== 0 && stored !== fitCrc16(bytes, 0, 12)) {
      return { ok: false, reason: "The header CRC does not match" };
    }
  }
  if (view.getUint16(bytes.length - 2, true) !== fitCrc16(bytes, 0, bytes.length - 2)) {
    return { ok: false, reason: "The file CRC does not match" };
  }
  return { ok: true, headerSize, dataSize };
}

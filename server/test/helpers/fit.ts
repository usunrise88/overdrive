import { fitCrc16 } from "../../src/fit.ts";

export interface FitOptions {
  /** 14 (with a header CRC, the default) or 12. */
  headerSize?: 12 | 14;
  /** The records section. Its content is not parsed by the service; default: 8 arbitrary bytes. */
  data?: Uint8Array;
}

/** A FIT file with the framing right (header, sizes, both CRCs) around any records. */
export function buildFit(options: FitOptions = {}): Buffer {
  const headerSize = options.headerSize ?? 14;
  const data = options.data ?? Uint8Array.from([0x40, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x02]);
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

/** A valid FIT file of exactly `size` bytes. */
export function buildFitOfSize(size: number): Buffer {
  return buildFit({ data: new Uint8Array(size - 14 - 2) });
}

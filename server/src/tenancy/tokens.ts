import { timingSafeEqual } from "node:crypto";
import type { TokenKind } from "./directory.ts";

export interface TokenEntry {
  userId: number;
  kind: TokenKind;
  name: string;
}

interface Stored extends TokenEntry {
  digest: Buffer;
}

const HEX_DIGEST = /^[0-9a-f]{64}$/;

/**
 * Bearer tokens by their SHA-256 digest. A lookup compares the presented digest with every stored one using
 * `timingSafeEqual` (equal length, no early exit on the first match), so the time taken does not tell a guess how close
 * it was or how many tokens there are before the match.
 */
export class TokenTable {
  #entries: Stored[] = [];

  /** `sha256` is lowercase hex; anything else is not a digest and is ignored. */
  add(sha256: string, entry: TokenEntry): void {
    if (!HEX_DIGEST.test(sha256)) return;
    this.#entries.push({ ...entry, digest: Buffer.from(sha256, "hex") });
  }

  removeUser(userId: number): void {
    this.#entries = this.#entries.filter((e) => e.userId !== userId);
  }

  clear(): void {
    this.#entries = [];
  }

  find(sha256: string, kind: TokenKind): TokenEntry | null {
    const valid = HEX_DIGEST.test(sha256);
    const presented = valid ? Buffer.from(sha256, "hex") : Buffer.alloc(32);
    let found: Stored | null = null;
    for (const entry of this.#entries) {
      if (timingSafeEqual(entry.digest, presented) && valid && entry.kind === kind) found = entry;
    }
    return found === null ? null : { userId: found.userId, kind: found.kind, name: found.name };
  }
}

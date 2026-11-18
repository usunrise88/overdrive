import type { Logger } from "../../src/log.ts";
import type { Timers } from "../../src/timers.ts";

/** A clock the test moves by hand. */
export interface TestClock {
  now(): Date;
  set(instant: string | number): void;
  advance(ms: number): void;
}

export function testClock(start: string | number): TestClock {
  let current = new Date(start).getTime();
  return {
    now: () => new Date(current),
    set(instant) {
      current = new Date(instant).getTime();
    },
    advance(ms) {
      current += ms;
    },
  };
}

export interface LogLine {
  level: "debug" | "info" | "warn" | "error";
  fields: Record<string, unknown>;
  message: string | undefined;
}

/** A logger that keeps its lines, for asserting on what was said (and what was not). */
export function collectingLogger(): { log: Logger; lines: LogLine[]; text(): string } {
  const lines: LogLine[] = [];
  const at =
    (level: LogLine["level"]) =>
    (fields: object, message?: string): void => {
      lines.push({ level, fields: fields as Record<string, unknown>, message });
    };
  return {
    log: { debug: at("debug"), info: at("info"), warn: at("warn"), error: at("error") },
    lines,
    text: () => JSON.stringify(lines),
  };
}

/** Timers the test fires by hand: nothing happens until `tick`. */
export interface ManualTimers extends Timers {
  /** The loops currently scheduled: what they wait for, in milliseconds. */
  pending: { kind: "after" | "every"; ms: number }[];
  /** Runs everything scheduled now; a one-shot is used up, an interval stays. */
  tick(): void;
}

export function manualTimers(): ManualTimers {
  const scheduled: { kind: "after" | "every"; ms: number; fn: () => void }[] = [];
  const add = (kind: "after" | "every", ms: number, fn: () => void): (() => void) => {
    const item = { kind, ms, fn };
    scheduled.push(item);
    return () => {
      const at = scheduled.indexOf(item);
      if (at >= 0) scheduled.splice(at, 1);
    };
  };
  return {
    after: (ms, fn) => add("after", ms, fn),
    every: (ms, fn) => add("every", ms, fn),
    get pending() {
      return scheduled.map(({ kind, ms }) => ({ kind, ms }));
    },
    tick() {
      for (const item of [...scheduled]) {
        if (item.kind === "after") scheduled.splice(scheduled.indexOf(item), 1);
        item.fn();
      }
    },
  };
}

/** `sleep` for the client: records the waits and does not wait. */
export function recordedSleep(): { sleep: (ms: number) => Promise<void>; waits: number[] } {
  const waits: number[] = [];
  return {
    waits,
    sleep: async (ms) => {
      waits.push(ms);
    },
  };
}

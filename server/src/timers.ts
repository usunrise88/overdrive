/**
 * The service's loops (the plan refresher, the upload worker) schedule through this, so a test can
 * fire a tick by hand instead of waiting for one. The real timers never keep the process alive.
 */
export interface Timers {
  /** Calls `fn` once after `ms`. Returns a function that cancels it. */
  after(ms: number, fn: () => void): () => void;
  /** Calls `fn` every `ms`. Returns a function that stops it. */
  every(ms: number, fn: () => void): () => void;
}

export const realTimers: Timers = {
  after(ms, fn) {
    const handle = setTimeout(fn, ms);
    handle.unref();
    return () => clearTimeout(handle);
  },
  every(ms, fn) {
    const handle = setInterval(fn, ms);
    handle.unref();
    return () => clearInterval(handle);
  },
};

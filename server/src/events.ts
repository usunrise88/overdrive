/**
 * The service's in-process events (ADR 0016): how one module tells the others that something
 * happened without importing them. The intake announces a stored ride; the plan store announces a
 * changed plan. The upload worker, and later the MCP, Telegram, Google Calendar and Strava modules,
 * subscribe. Delivery is synchronous in order of subscription and never fails the emitter: a
 * listener that throws (or returns a promise that rejects) is reported to `onError` and the others
 * still run.
 */

/** A ride whose record and FIT file are both stored: complete, ready to be forwarded. */
export interface RideStoredEvent {
  rideId: string;
  startEpochMillis: number;
}

/** What changed in the plan cache, by planned workout id (`icu-123`). Never empty. */
export interface PlanChangedEvent {
  added: string[];
  updated: string[];
  removed: string[];
}

export interface AppEvents {
  "ride-stored": RideStoredEvent;
  "plan-changed": PlanChangedEvent;
}

export type AppEventName = keyof AppEvents;

export type Listener<K extends AppEventName> = (event: AppEvents[K]) => void | Promise<void>;

export interface EventBus {
  /** Subscribes; the returned function unsubscribes. */
  on<K extends AppEventName>(name: K, listener: Listener<K>): () => void;
  /** Delivers to every listener now; async listeners are not awaited. */
  emit<K extends AppEventName>(name: K, event: AppEvents[K]): void;
}

export function createEventBus(
  onError: (name: AppEventName, error: unknown) => void = () => {},
): EventBus {
  const listeners = new Map<AppEventName, Set<Listener<AppEventName>>>();

  return {
    on(name, listener) {
      const set = listeners.get(name) ?? new Set();
      listeners.set(name, set);
      set.add(listener as Listener<AppEventName>);
      return () => {
        set.delete(listener as Listener<AppEventName>);
      };
    },
    emit(name, event) {
      for (const listener of [...(listeners.get(name) ?? [])]) {
        try {
          const result = listener(event);
          if (result !== undefined) result.catch((error: unknown) => onError(name, error));
        } catch (error) {
          onError(name, error);
        }
      }
    },
  };
}

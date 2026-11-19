import type { Db } from "../db.ts";

/**
 * The cue log (spec §12.1, ADR 0038): every cue the live coach made, kept on the ride in `live_cues` (migration 0012), keyed by the ride's
 * id. PRIVATE: a cue's text is derived from the rider's ride. Nothing here is ever logged, only ids and counts.
 */

export interface NewLiveCue {
  rideId: string;
  sessionId: string;
  /** The cue's number in its session, from 1; the SSE id. */
  seq: number;
  trigger: string;
  /** Who wrote the line: the model, or the agent for a `must_say` line. */
  source: "model" | "must-say";
  text: string;
  /** The flattened step the cue was about. */
  stepIndex: number | null;
  /** The ride's time when it was triggered, whole seconds (the ride record's offsets). */
  atSeconds: number;
  createdAt: Date;
}

export interface LiveCueRow {
  rideId: string;
  sessionId: string;
  seq: number;
  trigger: string;
  source: "model" | "must-say";
  text: string;
  stepIndex: number | null;
  atSeconds: number;
  createdAt: string;
  /** What the tablet reported: null while unknown (it did not say). */
  played: boolean | null;
}

export interface LiveCueStore {
  /** Idempotent by (session, seq). */
  insert(cue: NewLiveCue): Promise<void>;
  /** The cues of a ride, in the order they were made. */
  forRide(rideId: string): Promise<LiveCueRow[]>;
  /** Records what the tablet reported; false when no such cue is stored. */
  markPlayed(sessionId: string, seq: number, played: boolean, at: Date): Promise<boolean>;
}

interface Row {
  ride_id: string;
  session_id: string;
  seq: number;
  trigger: string;
  source: "model" | "must-say";
  text: string;
  step_index: number | null;
  at_seconds: number;
  created_at: Date | string;
  played: boolean | null;
}

export function createLiveCueStore(db: Db): LiveCueStore {
  return {
    async insert(cue) {
      await db.query(
        `INSERT INTO live_cues (ride_id, session_id, seq, trigger, source, text, step_index, at_seconds, created_at)
         VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz)
         ON CONFLICT (session_id, seq) DO NOTHING`,
        [
          cue.rideId,
          cue.sessionId,
          cue.seq,
          cue.trigger,
          cue.source,
          cue.text,
          cue.stepIndex,
          cue.atSeconds,
          cue.createdAt.toISOString(),
        ],
      );
    },

    async forRide(rideId) {
      const result = await db.query<Row>(
        `SELECT ride_id, session_id, seq, trigger, source, text, step_index, at_seconds, created_at, played
           FROM live_cues WHERE ride_id = $1 ORDER BY created_at, id`,
        [rideId],
      );
      return result.rows.map((row) => ({
        rideId: row.ride_id,
        sessionId: row.session_id,
        seq: row.seq,
        trigger: row.trigger,
        source: row.source,
        text: row.text,
        stepIndex: row.step_index,
        atSeconds: row.at_seconds,
        createdAt: new Date(row.created_at).toISOString(),
        played: row.played,
      }));
    },

    async markPlayed(sessionId, seq, played, at) {
      const result = await db.query(
        `UPDATE live_cues SET played = $3, played_at = $4::timestamptz
          WHERE session_id = $1 AND seq = $2 RETURNING id`,
        [sessionId, seq, played, at.toISOString()],
      );
      return result.rows.length > 0;
    },
  };
}

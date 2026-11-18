/**
 * What a module that has something to tell the owner hands to the Telegram bot (ADR 0025), without importing the bot: a list of
 * candidates, each with the (kind, key) the bot's `bot_notifications` makes exactly-once, the window in which it is worth sending,
 * and a function that builds the message at the moment of sending (it may decide the message is no longer true and return null).
 */

/** A span of a message shown in monospace: `pre` is a block the reader can tap to copy. Offsets and lengths are in UTF-16 code units. */
export interface MessageEntity {
  type: "pre" | "code";
  offset: number;
  length: number;
}

export interface NotificationMessage {
  text: string;
  entities?: MessageEntity[];
}

export interface NotificationCandidate {
  /** With `key`, what makes it exactly-once: one `bot_notifications` row per (kind, key). */
  kind: string;
  key: string;
  /** Epoch milliseconds from which it may be sent, and from which it is no longer worth sending. */
  dueAt: number;
  expiresAt: number;
  /** The message now, or null when it is no longer true. Health data: never logged. */
  build(): Promise<NotificationMessage | null>;
}

export interface NotificationSource {
  /** What may be sent at `nowMs`; the bot claims and sends each once. */
  candidates(nowMs: number): Promise<NotificationCandidate[]>;
}

import { ACTIVITY_FIT_MAX_BYTES, addDaysToIsoDate, isIsoDate } from "@overdrive/contracts";
import { IntervalsError } from "../intervals/client.ts";
import { eventIdOf } from "../intervals/ids.ts";
import { ImportError } from "../outdoor/fit-ride.ts";
import { shortDate, weekdayName } from "../zoned.ts";
import { type IncomingDocument, type InlineButton, TelegramError } from "./client.ts";
import { type BotContext, findEntry, loadDays, nextOpenWorkouts, todayOf } from "./context.ts";
import {
  fitDocumentRefusedText,
  fitImportedText,
  fitNotImportedText,
  HELP_TEXT,
  NOT_CONNECTED,
  READ_ONLY,
  todayText,
  UNKNOWN_COMMAND,
  weekText,
} from "./messages.ts";

/**
 * What the owner can ask for (spec §5.4): `/today`, `/week`, `/skip`, `/move`, `/help`. The handlers talk
 * to the chat through `io`, so they know nothing of Telegram's transport and the bot's owner filter has
 * already been applied by the time they run. Writes go through the plan store and intervals.icu, the same
 * calls the MCP tools use; with no plan source set up they say so in plain words.
 */

export interface ChatIo {
  /** Sends a new message. */
  reply(text: string, buttons?: InlineButton[][]): Promise<void>;
  /** Replaces the message a pressed button belongs to (a new message when it cannot be edited). */
  edit(text: string, buttons?: InlineButton[][]): Promise<void>;
}

export interface Commands {
  onText(text: string, io: ChatIo): Promise<void>;
  onButton(data: string, io: ChatIo): Promise<void>;
  /**
   * A file the owner sent. A FIT file from the bike computer is imported as an outdoor ride (spec §11.8, ADR 0026): `download` fetches
   * it (at most the given number of bytes), and the answer is a short summary, or a plain sentence when it is not a FIT file, is not
   * valid, is not cycling or was imported before.
   */
  onDocument(
    document: IncomingDocument,
    download: (maxBytes: number) => Promise<Uint8Array>,
    io: ChatIo,
  ): Promise<void>;
}

/** Callback data, at most 64 bytes: what a button sends back. */
const ASK_SKIP = "sk:";
const DO_SKIP = "sk!:";
const ASK_MOVE = "mv:";
const DO_MOVE = "md:";
const CANCEL = "no";

const PLAN_ID = /^[A-Za-z0-9._-]{1,40}$/;

/** How many days ahead a workout can be moved. */
const MOVE_DAYS = 7;

const cancelRow: InlineButton[] = [{ text: "Cancel", data: CANCEL }];

function oneLine(error: unknown): string {
  return error instanceof IntervalsError ? error.message : "an unexpected error";
}

export function createCommands(ctx: BotContext): Commands {
  const { log } = ctx;

  /** The plan cannot be read or changed without a plan source. */
  function notConnected(): boolean {
    return ctx.refresher === null || ctx.intervals === null;
  }

  async function today(io: ChatIo): Promise<void> {
    if (ctx.refresher === null) return io.reply(NOT_CONNECTED);
    const date = todayOf(ctx);
    const days = await loadDays(ctx, date, date);
    await io.reply(await withStaleNote(todayText(days.get(date) ?? [], date, ctx.workoutStart)));
  }

  async function week(io: ChatIo): Promise<void> {
    if (ctx.refresher === null) return io.reply(NOT_CONNECTED);
    const first = todayOf(ctx);
    const days = await loadDays(ctx, first, addDaysToIsoDate(first, 6));
    const list = Array.from({ length: 7 }, (_, index) => {
      const date = addDaysToIsoDate(first, index);
      return { date, entries: days.get(date) ?? [] };
    });
    await io.reply(await withStaleNote(weekText(list)));
  }

  /** The plan is the last one read when intervals.icu could not be reached since: say so. */
  async function withStaleNote(text: string): Promise<string> {
    const state = await ctx.plan.syncState("intervals.icu");
    return state.lastError === null
      ? text
      : `${text}\n\nNote: intervals.icu could not be reached at the last refresh, so this is the last plan I know.`;
  }

  // ---- /skip ----------------------------------------------------------------------------------

  async function skip(io: ChatIo): Promise<void> {
    if (notConnected()) return io.reply(ctx.refresher === null ? NOT_CONNECTED : READ_ONLY);
    const open = await nextOpenWorkouts(ctx);
    const [only] = open;
    if (only === undefined)
      return io.reply("No workout is waiting in the plan, so there is nothing to skip.");
    if (open.length === 1) return askSkip(only.id, io, false);
    await io.reply("Which workout do you want to skip?", [
      ...open.map((entry) => [
        { text: `${entry.name} (${shortDate(entry.date)})`, data: `${ASK_SKIP}${entry.id}` },
      ]),
      cancelRow,
    ]);
  }

  async function askSkip(id: string, io: ChatIo, edit: boolean): Promise<void> {
    const entry = await findEntry(ctx, id);
    const send = (text: string, buttons?: InlineButton[][]): Promise<void> =>
      edit ? io.edit(text, buttons) : io.reply(text, buttons);
    if (entry === null) return send("That workout is no longer in the plan.");
    await send(
      `Skip ${entry.name} on ${shortDate(entry.date)}? It is deleted from the calendar in intervals.icu.`,
      [
        [
          { text: "Yes, skip it", data: `${DO_SKIP}${id}` },
          { text: "Keep it", data: CANCEL },
        ],
      ],
    );
  }

  async function doSkip(id: string, io: ChatIo): Promise<void> {
    if (notConnected()) return io.edit(ctx.refresher === null ? NOT_CONNECTED : READ_ONLY);
    const entry = await findEntry(ctx, id);
    if (entry === null) return io.edit("That workout is no longer in the plan.");
    const eventId = eventIdOf(id);
    if (eventId === null || ctx.intervals === null) {
      return io.edit("I cannot change that workout: it does not come from intervals.icu.");
    }
    try {
      await ctx.intervals.deleteEvent(eventId);
    } catch (error) {
      log.warn({ planId: id, reason: oneLine(error) }, "skip failed at intervals.icu");
      return io.edit(`Could not skip it: ${oneLine(error)}. Nothing was changed.`);
    }
    // Noted before the plan loses it, so the week still counts it as skipped and not as vanished.
    await ctx.history.markSkipped(entry).catch((error: unknown) => {
      log.error({ err: error, planId: id }, "could not record a skipped workout");
    });
    await ctx.plan.remove(id);
    void ctx.refresher?.refresh();
    log.info({ planId: id, date: entry.date }, "workout skipped from Telegram");
    await io.edit(`Skipped ${entry.name} on ${shortDate(entry.date)}.`);
  }

  // ---- /move ----------------------------------------------------------------------------------

  async function move(io: ChatIo): Promise<void> {
    if (notConnected()) return io.reply(ctx.refresher === null ? NOT_CONNECTED : READ_ONLY);
    const open = await nextOpenWorkouts(ctx);
    const [only] = open;
    if (only === undefined)
      return io.reply("No workout is waiting in the plan, so there is nothing to move.");
    if (open.length === 1) return askMove(only.id, io, false);
    await io.reply("Which workout do you want to move?", [
      ...open.map((entry) => [
        { text: `${entry.name} (${shortDate(entry.date)})`, data: `${ASK_MOVE}${entry.id}` },
      ]),
      cancelRow,
    ]);
  }

  async function askMove(id: string, io: ChatIo, edit: boolean): Promise<void> {
    const entry = await findEntry(ctx, id);
    const send = (text: string, buttons?: InlineButton[][]): Promise<void> =>
      edit ? io.edit(text, buttons) : io.reply(text, buttons);
    if (entry === null) return send("That workout is no longer in the plan.");
    const first = todayOf(ctx);
    const days: InlineButton[] = [];
    for (let offset = 1; offset <= MOVE_DAYS; offset++) {
      const date = addDaysToIsoDate(first, offset);
      if (date === entry.date) continue;
      days.push({
        text: `${weekdayName(date)} ${Number(date.slice(8, 10))}`,
        data: `${DO_MOVE}${id}:${date}`,
      });
    }
    const rows: InlineButton[][] = [];
    for (let index = 0; index < days.length; index += 4) rows.push(days.slice(index, index + 4));
    rows.push(cancelRow);
    await send(`Move ${entry.name} (now ${shortDate(entry.date)}) to which day?`, rows);
  }

  async function doMove(id: string, date: string, io: ChatIo): Promise<void> {
    if (notConnected()) return io.edit(ctx.refresher === null ? NOT_CONNECTED : READ_ONLY);
    const first = todayOf(ctx);
    if (!isIsoDate(date) || date < first || date > addDaysToIsoDate(first, 14)) {
      return io.edit("I cannot move it to that day.");
    }
    const entry = await findEntry(ctx, id);
    if (entry === null) return io.edit("That workout is no longer in the plan.");
    const eventId = eventIdOf(id);
    if (eventId === null || ctx.intervals === null) {
      return io.edit("I cannot change that workout: it does not come from intervals.icu.");
    }
    if (entry.date === date) return io.edit(`${entry.name} is already on ${shortDate(date)}.`);
    try {
      await ctx.intervals.updateEvent(eventId, { start_date_local: `${date}T00:00:00` });
    } catch (error) {
      log.warn({ planId: id, reason: oneLine(error) }, "move failed at intervals.icu");
      return io.edit(`Could not move it: ${oneLine(error)}. Nothing was changed.`);
    }
    await ctx.plan.upsert({ ...entry, date });
    void ctx.refresher?.refresh();
    log.info({ planId: id, from: entry.date, to: date }, "workout moved from Telegram");
    await io.edit(`Moved ${entry.name} from ${shortDate(entry.date)} to ${shortDate(date)}.`);
  }

  // ---- A FIT file -------------------------------------------------------------------------------

  /** A document that is plainly something else: it has a name that does not end in .fit and a type that is not FIT or "a file". */
  function plainlyNotFit(document: IncomingDocument): boolean {
    const name = document.fileName?.toLowerCase() ?? null;
    const type = document.mimeType?.toLowerCase() ?? null;
    if (name?.endsWith(".fit") === true) return false;
    if (type === "application/vnd.ant.fit" || type === "application/octet-stream") return false;
    return name !== null || type !== null;
  }

  async function importDocument(
    document: IncomingDocument,
    download: (maxBytes: number) => Promise<Uint8Array>,
    io: ChatIo,
  ): Promise<void> {
    if (ctx.outdoor === null) return io.reply(fitDocumentRefusedText("unavailable"));
    if (plainlyNotFit(document)) return io.reply(fitDocumentRefusedText("not-fit"));
    if (document.fileSize !== null && document.fileSize > ACTIVITY_FIT_MAX_BYTES) {
      return io.reply(fitDocumentRefusedText("too-large"));
    }
    let bytes: Uint8Array;
    try {
      bytes = await download(ACTIVITY_FIT_MAX_BYTES);
    } catch (error) {
      const reason = error instanceof TelegramError ? error.kind : "unknown";
      log.warn({ reason }, "a FIT file could not be downloaded from Telegram");
      return io.reply(fitDocumentRefusedText(reason === "too-large" ? "too-large" : "download"));
    }
    try {
      const { response } = await ctx.outdoor.importFit(bytes, "telegram");
      // Counts and the status, never a value.
      log.info({ status: response.status, bytes: bytes.length }, "FIT file from Telegram");
      await io.reply(fitImportedText(response, ctx.timezone));
    } catch (error) {
      if (error instanceof ImportError) {
        log.info({ code: error.code, bytes: bytes.length }, "FIT file from Telegram refused");
        return io.reply(fitNotImportedText(error));
      }
      throw error;
    }
  }

  // ---- Dispatch -------------------------------------------------------------------------------

  return {
    onDocument: importDocument,

    async onText(text, io) {
      const match = /^\/([A-Za-z_]+)(?:@\w+)?(?:\s.*)?$/s.exec(text.trim());
      switch (match?.[1]?.toLowerCase()) {
        case "start":
        case "help":
          return io.reply(HELP_TEXT);
        case "today":
          return today(io);
        case "week":
          return week(io);
        case "skip":
          return skip(io);
        case "move":
          return move(io);
        default:
          return io.reply(UNKNOWN_COMMAND);
      }
    },

    async onButton(data, io) {
      if (data === CANCEL) return io.edit("Left as it is.");
      if (data.startsWith(DO_SKIP)) {
        const id = data.slice(DO_SKIP.length);
        return PLAN_ID.test(id) ? doSkip(id, io) : io.edit(UNKNOWN_COMMAND);
      }
      if (data.startsWith(ASK_SKIP)) {
        const id = data.slice(ASK_SKIP.length);
        return PLAN_ID.test(id) ? askSkip(id, io, true) : io.edit(UNKNOWN_COMMAND);
      }
      if (data.startsWith(ASK_MOVE)) {
        const id = data.slice(ASK_MOVE.length);
        return PLAN_ID.test(id) ? askMove(id, io, true) : io.edit(UNKNOWN_COMMAND);
      }
      if (data.startsWith(DO_MOVE)) {
        const [id = "", date = ""] = data.slice(DO_MOVE.length).split(":");
        return PLAN_ID.test(id) ? doMove(id, date, io) : io.edit(UNKNOWN_COMMAND);
      }
      return io.edit(UNKNOWN_COMMAND);
    },
  };
}

/** The buttons of a reminder: skip or move the workout it is about. */
export function reminderButtons(planId: string): InlineButton[][] {
  return [
    [
      { text: "Skip", data: `${ASK_SKIP}${planId}` },
      { text: "Move", data: `${ASK_MOVE}${planId}` },
    ],
  ];
}

import { isIsoDate, type NotebookResponse, type NotebookSection } from "@overdrive/contracts";
import { DEFAULT_NOTEBOOK_LOG_MAX_CHARS, DEFAULT_NOTEBOOK_MAX_CHARS } from "../config-coach.ts";
import type { EventBus } from "../events.ts";
import type { NotificationSource } from "../notifications.ts";
import { localDate } from "../time.ts";
import {
  LOG_SECTION,
  METHODOLOGY,
  type NotebookScope,
  type SectionDef,
  sectionsOf,
} from "./sections.ts";
import type { NotebookStore, VersionRow } from "./store.ts";

/**
 * The notebooks' rules (spec §11.6, §12.4, ADR 0035), over {@link NotebookStore}: fixed sections, a cap on the whole document and
 * one on the dated log, a reason that the methodology cannot be changed without, versions that are only ever added, and the
 * announcement to the rider of a change to the methodology. A mistake the caller can fix is a {@link NotebookError}; an answer that
 * is not a failure but asks the agent to do something first (condense) is an outcome with `saved: false`.
 */

/** A mistake the caller can understand and fix: an unknown section, a missing reason, a version that does not exist. */
export class NotebookError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "NotebookError";
  }
}

export interface NotebookLimits {
  /** The most the whole notebook may hold, in characters (UTF-16 code units). */
  maxChars: number;
  /** The most the dated log may hold. */
  logMaxChars: number;
}

export interface SectionView {
  key: string;
  title: string;
  text: string;
  /** 0 for a section never written. */
  version: number;
  /** UTC instant; null for version 0. */
  updatedAt: string | null;
  reason: string | null;
  chars: number;
}

export interface NotebookView {
  scope: NotebookScope;
  limits: NotebookLimits;
  totalChars: number;
  sections: SectionView[];
}

/** What to do when the notebook or its log is too full. Plain words for the agent. */
export interface CondenseAdvice {
  /** Which cap was reached. */
  cap: "document" | "log";
  chars: number;
  maxChars: number;
  how: string;
}

export type EditOutcome =
  | {
      saved: true;
      section: string;
      version: number;
      /** The version this one replaced; 0 when the section had never been written. */
      previousVersion: number;
      chars: number;
      totalChars: number;
      /** Methodology of the coach notebook only: the rider is told, through Telegram, when it is set up. */
      announcedToRider?: boolean;
      /** The log passed its cap with this entry: the agent must condense it now. */
      condense?: CondenseAdvice;
      note?: string;
    }
  | {
      saved: false;
      reason: "unchanged" | "version-conflict" | "document-too-large" | "log-full";
      section: string;
      message: string;
      currentVersion?: number;
      condense?: CondenseAdvice;
    };

export interface UpdateInput {
  section: string;
  text: string;
  reason?: string | undefined;
  /** The version the caller read; when the section has moved on, nothing is written. */
  expectedVersion?: number | undefined;
  author: string;
}

export interface AppendInput {
  entry: string;
  /** The rider's local day; default today. */
  date?: string | undefined;
  author: string;
}

export interface RollbackInput {
  section: string;
  toVersion: number;
  reason?: string | undefined;
  expectedVersion?: number | undefined;
  author: string;
}

export interface VersionListing {
  section: string;
  current: number;
  versions: {
    version: number;
    kind: VersionRow["kind"];
    rolledBackTo: number | null;
    reason: string | null;
    author: string;
    createdAt: string;
    chars: number;
    /** The first words of the text, so a version can be told from the next without reading it all. */
    preview: string;
    /** The whole text, when asked for. */
    text?: string;
  }[];
}

export interface NotebookService {
  readonly limits: NotebookLimits;
  /** One notebook, whole. */
  get(scope: NotebookScope): Promise<NotebookView>;
  /** The coach notebook as `GET /v1/notebook` serves it. Never the game master's. */
  coachResponse(): Promise<NotebookResponse>;
  update(scope: NotebookScope, input: UpdateInput): Promise<EditOutcome>;
  append(scope: NotebookScope, input: AppendInput): Promise<EditOutcome>;
  rollback(scope: NotebookScope, input: RollbackInput): Promise<EditOutcome>;
  versions(
    scope: NotebookScope,
    section: string,
    options: { limit: number; withText: boolean },
  ): Promise<VersionListing>;
  /** The methodology changes the rider is to be told of, for the Telegram bot. */
  readonly notifications: NotificationSource;
}

export interface NotebookServiceOptions {
  store: NotebookStore;
  bus: EventBus;
  timezone: string;
  now: () => Date;
  limits?: Partial<NotebookLimits>;
  /** Whether a message to the rider can be sent at all (the Telegram bot and the owner's chat are set up). */
  canAnnounce: boolean;
}

/** A change to the methodology is worth telling the rider of for a day after it was made. */
const ANNOUNCE_WINDOW_MS = 24 * 60 * 60 * 1000;
const MAX_REASON = 500;
const MAX_ENTRY = 300;
const PREVIEW = 80;

const CONDENSE_HOW =
  "Condense now: read the notebook with get_notebook, move what still matters from the old entries into the sections it belongs to " +
  "(what works, what does not, injuries, open questions, methodology) with update_notebook_section, then rewrite the log with " +
  'update_notebook_section (section "log", a reason such as "condensed old entries into the sections") so that it keeps only the ' +
  "recent entries. Nothing is lost: every earlier version stays in list_notebook_versions and can be restored with rollback_notebook_section.";

const SHORTEN_HOW =
  "Make room first: shorten the sections that have grown (update_notebook_section replaces a section's whole text, and every earlier " +
  "version stays in list_notebook_versions), keep what still matters, and say the same thing in fewer words. An edit that makes a " +
  "section shorter is always accepted.";

function sectionDef(scope: NotebookScope, key: string): SectionDef {
  const found = sectionsOf(scope).find((section) => section.key === key);
  if (found === undefined) {
    throw new NotebookError(
      `there is no section "${key}" in this notebook; the sections are ${sectionsOf(scope)
        .map((section) => section.key)
        .join(", ")}`,
    );
  }
  return found;
}

/** The reason as it is stored: trimmed, null when blank; the methodology cannot go without one. */
function reasonOf(scope: NotebookScope, section: string, raw: string | undefined): string | null {
  const reason = raw?.trim() ?? "";
  if (scope === "coach" && section === METHODOLOGY && reason === "") {
    throw new NotebookError(
      "changing the methodology needs a reason: say in a sentence why the approach changes (the rider is told of the change and of this reason)",
    );
  }
  if (reason.length > MAX_REASON) {
    throw new NotebookError(
      `the reason is ${reason.length} characters; at most ${MAX_REASON}: say it shorter`,
    );
  }
  return reason === "" ? null : reason;
}

const totalOf = (current: ReadonlyMap<string, VersionRow>): number => {
  let total = 0;
  for (const row of current.values()) total += row.text.length;
  return total;
};

const iso = (date: Date): string => date.toISOString();

export function createNotebookService(options: NotebookServiceOptions): NotebookService {
  const { store, bus, timezone, now, canAnnounce } = options;
  const limits: NotebookLimits = {
    maxChars: options.limits?.maxChars ?? DEFAULT_NOTEBOOK_MAX_CHARS,
    logMaxChars: options.limits?.logMaxChars ?? DEFAULT_NOTEBOOK_LOG_MAX_CHARS,
  };

  function view(scope: NotebookScope, current: ReadonlyMap<string, VersionRow>): NotebookView {
    const sections = sectionsOf(scope).map((def): SectionView => {
      const row = current.get(def.key);
      return {
        key: def.key,
        title: def.title,
        text: row?.text ?? "",
        version: row?.version ?? 0,
        updatedAt: row === undefined ? null : iso(row.createdAt),
        reason: row?.reason ?? null,
        chars: row?.text.length ?? 0,
      };
    });
    return { scope, limits, totalChars: totalOf(current), sections };
  }

  function documentTooLarge(
    section: string,
    wouldBe: number,
    current: ReadonlyMap<string, VersionRow>,
  ): EditOutcome {
    const chars = totalOf(current);
    return {
      saved: false,
      reason: "document-too-large",
      section,
      message:
        `Not saved. The notebook holds ${chars} characters and this edit would make it ${wouldBe}; the cap is ${limits.maxChars}. ` +
        SHORTEN_HOW,
      condense: { cap: "document", chars, maxChars: limits.maxChars, how: SHORTEN_HOW },
    };
  }

  /** Writes `text` as the next version of `section`, if the document stays within its cap. */
  async function write(
    scope: NotebookScope,
    next: {
      section: string;
      text: string;
      reason: string | null;
      kind: "edit" | "rollback";
      rolledBackTo: number | null;
      author: string;
      expectedVersion?: number | undefined;
    },
    extras: Partial<Extract<EditOutcome, { saved: true }>> = {},
  ): Promise<EditOutcome> {
    const at = now();
    const outcome = await store.mutate<EditOutcome>(scope, (current) => {
      const row = current.get(next.section);
      const currentVersion = row?.version ?? 0;
      if (next.expectedVersion !== undefined && next.expectedVersion !== currentVersion) {
        return {
          refuse: {
            saved: false,
            reason: "version-conflict",
            section: next.section,
            currentVersion,
            message:
              `Not saved. You edited from version ${next.expectedVersion} of "${next.section}" but it is now at version ${currentVersion}: ` +
              "another session changed it. Read it again with get_notebook, merge your change into what is there, and save again.",
          },
        };
      }
      const old = row?.text ?? "";
      if (next.text === old) {
        return {
          refuse: {
            saved: false,
            reason: "unchanged",
            section: next.section,
            currentVersion,
            message: `Nothing was saved: "${next.section}" already says exactly that (version ${currentVersion}).`,
          },
        };
      }
      const total = totalOf(current) - old.length + next.text.length;
      // An edit that does not make the document longer is always accepted, so that condensing is always possible.
      if (total > limits.maxChars && next.text.length > old.length) {
        return { refuse: documentTooLarge(next.section, total, current) };
      }
      return {
        write: {
          section: next.section,
          text: next.text,
          reason: next.reason,
          kind: next.kind,
          rolledBackTo: next.rolledBackTo,
          author: next.author,
          at,
        },
      };
    });
    if ("refused" in outcome) return outcome.refused;
    const written = outcome.written;
    bus.emit("notebook-changed", { scope, section: written.section, version: written.version });
    const current = await store.current(scope);
    const saved: Extract<EditOutcome, { saved: true }> = {
      saved: true,
      section: written.section,
      version: written.version,
      previousVersion: written.version - 1,
      chars: written.text.length,
      totalChars: totalOf(current),
      ...extras,
    };
    return saved;
  }

  /** What a saved change to the methodology says about the rider being told. */
  function announcement(
    scope: NotebookScope,
    section: string,
  ): Partial<{
    announcedToRider: boolean;
    note: string;
  }> {
    if (scope !== "coach" || section !== METHODOLOGY) return {};
    return canAnnounce
      ? { announcedToRider: true }
      : {
          announcedToRider: false,
          note: "The rider was NOT told in Telegram (the bot is not set up). Tell them in the chat that the methodology changed, and why.",
        };
  }

  return {
    limits,

    async get(scope) {
      return view(scope, await store.current(scope));
    },

    async coachResponse() {
      const coach = view("coach", await store.current("coach"));
      return {
        scope: "coach",
        generatedAt: iso(now()),
        limits,
        totalChars: coach.totalChars,
        sections: coach.sections.map((section) => ({
          key: section.key as NotebookSection,
          title: section.title,
          text: section.text,
          version: section.version,
          updatedAt: section.updatedAt,
          reason: section.reason,
        })),
      };
    },

    async update(scope, input) {
      sectionDef(scope, input.section);
      const reason = reasonOf(scope, input.section, input.reason);
      return write(
        scope,
        {
          section: input.section,
          text: input.text,
          reason,
          kind: "edit",
          rolledBackTo: null,
          author: input.author,
          expectedVersion: input.expectedVersion,
        },
        announcement(scope, input.section),
      );
    },

    async append(scope, input) {
      const entry = input.entry.replace(/\s+/g, " ").trim();
      if (entry === "") throw new NotebookError("the entry is empty");
      if (entry.length > MAX_ENTRY) {
        throw new NotebookError(
          `the entry is ${entry.length} characters; a log line is at most ${MAX_ENTRY}: say it shorter, and put detail in the section it belongs to`,
        );
      }
      const date = input.date ?? localDate(now(), timezone);
      if (!isIsoDate(date)) {
        throw new NotebookError(`date ${date} is not a real calendar date, write YYYY-MM-DD`);
      }
      const line = `${date}: ${entry}`;
      const at = now();
      // Two sessions appending at once must both be kept, so the line is added to the text as it is under the lock.
      const outcome = await store.mutate<EditOutcome>(scope, (current) => {
        const row = current.get(LOG_SECTION);
        const old = row?.text ?? "";
        if (old.length > limits.logMaxChars) {
          return {
            refuse: {
              saved: false,
              reason: "log-full",
              section: LOG_SECTION,
              message:
                `Not saved. The decisions log holds ${old.length} characters and the cap is ${limits.logMaxChars}. ` +
                `${CONDENSE_HOW} Then append this entry again.`,
              condense: {
                cap: "log",
                chars: old.length,
                maxChars: limits.logMaxChars,
                how: CONDENSE_HOW,
              },
            },
          };
        }
        const text = old === "" ? line : `${old}\n${line}`;
        const total = totalOf(current) - old.length + text.length;
        if (total > limits.maxChars)
          return { refuse: documentTooLarge(LOG_SECTION, total, current) };
        return {
          write: {
            section: LOG_SECTION,
            text,
            reason: "log entry",
            kind: "append",
            rolledBackTo: null,
            author: input.author,
            at,
          },
        };
      });
      if ("refused" in outcome) return outcome.refused;
      const written = outcome.written;
      bus.emit("notebook-changed", { scope, section: written.section, version: written.version });
      const total = totalOf(await store.current(scope));
      const over = written.text.length > limits.logMaxChars;
      return {
        saved: true,
        section: LOG_SECTION,
        version: written.version,
        previousVersion: written.version - 1,
        chars: written.text.length,
        totalChars: total,
        ...(over
          ? {
              condense: {
                cap: "log" as const,
                chars: written.text.length,
                maxChars: limits.logMaxChars,
                how: CONDENSE_HOW,
              },
              note: `Saved, but the log now holds ${written.text.length} characters and the cap is ${limits.logMaxChars}: no further entry is accepted until you condense it. ${CONDENSE_HOW}`,
            }
          : {}),
      };
    },

    async rollback(scope, input) {
      sectionDef(scope, input.section);
      const target = await store.version(scope, input.section, input.toVersion);
      if (target === null) {
        throw new NotebookError(
          `there is no version ${input.toVersion} of "${input.section}"; list_notebook_versions shows them`,
        );
      }
      const reason =
        reasonOf(scope, input.section, input.reason) ?? `rolled back to version ${input.toVersion}`;
      return write(
        scope,
        {
          section: input.section,
          text: target.text,
          reason,
          kind: "rollback",
          rolledBackTo: input.toVersion,
          author: input.author,
          expectedVersion: input.expectedVersion,
        },
        announcement(scope, input.section),
      );
    },

    async versions(scope, section, { limit, withText }) {
      sectionDef(scope, section);
      const rows = await store.versions(scope, section, limit);
      const [newest] = rows;
      return {
        section,
        current: newest?.version ?? 0,
        versions: rows.map((row) => ({
          version: row.version,
          kind: row.kind,
          rolledBackTo: row.rolledBackTo,
          reason: row.reason,
          author: row.author,
          createdAt: iso(row.createdAt),
          chars: row.text.length,
          preview:
            row.text.length <= PREVIEW ? row.text : `${row.text.slice(0, PREVIEW).trimEnd()}…`,
          ...(withText ? { text: row.text } : {}),
        })),
      };
    },

    notifications: {
      async candidates(nowMs) {
        const rows = await store.since("coach", METHODOLOGY, nowMs - ANNOUNCE_WINDOW_MS);
        return rows.map((row) => ({
          kind: "notebook-methodology",
          key: String(row.version),
          dueAt: row.createdAt.getTime(),
          expiresAt: row.createdAt.getTime() + ANNOUNCE_WINDOW_MS,
          build: async () => ({ text: methodologyChangedText(row) }),
        }));
      },
    },
  };
}

/** The one short message to the rider, written by code from the version's fields. */
export function methodologyChangedText(
  row: Pick<VersionRow, "version" | "reason" | "kind" | "rolledBackTo">,
): string {
  const what =
    row.kind === "rollback" && row.rolledBackTo !== null
      ? `Methodology went back to the text of version ${row.rolledBackTo} (now version ${row.version}).`
      : `Methodology changed (version ${row.version}).`;
  return `Coach notebook: ${what}\nWhy: ${row.reason ?? "no reason was recorded"}`;
}

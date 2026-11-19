import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { isIsoDate } from "@overdrive/contracts";
import { z } from "zod";
import {
  COACH_SECTIONS,
  GAME_MASTER_SECTIONS,
  LOG_SECTION,
  METHODOLOGY,
  type NotebookScope,
} from "../../notebook/sections.ts";
import { NotebookError } from "../../notebook/service.ts";
import { GAME_MASTER_SCOPE } from "../../oauth/routes.ts";
import { dateField } from "../schemas.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

const MAX_VERSIONS = 50;
/** An upper bound on what a call may carry, so a mistake does not fill the request; the notebook's own cap is far below it. */
const MAX_TEXT = 100_000;

/** Whether this session's token carries the game master's scope. */
export function hasGameMasterScope(ctx: ToolContext): boolean {
  return ctx.caller?.scopes.includes(GAME_MASTER_SCOPE) === true;
}

const sectionList = (defs: readonly { key: string; title: string; purpose: string }[]): string =>
  defs.map((def) => `${def.key} (${def.title}: ${def.purpose})`).join(" ");

/**
 * The coach notebook's tools (spec §11.6, §12.4, §8.4, ADR 0035): `get_notebook`, `update_notebook_section`, `append_notebook_log`,
 * `list_notebook_versions` and `rollback_notebook_section`. The agent's persistent working memory about the rider, kept on the
 * server, versioned, capped, and read-only for the rider in the companion.
 *
 * The game master's notebook is the same machinery under a second scope. **A session without that scope never sees it**: the
 * tools are built for this request, so for such a session they have no `scope` argument at all, no description mentions it and
 * every call is on the coach notebook; a session whose token carries the scope gets the argument.
 */
export function registerNotebookTools(server: McpServer, ctx: ToolContext): void {
  const { notebook } = ctx.services;
  const gameMaster = hasGameMasterScope(ctx);
  const author = ctx.caller?.clientId ?? "unknown";
  const { limits } = notebook;

  const sectionKeys = [
    ...new Set([
      ...COACH_SECTIONS.map((def) => def.key),
      ...(gameMaster ? GAME_MASTER_SECTIONS.map((def) => def.key) : []),
    ]),
  ] as [string, ...string[]];
  const sectionField = z.enum(sectionKeys);

  // The game master's session may name the notebook; every other session has no such argument, and an unknown argument is dropped
  // before the tool sees it, so a coach session that tries the name gets the coach notebook.
  const scopeField = z
    .enum(["coach", GAME_MASTER_SCOPE])
    .optional()
    .describe(
      `Which notebook: coach (default) or ${GAME_MASTER_SCOPE}, the game master's notebook that the rider never sees.`,
    );
  const scopeShape = (gameMaster ? { scope: scopeField } : {}) as { scope: typeof scopeField };
  const scopeOf = (args: { scope?: string | undefined }): NotebookScope =>
    gameMaster && args.scope === GAME_MASTER_SCOPE ? GAME_MASTER_SCOPE : "coach";

  const sections = sectionList(COACH_SECTIONS);
  const gmNote = gameMaster
    ? ` This session may also use the game master's notebook (scope ${GAME_MASTER_SCOPE}): sections ${sectionList(GAME_MASTER_SECTIONS)} The rider and the coach agent never see it: keep its contents out of the coach notebook, of chats and of the plan.`
    : "";

  /** What a failed rule of the notebook says, as a tool error. */
  const guarded = async <T>(work: () => Promise<T>): Promise<T> => {
    try {
      return await work();
    } catch (error) {
      if (error instanceof NotebookError) throw new ToolError(error.message);
      throw error;
    }
  };

  defineTool(
    server,
    ctx,
    {
      name: "get_notebook",
      title: "Read the coach notebook",
      kind: "read",
      description:
        "Your persistent working memory about this rider, kept on the server between sessions: START EVERY SESSION BY READING IT, before advising or " +
        "changing the plan. It is a document of fixed sections, each with its text, version number, when it last changed and why: " +
        `${sections} Without section you get the whole document (the sections in this order, with how many characters it holds against its cap, ` +
        "and the log's size against its own cap); with section only that one. A section never written has version 0 and empty text. The rider can read " +
        "this notebook in the companion app, read-only, so write what you would say to their face. " +
        `Check every plan change against the methodology section.${gmNote}`,
      input: {
        section: sectionField.optional().describe("One section only. Default: the whole notebook."),
        ...scopeShape,
      },
    },
    async (args) => {
      const scope = scopeOf(args);
      const view = await notebook.get(scope);
      const log = view.sections.find((section) => section.key === LOG_SECTION);
      const whole = {
        scope,
        limits: view.limits,
        totalChars: view.totalChars,
        log:
          log === undefined
            ? null
            : {
                chars: log.chars,
                maxChars: view.limits.logMaxChars,
                overCap: log.chars > view.limits.logMaxChars,
              },
      };
      if (args.section !== undefined) {
        const one = view.sections.find((section) => section.key === args.section);
        if (one === undefined) {
          throw new ToolError(
            `there is no section "${args.section}" in this notebook; the sections are ${view.sections.map((section) => section.key).join(", ")}`,
          );
        }
        return { ...whole, section: one };
      }
      return {
        ...whole,
        ...(view.totalChars === 0
          ? {
              note: "The notebook is empty: this is a first session. Fill it in as you learn about the rider (get_profile and the rides are a start), and record decisions in the log.",
            }
          : {}),
        sections: view.sections,
      };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "update_notebook_section",
      title: "Write a section of the coach notebook",
      kind: "write",
      description:
        "Replaces the whole text of one section of the notebook with the text you give, as a new version: every earlier version is kept " +
        "(list_notebook_versions) and can be restored (rollback_notebook_section), so nothing is ever lost, but you must send the " +
        "section's complete new text, not a fragment: read it first with get_notebook and keep what is still true. Say why in reason " +
        "(optional, except for the methodology). CHANGING THE METHODOLOGY IS DELIBERATE: " +
        `section ${METHODOLOGY} needs a written reason, and the server logs the change, versions it and tells the rider in Telegram ` +
        "with the section, the reason and the version; use it when the approach itself changes (and only then: day-to-day adjustments " +
        "inside the methodology need no announcement and go in the log). The notebook has a cap on its size " +
        `(${limits.maxChars} characters in all, ${limits.logMaxChars} for the log): an edit that would pass it is not saved and the answer says how to ` +
        "make room; an edit that makes the section shorter is always accepted. expectedVersion (the version you read) makes sure no other " +
        "session changed the section meanwhile: if it has, nothing is saved and you are told. Saving the text a section already has " +
        "writes nothing. The answer says saved true or false, the new version number and the sizes. " +
        `Sections: ${sections}${gmNote}`,
      input: {
        section: sectionField.describe("The section to replace."),
        text: z
          .string()
          .max(MAX_TEXT)
          .describe("The complete new text of the section. An empty text clears it."),
        reason: z
          .string()
          .max(600)
          .optional()
          .describe(
            `Why the section changes, in a sentence. Required, and not blank, for ${METHODOLOGY}; the rider is told it.`,
          ),
        expectedVersion: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe("The version you read. If the section has moved on, nothing is saved."),
        ...scopeShape,
      },
    },
    async (args) =>
      guarded(() =>
        notebook.update(scopeOf(args), {
          section: args.section,
          text: args.text,
          reason: args.reason,
          expectedVersion: args.expectedVersion,
          author,
        }),
      ),
  );

  defineTool(
    server,
    ctx,
    {
      name: "append_notebook_log",
      title: "Add a line to the notebook's decisions log",
      kind: "write",
      description:
        "Adds one dated line to the end of the notebook's decisions log: what you decided about the plan and why, in one short line " +
        "(at most 300 characters). RECORD THE REASON FOR EVERY PLAN CHANGE HERE, so the next session, which starts with no memory, knows why " +
        "the plan is as it is. The line is written as `YYYY-MM-DD: text`, dated today in the rider's time zone unless you give date. " +
        `The log has a cap (${limits.logMaxChars} characters): the entry that passes it is saved and the answer then asks you to condense ` +
        "the log (move what still matters into the sections, then rewrite the log shorter with update_notebook_section) and says how; " +
        "until you have, further entries are not saved and the answer says the same. This is an answer, not an error: do what it says. " +
        `Day-to-day adjustments inside the methodology go here; a change of the methodology itself goes through update_notebook_section.${gmNote}`,
      input: {
        entry: z
          .string()
          .min(1)
          .max(600)
          .describe("One line: what was decided and why. At most 300 characters."),
        date: dateField.optional().describe("The local day the decision was made. Default: today."),
        ...scopeShape,
      },
    },
    async (args) => {
      if (args.date !== undefined && !isIsoDate(args.date)) {
        throw new ToolError(`date ${args.date} is not a real calendar date, write YYYY-MM-DD`);
      }
      return guarded(() =>
        notebook.append(scopeOf(args), { entry: args.entry, date: args.date, author }),
      );
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "list_notebook_versions",
      title: "List the versions of a notebook section",
      kind: "read",
      description:
        "The history of one section of the notebook, newest first: for each version its number, how it came about (edit, append, or " +
        "rollback with the version it went back to), the reason given, who wrote it, when, its size and its first words (the whole text " +
        "with includeText). Use it to see what a section said before, to find the version to restore with rollback_notebook_section, or to " +
        `see why the methodology changed. Up to ${MAX_VERSIONS} versions, default 10; current is the number of the newest.${gmNote}`,
      input: {
        section: sectionField.describe("The section."),
        limit: z
          .number()
          .int()
          .min(1)
          .max(MAX_VERSIONS)
          .optional()
          .describe(`How many versions, newest first. Default 10, at most ${MAX_VERSIONS}.`),
        includeText: z
          .boolean()
          .optional()
          .describe("Give each version's whole text, not only its first words."),
        ...scopeShape,
      },
    },
    async (args) =>
      guarded(() =>
        notebook.versions(scopeOf(args), args.section, {
          limit: args.limit ?? 10,
          withText: args.includeText === true,
        }),
      ),
  );

  defineTool(
    server,
    ctx,
    {
      name: "rollback_notebook_section",
      title: "Restore an earlier version of a notebook section",
      kind: "write",
      description:
        "Restores the text a section had in an earlier version (list_notebook_versions) by writing it as a NEW version: history is never " +
        "rewritten, the version you leave stays in the list and a later rollback can bring it back. Say why in reason (the answer " +
        `records "rolled back to version N" if you do not; for ${METHODOLOGY} a reason is required and, as for any change of the methodology, ` +
        "the rider is told in Telegram). expectedVersion works as in update_notebook_section. Restoring the text the section already has " +
        `writes nothing, and a restore that would pass the notebook's cap is not saved and the answer says how to make room.${gmNote}`,
      input: {
        section: sectionField.describe("The section."),
        version: z.number().int().min(1).describe("The version whose text to restore."),
        reason: z
          .string()
          .max(600)
          .optional()
          .describe("Why. Required, and not blank, for the methodology."),
        expectedVersion: z
          .number()
          .int()
          .min(0)
          .optional()
          .describe("The version you read. If the section has moved on, nothing is saved."),
        ...scopeShape,
      },
    },
    async (args) =>
      guarded(() =>
        notebook.rollback(scopeOf(args), {
          section: args.section,
          toVersion: args.version,
          reason: args.reason,
          expectedVersion: args.expectedVersion,
          author,
        }),
      ),
  );
}

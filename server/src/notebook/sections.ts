import {
  METHODOLOGY_SECTION,
  NOTEBOOK_SECTION_TITLES,
  NOTEBOOK_SECTIONS,
} from "@overdrive/contracts";

/**
 * The notebooks and their fixed sections (spec §11.6, §12.4, ADR 0035). There are two, told apart by scope: the **coach** notebook,
 * which the rider can read in the companion, and the **game-master** notebook, the agent's game plans, which only a session whose
 * token carries the `game-master` scope can read or write and which no REST route ever serves.
 */

export const NOTEBOOK_SCOPES = ["coach", "game-master"] as const;
export type NotebookScope = (typeof NOTEBOOK_SCOPES)[number];

export interface SectionDef {
  key: string;
  title: string;
  /** What belongs in the section, in the words a model is told it in (the tools' descriptions). */
  purpose: string;
}

const COACH_PURPOSE: Record<(typeof NOTEBOOK_SECTIONS)[number], string> = {
  profile:
    "who the rider is and what limits the training: job, family, hours that are free, equipment, goals, preferences, what they hate.",
  what_works:
    "what has been observed to work for this rider: sessions, structure, progression, timing, fuelling, anything that moved the numbers or made riding easier.",
  what_does_not:
    "what has been observed not to work: sessions, loads, patterns that led to fatigue, missed days or a drop in form.",
  injuries:
    "injuries and niggles, when they began, what aggravates them, what is avoided, what the rider said about them (the rider's own words, no diagnosis).",
  methodology:
    "the current plan rationale: the training approach in use and why it suits this rider; the weekly structure; how load progresses; what counts as a hard and an easy day; when and how FTP and zones are revisited; what would justify changing the approach; the date of the next review. Changing it is deliberate: it needs a written reason and the rider is told.",
  open_questions:
    "what you do not know yet and want to find out: questions for the rider, things to watch in the next rides.",
  log: "the dated decisions log, one line per decision, oldest first, each starting with its date (append_notebook_log writes it). Condense it when it passes its cap.",
};

/** The coach notebook's sections, in the order they are shown. */
export const COACH_SECTIONS: readonly SectionDef[] = NOTEBOOK_SECTIONS.map((key) => ({
  key,
  title: NOTEBOOK_SECTION_TITLES[key],
  purpose: COACH_PURPOSE[key],
}));

/** The game master's notebook: what the agent has planned for the game and keeps from the rider. */
export const GAME_MASTER_SECTIONS: readonly SectionDef[] = [
  {
    key: "game_plans",
    title: "Game plans",
    purpose:
      "the game plans: what is set up for the rider, what is meant to happen when, and what is held back. The rider never reads this.",
  },
  {
    key: "notes",
    title: "Working notes",
    purpose: "working notes of the game master that do not belong in the plans.",
  },
  {
    key: "log",
    title: "Decisions log",
    purpose:
      "the dated decisions log, one line per decision, oldest first, each starting with its date. Condense it when it passes its cap.",
  },
];

export function sectionsOf(scope: NotebookScope): readonly SectionDef[] {
  return scope === "coach" ? COACH_SECTIONS : GAME_MASTER_SECTIONS;
}

/** The section that is a dated log: it has its own cap and its own tool. Both notebooks have one. */
export const LOG_SECTION = "log";

/** The coach notebook's section whose change needs a reason and is announced to the rider (spec §12.4). */
export const METHODOLOGY = METHODOLOGY_SECTION;

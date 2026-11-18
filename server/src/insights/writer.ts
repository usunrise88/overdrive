import type { Logger } from "../log.ts";
import type { Facts } from "./facts.ts";
import type { LlmClient, LlmMessage } from "./llm.ts";
import { promptVersion, retryMessage, systemMessage, userMessage } from "./prompts/index.ts";
import { TEMPLATE_VERSION, templateText } from "./template.ts";
import {
  countViolations,
  describeViolations,
  type TextValidator,
  type Violation,
} from "./validator.ts";

/**
 * Turns facts into the text of a note (spec §6.5, ADR 0025): the language model writes it when one is configured, the validator
 * checks it, and the deterministic template is used otherwise. The rule is enforced, not trusted: a text with a number that was not
 * supplied or a word that is not allowed is rejected, the model is asked once more with the violation named, and a second
 * rejection (or any failure of the endpoint) falls back to the template, so a note is always written and is always checked.
 *
 * Health data: this logs the kind of note, how it was written, how many requests it took and how many violations of each kind
 * there were. Never the facts, the text, or the offending numbers.
 */

export interface WrittenNote {
  text: string;
  /** Who wrote it: the model (its text passed the validator) or the template. */
  source: "llm" | "template";
  /** The prompt that asked for it, or the template's version. */
  promptVersion: string;
  /** Requests made to the model (0 for a template note written without one). */
  requests: number;
}

export interface NoteWriter {
  write(facts: Facts): Promise<WrittenNote>;
  /** Whether a model is configured. */
  readonly usesModel: boolean;
}

export interface NoteWriterOptions {
  /** null: no model configured, every note is the template's. */
  llm: LlmClient | null;
  /** `INSIGHTS_LANGUAGE`: put in the prompt as given. */
  language: string;
  validator: TextValidator;
  log: Logger;
}

/** Plain text, as the prompt asks: a model that sends markdown anyway gets it taken off, not rejected. */
export function plainText(text: string): string {
  return text
    .replace(/\r\n?/g, "\n")
    .replace(/(\*\*|__|`)/g, "")
    .replace(/^[ \t]*#{1,6}[ \t]+/gm, "")
    .replace(/[ \t]+\n/g, "\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

export function createNoteWriter(options: NoteWriterOptions): NoteWriter {
  const { llm, language, validator, log } = options;

  const fromTemplate = (facts: Facts, requests: number, why: string): WrittenNote => {
    log.info({ kind: facts.kind, source: "template", requests, why }, "a note was written");
    return {
      text: templateText(facts),
      source: "template",
      promptVersion: TEMPLATE_VERSION,
      requests,
    };
  };

  return {
    usesModel: llm !== null,
    async write(facts) {
      if (llm === null) return fromTemplate(facts, 0, "no-model");
      const kind = facts.kind;
      const messages: LlmMessage[] = [
        { role: "system", content: systemMessage(kind, language) },
        { role: "user", content: userMessage(kind, facts) },
      ];

      const first = await llm.complete(messages);
      if (!first.ok) return fromTemplate(facts, 1, `model-${first.kind}`);
      const firstText = plainText(first.text);
      const firstCheck = validator.check(firstText, facts);
      if (firstCheck.ok) {
        log.info({ kind, source: "llm", requests: 1 }, "a note was written");
        return { text: firstText, source: "llm", promptVersion: promptVersion(kind), requests: 1 };
      }

      // Once more, with the violation named.
      log.info(
        { kind, requests: 1, violations: countViolations(firstCheck.violations) },
        "a note was rejected; the model is asked once more",
      );
      const second = await llm.complete([
        ...messages,
        { role: "assistant", content: firstText },
        { role: "user", content: retryMessage(describeViolations(firstCheck.violations)) },
      ]);
      if (!second.ok) return fromTemplate(facts, 2, `model-${second.kind}`);
      const secondText = plainText(second.text);
      const secondCheck = validator.check(secondText, facts);
      if (secondCheck.ok) {
        log.info({ kind, source: "llm", requests: 2 }, "a note was written");
        return { text: secondText, source: "llm", promptVersion: promptVersion(kind), requests: 2 };
      }
      log.info(
        { kind, requests: 2, violations: countViolations(secondCheck.violations as Violation[]) },
        "a note was rejected twice",
      );
      return fromTemplate(facts, 2, "rejected-twice");
    },
  };
}

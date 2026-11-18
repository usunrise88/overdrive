/**
 * The words no written note may contain (spec §6.5: "make no diagnosis and name no medical condition", ADR 0025). The list is a
 * safety net behind the prompt, which asks the same: a text with one of these is rejected and written again, and after two
 * rejections the deterministic template is used instead. A word ending in `*` is a stem (`diagnos*` is diagnose, diagnosis,
 * diagnosed, ...); the rest are whole words or phrases. Matching ignores case. The owner adds words with `INSIGHT_DENY_WORDS`.
 *
 * The list is deliberately broad: a rejected text costs one more request or a plainer note, a diagnosis costs trust.
 */
export const DEFAULT_DENY_WORDS: readonly string[] = [
  "diagnos*",
  "disease*",
  "disorder*",
  "syndrome*",
  "pathology",
  "infect*",
  "virus",
  "viral",
  "covid*",
  "influenza",
  "flu",
  "cough*",
  "fever*",
  "illness*",
  "ill",
  "sick*",
  "anemi*",
  "anaemi*",
  "arrhythmi*",
  "tachycardi*",
  "bradycardi*",
  "fibrillation",
  "hypertens*",
  "hypotens*",
  "hypoxi*",
  "heart attack",
  "heart condition",
  "heart problem*",
  "cardiac",
  "cardiovascular",
  "stroke",
  "apnea",
  "apnoea",
  "insomnia",
  "depress*",
  "anxiety",
  "burnout",
  "diabet*",
  "asthma",
  "pneumonia",
  "bronchitis",
  "thyroid",
  "cancer",
  "tumor",
  "tumour",
  "overtrain*",
  "medical*",
  "medicin*",
  "medicat*",
  "doctor*",
  "physician*",
  "clinic*",
  "hospital*",
  "symptom*",
  "condition of health",
  "health condition*",
  "pregnan*",
];

const escapeRegExp = (text: string): string => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/** One regular expression for the whole list: a word is matched whole (a stem as a prefix), case-insensitively. */
export function compileDenyList(extra: readonly string[] = []): RegExp {
  const entries = [...DEFAULT_DENY_WORDS, ...extra]
    .map((word) => word.trim().toLowerCase())
    .filter((word) => word !== "");
  const parts = entries.map((word) => {
    const stem = word.endsWith("*");
    const body = escapeRegExp(stem ? word.slice(0, -1) : word).replace(/\s+/g, "\\s+");
    return stem ? `${body}\\p{L}*` : body;
  });
  return new RegExp(`(?<![\\p{L}\\p{N}])(?:${parts.join("|")})(?![\\p{L}\\p{N}])`, "giu");
}

/** The distinct words of `text` that are on the list, in the order they first appear, lower-case. */
export function deniedWords(text: string, list: RegExp): string[] {
  const found: string[] = [];
  for (const match of text.matchAll(list)) {
    const word = match[0].toLowerCase().replace(/\s+/g, " ");
    if (!found.includes(word)) found.push(word);
  }
  return found;
}

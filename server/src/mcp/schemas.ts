import { workoutJsonSchema } from "@overdrive/contracts";
import { z } from "zod";

/**
 * Input shapes shared by the tools. The workout's shape is advertised to the model as exactly the
 * contract's JSON Schema (`workout.schema.json`, with its `$ref`s written out), but it is not parsed
 * by Zod: the contract's own validator reads it, so a mistake is answered with the path in the
 * workout (`workout does not validate: /steps/0/duration must be integer`) and the one definition of
 * a valid workout stays in `contracts/`.
 */

export const dateField = z
  .string()
  .regex(/^\d{4}-\d{2}-\d{2}$/, "a calendar date written YYYY-MM-DD");

type Json = Record<string, unknown>;

/** Replaces every `{ "$ref": "#/$defs/name" }` with the definition it names. */
function inlineRefs(node: unknown, defs: Record<string, unknown>): unknown {
  if (Array.isArray(node)) return node.map((item) => inlineRefs(item, defs));
  if (typeof node !== "object" || node === null) return node;
  const object = node as Json;
  const ref = object.$ref;
  if (typeof ref === "string" && ref.startsWith("#/$defs/")) {
    return inlineRefs(defs[ref.slice("#/$defs/".length)], defs);
  }
  return Object.fromEntries(
    Object.entries(object)
      .filter(([key]) => key !== "$defs" && key !== "$schema")
      .map(([key, value]) => [key, inlineRefs(value, defs)]),
  );
}

const resolved = inlineRefs(workoutJsonSchema, (workoutJsonSchema.$defs ?? {}) as Json) as Json;
const properties = resolved.properties as Json;

/** The steps of a workout, as the contract describes them. */
const stepsSchema = properties.steps as Json;

/**
 * A workout for `create_workout`: the contract's, with the id and the schema number optional because
 * the server supplies them (the id is the calendar entry's, `icu-<number>`; the schema is 1).
 */
const newWorkoutSchema: Json = {
  ...resolved,
  title: "Workout",
  required: (resolved.required as string[]).filter((key) => key !== "id" && key !== "schema"),
  properties: {
    ...properties,
    schema: { const: 1, description: "Optional: the format's version, always 1." },
    id: {
      type: "string",
      description:
        "Optional, and ignored: the stored workout gets the id icu-<number> of its calendar entry.",
    },
  },
};

/** The `workout` argument of `create_workout`. */
export const newWorkoutField = z
  .record(z.string(), z.unknown())
  .meta(newWorkoutSchema)
  .describe("The workout in the native format (schema 1).");

/** The `steps` argument of `update_workout`. */
export const stepsField = z
  .array(z.record(z.string(), z.unknown()))
  .meta(stepsSchema)
  .describe("The complete new list of steps, replacing the old one.");

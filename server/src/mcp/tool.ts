import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { ToolAnnotations } from "@modelcontextprotocol/sdk/types.js";
import type { z } from "zod";
import { IntervalsError } from "../intervals/client.ts";
import type { Logger } from "../log.ts";
import type { Services } from "../services.ts";

/**
 * What a tool needs and how it fails. A tool throws {@link ToolError} for anything the caller can
 * understand and fix (a bad date, no plan source, a workout that does not validate): the message goes
 * back as a tool error, in plain words, so a model can read it and try again. Anything else is a bug
 * or an outage: it is logged here and answered without detail.
 */

export class ToolError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ToolError";
  }
}

export interface ToolContext {
  services: Services;
  now: () => Date;
  log: Logger;
}

/** intervals.icu's failures in the words a model and its owner can act on. */
export function describeIntervalsError(error: IntervalsError): string {
  switch (error.kind) {
    case "unauthorized":
      return "intervals.icu did not accept the server's API key: the owner has to fix INTERVALS_API_KEY";
    case "not-found":
      return "intervals.icu answered 404: the owner has to check INTERVALS_ATHLETE_ID and INTERVALS_BASE_URL";
    case "rejected":
      return `${error.message}; nothing was changed`;
    default:
      return `${error.message}; nothing was changed, try again later`;
  }
}

export interface ToolSpec<Shape extends z.ZodRawShape> {
  name: string;
  title: string;
  /** What it does, what it returns, in what units; the model reads this to choose and call it. */
  description: string;
  input: Shape;
  /** Reads change nothing; writes change the plan or the profile. */
  kind: "read" | "write";
  /** The write cannot be undone by repeating it with other values (a delete). */
  destructive?: boolean;
  /** The tool reaches intervals.icu. */
  external?: boolean;
}

const failure = (message: string) => ({
  isError: true as const,
  content: [{ type: "text" as const, text: message }],
});

/**
 * Registers a tool whose result is one JSON document (compact, in a text block) and whose failures
 * are tool errors. Numbers in a result are computed by code, never by a model.
 */
export function defineTool<Shape extends z.ZodRawShape>(
  server: McpServer,
  ctx: ToolContext,
  spec: ToolSpec<Shape>,
  run: (args: z.infer<z.ZodObject<Shape>>) => Promise<unknown>,
): void {
  const annotations: ToolAnnotations = {
    title: spec.title,
    readOnlyHint: spec.kind === "read",
    ...(spec.kind === "write" ? { destructiveHint: spec.destructive === true } : {}),
    idempotentHint: spec.kind === "read",
    openWorldHint: spec.external === true,
  };
  const callback = async (args: unknown) => {
    try {
      const result = await run(args as z.infer<z.ZodObject<Shape>>);
      return { content: [{ type: "text" as const, text: JSON.stringify(result) }] };
    } catch (error) {
      if (error instanceof ToolError) return failure(error.message);
      if (error instanceof IntervalsError) return failure(describeIntervalsError(error));
      ctx.log.error({ err: error, tool: spec.name }, "mcp tool failed");
      return failure(
        "Something went wrong on the server. Nothing was changed unless a result said so.",
      );
    }
  };
  server.registerTool(
    spec.name,
    { title: spec.title, description: spec.description, inputSchema: spec.input, annotations },
    // The SDK has parsed the arguments with the shape; its types for them are looser than ours.
    callback as never,
  );
}

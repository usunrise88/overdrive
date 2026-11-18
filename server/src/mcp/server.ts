import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import type { ToolContext } from "./tool.ts";
import { registerPlanTools } from "./tools/plan.ts";
import { registerProfileTools } from "./tools/profile.ts";
import { registerRideTools } from "./tools/rides.ts";

/** What the server tells a client about itself; not the release tag. */
export const MCP_SERVER_NAME = "overdrive";
export const MCP_SERVER_VERSION = "0.2.0";

const INSTRUCTIONS =
  "Overdrive plans and reviews the indoor cycling of one rider. A workout is a list of steps; a step's power target is a fraction of " +
  "the rider's FTP (0.88 means 88%) and its duration is in whole seconds. Dates are the rider's local days, written YYYY-MM-DD. " +
  "Every number in a result is computed by the server from recorded data: quote it, do not recalculate it. Start with get_profile " +
  "(FTP, zones, weekly availability, goals). The write tools create_workout, update_workout, delete_workout and update_profile change " +
  "the rider's real calendar and profile: use them only when asked. Results are compact by default; ask for detail only when needed.";

/**
 * A server with the Phase 2 tools (spec §8.4): planned workouts (list, create, update, delete),
 * rides (list, get) and the profile (get, update). One is made per request: the service is
 * stateless, so nothing survives between calls but what is in the database.
 */
export function createMcpServer(ctx: ToolContext): McpServer {
  const server = new McpServer(
    { name: MCP_SERVER_NAME, version: MCP_SERVER_VERSION },
    { instructions: INSTRUCTIONS },
  );
  registerProfileTools(server, ctx);
  registerPlanTools(server, ctx);
  registerRideTools(server, ctx);
  return server;
}

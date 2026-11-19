import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { GAME_MASTER_SCOPE } from "../oauth/routes.ts";
import type { McpCaller, ToolContext } from "./tool.ts";
import { registerActivityTools } from "./tools/activities.ts";
import { registerFitnessTools } from "./tools/fitness.ts";
import { registerInsightTools } from "./tools/insights.ts";
import { registerNotebookTools } from "./tools/notebook.ts";
import { registerPlanTools } from "./tools/plan.ts";
import { registerPlanEventTools } from "./tools/plan-events.ts";
import { registerProfileTools } from "./tools/profile.ts";
import { registerRepeatTools } from "./tools/repeats.ts";
import { registerRideTools } from "./tools/rides.ts";
import { registerRouteTools } from "./tools/routes.ts";
import { registerWellnessTools } from "./tools/wellness.ts";

/** What the server tells a client about itself; not the release tag. */
export const MCP_SERVER_NAME = "overdrive";
export const MCP_SERVER_VERSION = "0.3.0";

const INSTRUCTIONS =
  "Overdrive plans and reviews the indoor cycling of one rider. A workout is a list of steps; a step's power target is a fraction of " +
  "the rider's FTP (0.88 means 88%) and its duration is in whole seconds. Dates are the rider's local days, written YYYY-MM-DD. " +
  "Every number in a result is computed by the server from recorded data: quote it, do not recalculate it. Start with get_profile " +
  "(FTP, zones, weekly availability, goals). The write tools create_workout, update_workout, delete_workout and update_profile change " +
  "the rider's real calendar and profile: use them only when asked. get_fitness gives load, fitness, fatigue, form, the power curve and the FTP; " +
  "an FTP it suggests is only a suggestion, never change the FTP without the rider's say-so. For how the rider is today use get_recovery (a " +
  "state and the facts behind it) and get_wellness (the daily figures with the rider's own usual ranges): they are facts about training load " +
  "and recovery, never a diagnosis. list_activities and get_activity cover the tablet's rides and the watch's workouts. list_insights shows the " +
  "notes the rider has already been written (read them before advising); get_facts gives the computed facts behind a note, to reason over. " +
  "list_repeats and get_repeat show the sessions the rider has ridden more than once, heart rate at the same power and its trend, and get_ride says " +
  "how a ride compared with the attempt before it (repeatOf); heart rate also moves with heat, sleep and caffeine, so trust a trend over several attempts. " +
  "When the rider opens a chat, list_discussion_topics holds what the post-ride analysis found worth discussing: start from the latest open one " +
  "and mark it discussed with mark_discussion_topic when it has been talked through. " +
  // The notebook and the plan events (spec §11.6, §12.2, §12.4, ADR 0035).
  "YOU START WITH NO MEMORY OF THE LAST SESSION: the coach notebook is your memory about this rider. EVERY SESSION STARTS BY READING IT (get_notebook: " +
  "the rider's profile and constraints, what works, what does not, injuries, the methodology, open questions, a dated log of decisions) and by " +
  "checking list_plan_events (sessions missed, moved or deleted, illness or travel declared, the easier version ridden), whose open events are what " +
  "needs your attention now. Check EVERY plan change against the methodology section: day-to-day adjustments inside it need no announcement; " +
  "changing the approach itself means update_notebook_section on methodology with a written reason, and the rider is told in Telegram. RECORD THE " +
  "REASON FOR ANY PLAN CHANGE with append_notebook_log (one dated line), and keep the sections true (update_notebook_section replaces a section's whole " +
  "text; every old version is kept and rollback_notebook_section restores one). The notebook is size-capped: when an answer asks you to condense, " +
  "do it. The rider reads this notebook in the companion, so write what you would say to their face. When you have rebuilt the plan for an event with " +
  "the planning tools, call resolve_plan_event with a short note for the rider (what changed and why; the server sends it) and dropped true or false: " +
  "dropping a missed session is a valid outcome, and the rider is told plainly. The service moves nothing by itself. " +
  // Routes and the easier variant (spec §11.4, §11.5, ADR 0036).
  "ROUTES: create_route makes a route with elevation (from a GPX file, from waypoints routed over roads, or as a synthetic elevation profile) " +
  "that the rider rides with its real grade; give a free step of a workout its id as route_id. list_routes and delete_route manage them. " +
  "EASIER VERSIONS: when you plan a demanding session you may also write an easier version of it (variant and variantNote in create_workout and " +
  "update_workout); the rider sees their recovery state at the start of the ride and picks one version with a tap. It lives only in this server. " +
  "When it is ridden the server updates the calendar entry to what was done and list_plan_events shows a variant event: treat it as a fact about the " +
  "rider's day (they chose the easier version) when you plan the next sessions, and resolve it. " +
  // The live coach (spec §12.1, ADR 0038).
  "LIVE COACH: for key or strict sessions (threshold work, a test, a session where form matters) set live_coach true in create_workout or update_workout and " +
  "write a coach_brief (what the session is for, what to watch in this rider, what tone): during the ride a coach on the server speaks short cues through the " +
  "tablet, written by a fast model within rules that limit when it may talk, and it never changes a target or the workout. Add must_say lines to a step (spoken " +
  "exactly as written) only sparingly, for what the coach could not know. Read the notebook's profile and what-works sections before you write a brief. After a " +
  "ride, get_ride and get_facts show what the coach said (liveCoach): that is what the rider was told, so plan the next session knowing it. " +
  "Results are compact by default; ask for detail only when needed.";

/** What a session that holds the game master's scope is told in addition. */
const GAME_MASTER_INSTRUCTIONS =
  " THIS SESSION ALSO HOLDS THE GAME MASTER SCOPE: the notebook tools take scope game-master for the game master's own notebook (game plans, working " +
  "notes, a log), which the rider and the coach agent never see. Read it at the start of a game master session too, and keep its contents out of " +
  "the coach notebook, of chats, of notes to the rider and of the plan.";

/**
 * A server with the tools of spec §8.4: planned workouts (list, create, update, delete), activities (list, get; `list_rides` and
 * `get_ride` stay as deprecated aliases for one release), wellness and recovery (get), the profile (get, update), the coach notebook (get,
 * update a section, append to the log, list versions, roll back) and the plan events (list, resolve). One is made per
 * request, for the token that made it: the service is stateless, so nothing survives between calls but what is in the database.
 */
export function createMcpServer(base: ToolContext, caller?: McpCaller): McpServer {
  // The tools are built for this caller: the game master's notebook exists only for a token that carries its scope.
  const ctx: ToolContext = caller === undefined ? base : { ...base, caller };
  const gameMaster = ctx.caller?.scopes.includes(GAME_MASTER_SCOPE) === true;
  const server = new McpServer(
    { name: MCP_SERVER_NAME, version: MCP_SERVER_VERSION },
    { instructions: gameMaster ? INSTRUCTIONS + GAME_MASTER_INSTRUCTIONS : INSTRUCTIONS },
  );
  registerProfileTools(server, ctx);
  registerPlanTools(server, ctx);
  registerRouteTools(server, ctx);
  const rides = registerRideTools(server, ctx);
  registerActivityTools(server, ctx, rides);
  registerFitnessTools(server, ctx);
  registerRepeatTools(server, ctx);
  registerWellnessTools(server, ctx);
  registerInsightTools(server, ctx);
  registerNotebookTools(server, ctx);
  registerPlanEventTools(server, ctx);
  return server;
}

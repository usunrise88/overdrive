import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ROUTE_PROFILE_MAX_GRADE_PERCENT, ROUTE_PROFILE_MAX_METERS } from "@overdrive/contracts";
import { z } from "zod";
import { BROUTER_PROFILES, BrouterError } from "../../routes/brouter.ts";
import { RouteError } from "../../routes/geometry.ts";
import { GPX_MAX_BYTES } from "../../routes/gpx.ts";
import { type CreateRouteInput, MAX_WAYPOINTS } from "../../routes/service.ts";
import { defineTool, type ToolContext, ToolError } from "../tool.ts";

/** The largest synthetic profile's segment count: a profile is a few dozen segments, not a recording. */
const MAX_SEGMENTS = 500;

/**
 * What `create_route` accepts, discriminated by `mode`. The tool advertises one flat object (a client needs a single `type: object` at
 * the top), every mode's fields optional and said to belong to its mode; this union then checks what the chosen mode needs and says what
 * is wrong in plain words.
 */
const createRouteInput = z.discriminatedUnion("mode", [
  z.object({
    mode: z.literal("gpx"),
    name: z.string().trim().min(1).max(100),
    gpx: z.string().min(1, "gpx is empty: send the text of the GPX file"),
  }),
  z.object({
    mode: z.literal("waypoints"),
    name: z.string().trim().min(1).max(100),
    waypoints: z
      .array(
        z.object({
          lon: z.number().min(-180).max(180),
          lat: z.number().min(-90).max(90),
        }),
      )
      .min(2, "give at least 2 waypoints")
      .max(MAX_WAYPOINTS, `give at most ${MAX_WAYPOINTS} waypoints`),
    routingProfile: z.enum(BROUTER_PROFILES).optional(),
  }),
  z.object({
    mode: z.literal("profile"),
    name: z.string().trim().min(1).max(100),
    segments: z
      .array(
        z.object({
          lengthMeters: z.number().positive(),
          gradePercent: z.number(),
        }),
      )
      .min(1, "give at least one segment")
      .max(MAX_SEGMENTS, `give at most ${MAX_SEGMENTS} segments`),
    startElevationMeters: z.number().min(-500).max(9000).optional(),
  }),
]);

/** The first problem of a failed parse, as a sentence: `waypoints.1.lat must be at most 90`. */
function plain(error: z.ZodError): string {
  const issue = error.issues[0];
  if (issue === undefined) return "the arguments are not valid";
  const where = issue.path.join(".");
  if (issue.code === "invalid_union") return "mode must be gpx, waypoints or profile";
  return where === "" ? issue.message : `${where}: ${issue.message}`;
}

/**
 * `create_route`, `list_routes` and `delete_route` (spec §11.4, §8.4, ADR 0036): routes with elevation for the tablet's simulation mode.
 * The service resamples and smooths them and works out their distance and ascent; the tablet computes the grade. Routes come from the
 * agent, never from a catalogue.
 */
export function registerRouteTools(server: McpServer, ctx: ToolContext): void {
  const { routes } = ctx.services;

  defineTool(
    server,
    ctx,
    {
      name: "create_route",
      title: "Create a route",
      kind: "write",
      description:
        "Creates a route with elevation that the rider can ride on the tablet: the trainer's resistance then follows the route's grade, " +
        "and the virtual gears add their offset on top. A route backs a free ride or a free step of a workout (create_workout and " +
        "update_workout take a route_id on a free step). One tool, three ways in, chosen by mode, each with its own fields: " +
        "(1) mode gpx: gpx is the TEXT of a GPX file (at most 2 MB) whose track points have an elevation (<ele>); a point with no elevation is " +
        "refused, the server does not guess one. (2) mode waypoints: waypoints is 2 to 25 places in the order to ride them, each {lon, lat} " +
        "in degrees (LONGITUDE FIRST), which the server turns into a road route with elevation using the routing engine; routingProfile is " +
        "trekking (default, mixed roads), fastbike (road bike) or gravel. Only where the routing engine has map data, and only if routing " +
        "is switched on (the answer says so plainly if not). The route follows real roads, so it has coordinates and writes a map into the ride's " +
        `file. (3) mode profile: segments is a list of {lengthMeters, gradePercent} (rise over run: 5 is a 5% climb, -3 a descent), ` +
        `in the order ridden, at most ${ROUTE_PROFILE_MAX_METERS / 1000} km in all, each grade clamped to plus or minus ${ROUTE_PROFILE_MAX_GRADE_PERCENT}%; ` +
        "startElevationMeters (default 0) is the elevation of the start. A profile has no coordinates and no map. " +
        "Whatever the way in, the points are resampled to one every 20 m, the elevation is lightly smoothed, and the distance and ascent are " +
        "worked out by the server from the points: quote them, do not calculate them. The server sends the tablet no grade; the tablet works it out. " +
        "Returns the route's id, which a free step's route_id and the rider's ride then use. Routes are never changed: to change one, delete " +
        "it and create another. At most 200 routes are kept.",
      input: {
        mode: z
          .enum(["gpx", "waypoints", "profile"])
          .describe("How the route comes in: gpx, waypoints or profile."),
        name: z.string().describe("The route's name, 1 to 100 characters, shown to the rider."),
        gpx: z
          .string()
          .optional()
          .describe(
            `mode gpx: the text of the GPX file, at most ${GPX_MAX_BYTES / (1024 * 1024)} MB, with <ele> on every track point.`,
          ),
        waypoints: z
          .array(z.object({ lon: z.number(), lat: z.number() }))
          .optional()
          .describe(
            `mode waypoints: ${2} to ${MAX_WAYPOINTS} places in riding order, each {lon, lat} in degrees (longitude first).`,
          ),
        routingProfile: z
          .enum(BROUTER_PROFILES)
          .optional()
          .describe("mode waypoints: trekking (default), fastbike or gravel."),
        segments: z
          .array(z.object({ lengthMeters: z.number(), gradePercent: z.number() }))
          .optional()
          .describe(
            "mode profile: segments in riding order, each {lengthMeters, gradePercent} (rise over run, clamped to ±20).",
          ),
        startElevationMeters: z
          .number()
          .optional()
          .describe("mode profile: the elevation at the start in metres, default 0."),
      },
    },
    async (args) => {
      // A key that is not set is absent, not undefined, for the strict parse below.
      const given = Object.fromEntries(Object.entries(args).filter(([, v]) => v !== undefined));
      const parsed = createRouteInput.safeParse(given);
      if (!parsed.success) throw new ToolError(plain(parsed.error));
      const input: CreateRouteInput = parsed.data;
      try {
        const created = await routes.create(input);
        return {
          created: true,
          ...created.route,
          pointCount: created.pointCount,
          pointSpacingMeters: created.stepMeters,
          ...(created.notes.length > 0 ? { notes: created.notes } : {}),
          next: `Use route_id "${created.route.id}" on a free step of a workout (create_workout, update_workout) to have the rider ride it as part of a session.`,
        };
      } catch (error) {
        if (error instanceof RouteError || error instanceof BrouterError) {
          throw new ToolError(error.message);
        }
        throw error;
      }
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "list_routes",
      title: "List the routes",
      kind: "read",
      description:
        "The routes that exist, newest first: id, name, how it was made (gpx, waypoints or profile), distanceMeters, ascentMeters (both " +
        "computed by the server from the route's points), whether it has coordinates (a route with them writes a map into the ride's file), " +
        "and when it was created. Use an id as the route_id of a free step in create_workout or update_workout. The points themselves are " +
        "not listed.",
      input: {},
    },
    async () => {
      const list = await routes.list();
      return { count: list.length, routingOn: routes.routingOn, routes: list };
    },
  );

  defineTool(
    server,
    ctx,
    {
      name: "delete_route",
      title: "Delete a route",
      kind: "write",
      destructive: true,
      description:
        "Deletes a route. Rides already recorded on it are kept (they keep the route's name). A planned workout whose free step named the " +
        "route loses it: that step becomes a flat free ride again, and the answer says how many workouts were changed (look at them with " +
        "list_planned_workouts, and give them another route_id if the rider should still ride one). It cannot be undone: a route is created " +
        "again from its source. Delete only what the rider asked to remove.",
      input: { id: z.string().describe("The route's id, from list_routes.") },
    },
    async ({ id }) => {
      const removed = await routes.delete(id);
      if (!removed) throw new ToolError(`there is no route ${id}; list_routes shows the routes`);
      const changed = await ctx.services.extras.forgetRoute(id);
      ctx.log.info(
        { tool: "delete_route", routeId: id, workouts: changed.length },
        "route deleted",
      );
      return {
        id,
        deleted: true,
        workoutsChanged: changed,
        ...(changed.length > 0
          ? {
              note: "These planned workouts had a free step on this route; it is a flat free ride now.",
            }
          : {}),
      };
    },
  );
}

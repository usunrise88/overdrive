import { validatePlanEventDeclaration } from "@overdrive/contracts";
import type { FastifyInstance } from "fastify";
import { HttpError } from "../errors.ts";
import { PlanEventError, type PlanEventsService } from "./service.ts";

export interface PlanEventsRoutesOptions {
  service: PlanEventsService;
}

/**
 * Plan events (spec §12.2, §8.3, `contracts/schema/plan-event*.schema.json`), mounted under `/v1`:
 *
 * - `POST /plan-events`: the rider declares illness or travel (`{ kind, until? }`) so that the coach agent rebuilds the days ahead.
 *   Answers the plan event: `201` for a new one, `200` when the same declaration (kind, day and until) was made before. A body that
 *   is not a declaration, or an `until` before today, is `400 invalid_json`. The other kinds of plan event are detected by the
 *   service and cannot be declared.
 */
export function planEventsRoutes(app: FastifyInstance, options: PlanEventsRoutesOptions): void {
  const { service } = options;

  app.post("/plan-events", async (request, reply) => {
    reply.header("cache-control", "no-store");
    const checked = validatePlanEventDeclaration(request.body);
    if (!checked.ok) {
      throw new HttpError(
        "invalid_json",
        `Not a plan event declaration: ${checked.errors.slice(0, 3).join("; ")}`,
      );
    }
    try {
      const { event, created } = await service.declare(
        checked.value.kind,
        checked.value.until ?? null,
      );
      request.log.info(
        { device: request.device, kind: event.kind, created },
        "plan event declared",
      );
      return reply.code(created ? 201 : 200).send(event);
    } catch (error) {
      if (error instanceof PlanEventError) throw new HttpError("invalid_json", error.message);
      throw error;
    }
  });
}

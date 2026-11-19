package app.overdrive.core.route

import app.overdrive.core.fit.FitRoutes
import app.overdrive.core.ride.FlatStep

/**
 * The routes a ride may use (spec §11.4), as the ride engine is given them when it is built: only routes the tablet **has downloaded**,
 * so the ride never waits for the network and a route it does not have is the flat road.
 *
 * - A ride with **no workout** follows [freeRide], the route chosen at the start, if there is one.
 * - A ride **with a workout** follows, in each `free` step that names a route (`route_id`), that route if it is in [byId]. Every other
 *   step is flat: an ERG step has no road, and a `free` step with no route, or with one that is not downloaded yet, is the flat free
 *   ride it always was.
 * - [difficultyPercent] scales every route's grade ([RouteGrade]).
 */
public class RideRoutes(
    public val difficultyPercent: Int = RouteGrade.DEFAULT_DIFFICULTY_PERCENT,
    public val freeRide: Route? = null,
    public val byId: Map<String, Route> = emptyMap(),
) {
    init {
        require(difficultyPercent in RouteGrade.MIN_DIFFICULTY_PERCENT..RouteGrade.MAX_DIFFICULTY_PERCENT) {
            "Difficulty is 0 to 100 percent, got $difficultyPercent"
        }
    }

    /** The route behind a step of [steps]; with no steps (a ride with no workout) the chosen one. Null when the step is flat. */
    public fun routeOf(
        steps: List<FlatStep>,
        stepIndex: Int?,
    ): Route? =
        if (steps.isEmpty()) {
            freeRide
        } else {
            stepIndex?.let { steps.getOrNull(it) }?.routeId?.let(byId::get)
        }

    /** The first route the ride follows, in step order: what the ride record's `routeId` and `routeName` say. Null for a ride on the flat. */
    public fun firstRoute(steps: List<FlatStep>): Route? =
        if (steps.isEmpty()) freeRide else steps.firstNotNullOfOrNull { step -> step.routeId?.let(byId::get) }

    /** The routes of a ride with [steps], for the FIT file's positions. */
    public fun forFit(steps: List<FlatStep>): FitRoutes = FitRoutes { stepIndex -> routeOf(steps, stepIndex) }

    public companion object {
        /** The flat road everywhere. */
        public val None: RideRoutes = RideRoutes()
    }
}

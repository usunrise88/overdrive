package app.overdrive.ride.routes

import androidx.compose.runtime.Immutable
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteDetailsState
import app.overdrive.core.route.RouteStatus
import app.overdrive.core.route.RouteSummary
import app.overdrive.core.route.RoutesResponse
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.RecoveryState
import java.util.Locale

/**
 * What the Ready screen knows of the routes at one moment (spec §11.4, ADR 0037): the list the service gave (cached, shown offline) and
 * which routes' points are on the tablet. Plain data, so every sentence made from it is a unit test.
 */
@Immutable
data class RoutesView(
    /** The routes of the list, newest first, or none while the tablet has never had a list. */
    val routes: List<RouteSummary> = emptyList(),
    val details: RouteDetailsState = RouteDetailsState(),
) {
    /** A route's name: from the points on the tablet, else from the list, else unknown. */
    fun nameOf(id: String): String? = details.downloaded[id]?.name ?: routes.firstOrNull { it.id == id }?.name

    /** The route as the list or the tablet describes it. */
    fun summaryOf(id: String): RouteSummary? = details.downloaded[id]?.summary ?: routes.firstOrNull { it.id == id }

    fun statusOf(id: String): RouteStatus = details.statusOf(id)

    /** The routes whose points are on the tablet: what a ride can follow. */
    val downloaded: Map<String, Route> get() = details.downloaded

    companion object {
        val None = RoutesView()

        fun of(
            list: ResourceState<RoutesResponse>,
            details: RouteDetailsState,
        ): RoutesView = RoutesView(list.value?.routes.orEmpty(), details)
    }
}

/** One line of the route chooser: a route or the flat road (a null [id]). */
@Immutable
data class RouteOption(
    val id: String?,
    val title: String,
    /** "12.4 km · 340 m up · has a map", or "No hills" for the flat road. */
    val detail: String,
    val selected: Boolean,
)

/**
 * The Ready screen's route row for a free ride and the short list behind it (spec §11.4, design/system.md §6.1): one row, "Route" with
 * what is chosen ("Flat road" or the route's name), and a list of the cached routes with their distance, ascent and a word for whether
 * the route has a map. Made by [of]; the screen only draws it.
 *
 * @property title what is chosen: "Flat road" or the route's name.
 * @property detail one line under the row, or null: the facts of the chosen route, "Downloading…", or **"Route not downloaded yet"**,
 * which says plainly that the ride will be flat.
 * @property options the flat road first, then every route of the list.
 */
@Immutable
data class RouteChoiceUi(
    val title: String,
    val detail: String?,
    val options: List<RouteOption>,
) {
    companion object {
        const val FLAT_ROAD = "Flat road"
        const val NOT_DOWNLOADED = "Route not downloaded yet"
        const val DOWNLOADING = "Downloading…"
        const val FLAT_DETAIL = "No hills"
        private const val UNKNOWN_ROUTE = "A route"

        /**
         * @param chosen the id of the route chosen for a free ride, or null for the flat road.
         * @return null when there is nothing to choose from and nothing chosen: the screen draws no row, and says nothing.
         */
        fun of(
            view: RoutesView,
            chosen: String?,
        ): RouteChoiceUi? {
            if (view.routes.isEmpty() && chosen == null) return null
            val options =
                listOf(RouteOption(null, FLAT_ROAD, FLAT_DETAIL, selected = chosen == null)) +
                    view.routes.map { RouteOption(it.id, it.name, facts(it), selected = it.id == chosen) }
            if (chosen == null) return RouteChoiceUi(FLAT_ROAD, null, options)
            val title = view.nameOf(chosen) ?: UNKNOWN_ROUTE
            val detail =
                when (view.statusOf(chosen)) {
                    RouteStatus.READY -> view.summaryOf(chosen)?.let(::facts)
                    RouteStatus.DOWNLOADING -> DOWNLOADING
                    RouteStatus.FAILED, RouteStatus.NOT_DOWNLOADED -> NOT_DOWNLOADED
                }
            return RouteChoiceUi(title, detail, options)
        }

        /** "12.4 km · 340 m up · has a map" or "… · profile only": what a route is, in a line. */
        fun facts(route: RouteSummary): String =
            listOf(
                distance(route.distanceMeters),
                "${route.ascentMeters} m up",
                if (route.hasCoordinates) "has a map" else "profile only",
            ).joinToString(" · ")

        /** "640 m" under a kilometre, "12.4 km" up to 100 km, "142 km" above. */
        fun distance(meters: Int): String =
            when {
                meters < METERS_PER_KM -> {
                    "$meters m"
                }

                meters < HUNDRED_KM_IN_METERS -> {
                    String.format(
                        Locale.ROOT,
                        "%.1f km",
                        meters / METERS_PER_KM.toDouble(),
                    )
                }

                else -> {
                    "${(meters + METERS_PER_KM / 2) / METERS_PER_KM} km"
                }
            }

        private const val METERS_PER_KM = 1000
        private const val HUNDRED_KM_IN_METERS = 100_000
    }
}

/**
 * The plain line beside the easier version's choice (spec §11.5): "Recovery: caution", worded from the service's own state and never
 * rated here. Only a rated state of **today** says anything (`ready`, `caution`, `rest`); while the service is still learning the rider's
 * usual range, has no reading, or the answer is from another day, there is nothing to say, and the screen shows nothing.
 */
object RecoveryLine {
    fun of(
        recovery: ResourceState<RecoveryResponse>,
        today: String,
    ): String? {
        val response = recovery.value ?: return null
        if (response.date != today) return null
        val word =
            when (response.state) {
                RecoveryState.READY -> "ready"
                RecoveryState.CAUTION -> "caution"
                RecoveryState.REST -> "rest"
                RecoveryState.LEARNING, RecoveryState.NO_DATA -> return null
            }
        return "Recovery: $word"
    }
}

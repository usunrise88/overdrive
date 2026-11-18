package app.overdrive.core.plan

import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout

/** Synthetic planned workouts for the plan tests: nothing here is anyone's training. */
internal object PlanSamples {
    fun sweetSpot(id: String = "icu-1001") =
        Workout(
            schema = 1,
            id = id,
            name = "Sweet spot 3x10",
            description = "Three 10-minute efforts with 5-minute recoveries.",
            steps =
                listOf(
                    RampStep(duration = 600, from = 0.45, to = 0.7, label = "Warm-up"),
                    RepeatStep(
                        count = 3,
                        steps =
                            listOf(
                                SteadyStep(duration = 600, target = 0.88, label = "Sweet spot"),
                                SteadyStep(duration = 300, target = 0.5, label = "Recover"),
                            ),
                    ),
                    RampStep(duration = 300, from = 0.55, to = 0.4, label = "Cool-down"),
                ),
        )

    fun planned(
        id: String,
        date: String,
        workout: Workout = sweetSpot(id),
        name: String = workout.name,
    ) = PlannedWorkout(id, date, name, PlanSource.INTERVALS_ICU, workout)

    fun response(
        from: String,
        to: String,
        workouts: List<PlannedWorkout> = emptyList(),
        generatedAt: String? = "2026-11-18T06:30:00Z",
        stale: Boolean = false,
        configured: Boolean = true,
    ) = WorkoutsResponse(from, to, generatedAt, stale, configured, workouts)

    fun json(response: WorkoutsResponse): String = PlanApiJson.encodeWorkoutsResponse(response)
}

/** An in-memory [PlanCache]; [failWrites] makes the disk refuse, [damaged] makes the stored text unreadable. */
internal class MemoryPlanCache(
    var text: String? = null,
) : PlanCache {
    var failWrites = false
    var reads = 0
    var writes = 0

    override suspend fun read(): CachedPlan? {
        reads++
        return text?.let(PlanCacheFormat::decode)
    }

    override suspend fun write(plan: CachedPlan) {
        writes++
        if (failWrites) throw IllegalStateException("The disk refuses")
        text = PlanCacheFormat.encode(plan)
    }
}

/** A [HttpGetTransport] that answers from a script, in order (the last answer repeats), and writes down every request. */
internal class ScriptedGetTransport(
    vararg script: Any,
) : HttpGetTransport {
    private val script = script.toMutableList()
    private var last: Any? = null
    val requests = mutableListOf<HttpGetRequest>()

    /** What to do before answering: a test uses it to hold a request open. */
    var beforeAnswer: suspend () -> Unit = {}

    fun then(vararg more: Any) {
        script.addAll(more)
    }

    override suspend fun get(request: HttpGetRequest): HttpAnswer {
        requests += request
        beforeAnswer()
        val next = if (script.isNotEmpty()) script.removeAt(0).also { last = it } else checkNotNull(last)
        return when (next) {
            is HttpAnswer -> next
            is Throwable -> throw next
            else -> error("bad script entry")
        }
    }
}

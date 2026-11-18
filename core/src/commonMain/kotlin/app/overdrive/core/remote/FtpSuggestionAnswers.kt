package app.overdrive.core.remote

import app.overdrive.core.metrics.FtpSuggestionDismissRequest
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the screens know of the rider's answers to FTP suggestions.
 *
 * @property dismissed the suggestions the rider said **Keep** to: hidden from Today and Power at once, whether the service has
 * been told or not, and after a restart (the cached metrics may still hold them).
 * @property waiting how many of those the service has not been told yet ("Will be sent when the service can be reached").
 */
public data class FtpAnswers(
    val dismissed: Set<Int> = emptySet(),
    val waiting: Int = 0,
)

/**
 * **Keep 250 W**: `POST /v1/ftp-suggestion/dismiss` (spec §6.3, ADR 0022), queued so it works offline.
 *
 * The suggestion is hidden at once. The service is told now, or later when it can be reached: the answer is written down
 * **before** it is sent (a [TextStore], one line per suggestion, so it survives a restart), and [flush] sends what waits (at
 * every refresh). A service that answers 4xx other than 401, 408 and 429 (the suggestion is gone, or was never there) has settled
 * the matter too: the entry is kept as sent. Only the last [REMEMBER] answers are remembered.
 *
 * The file holds the checksum of the address (an answer is for one service's suggestions) and suggestion numbers, nothing secret.
 */
public class FtpSuggestionAnswers(
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    private val store: TextStore?,
) {
    private class Entry(
        val id: Int,
        val sent: Boolean,
    )

    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val mutableState = MutableStateFlow(FtpAnswers())
    private val lock = Mutex()

    /** What the screens read. */
    public val state: StateFlow<FtpAnswers> = mutableState.asStateFlow()

    /** Reads what was written down before. A damaged or foreign file reads as nothing. */
    public suspend fun load() {
        val text =
            try {
                store?.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        lock.withLock {
            if (entries.value.isEmpty()) entries.value = text?.let(::parse).orEmpty()
            publish()
        }
    }

    /** The settings changed: another service's suggestions are not these. */
    public fun settingsChanged() {
        publish()
    }

    /** Hides suggestion [id], writes the answer down and tells the service if it can be reached. */
    public suspend fun dismiss(id: Int) {
        require(id >= 1) { "A suggestion id is positive" }
        lock.withLock {
            val others = entries.value.filter { it.id != id }
            entries.value = (others + Entry(id, sent = false)).takeLast(REMEMBER)
            persist()
            publish()
        }
        flush()
    }

    /** Tells the service what it has not been told. Returns when done; a failure leaves the answers waiting. */
    public suspend fun flush() {
        lock.withLock {
            for (entry in entries.value.filter { !it.sent }) {
                val body = MetricsApiJson.encodeDismissRequest(FtpSuggestionDismissRequest(entry.id))
                val settled =
                    when (val outcome = client.post("/v1/ftp-suggestion/dismiss", body, MAX_ANSWER_BYTES)) {
                        is Outcome.Ok -> {
                            true
                        }

                        Outcome.NotSetUp -> {
                            return
                        }

                        is Outcome.Failed -> {
                            outcome.problem.kind == ProblemKind.REQUEST_REFUSED ||
                                outcome.problem.kind == ProblemKind.NO_SUCH_ROUTE
                        }
                    }
                if (!settled) return
                entries.value = entries.value.map { if (it.id == entry.id) Entry(it.id, sent = true) else it }
                persist()
                publish()
            }
        }
    }

    private suspend fun persist() {
        val target = store ?: return
        val text =
            buildString {
                append(HEADER).append(' ').append(CacheText.sourceOf(config().baseUrl)).append('\n')
                for (entry in entries.value) {
                    append(
                        entry.id,
                    ).append(' ').append(if (entry.sent) SENT else PENDING).append('\n')
                }
            }
        withContext(NonCancellable) {
            try {
                target.write(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Kept in memory for this run; the next answer writes the file again.
            }
        }
    }

    private fun parse(text: String): List<Entry> {
        val lines = text.lines()
        val header = lines.firstOrNull()?.split(' ') ?: return emptyList()
        if (header.size != 2 || header[0] != HEADER ||
            header[1] != CacheText.sourceOf(config().baseUrl)
        ) {
            return emptyList()
        }
        return lines
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split(' ')
                val id = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it >= 1 && parts.size == 2 } ?: return emptyList()
                val sent =
                    when (parts[1]) {
                        SENT -> true
                        PENDING -> false
                        else -> return emptyList()
                    }
                Entry(id, sent)
            }.takeLast(REMEMBER)
    }

    private fun publish() {
        val all = entries.value
        mutableState.value = FtpAnswers(all.map { it.id }.toSet(), all.count { !it.sent })
    }

    private companion object {
        const val HEADER = "overdrive-ftp-answers"
        const val SENT = "sent"
        const val PENDING = "pending"
        const val REMEMBER = 20
        const val MAX_ANSWER_BYTES = 4096
    }
}

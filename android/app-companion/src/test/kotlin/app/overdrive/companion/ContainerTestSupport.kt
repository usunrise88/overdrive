package app.overdrive.companion

import android.content.Context
import app.overdrive.companion.model.TestClock
import app.overdrive.data.settings.SecretText
import app.overdrive.data.sync.UrlConnectionTransport
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

/**
 * What the container tests share: a container on real threads, real files and real sockets on the loopback address, each with its
 * own temporary cache folder and settings file, and a wait that is for a condition, never for a time.
 *
 * **Where a test closes the container, or reads a file, after it saw a value in memory, it also waits for the disk**: the
 * repositories write the cache **before** they publish an answer (`RemoteResource`), so the file is there as soon as the answer is,
 * and the checks below read the file only after the state says so.
 */
class ContainerWorld(
    private val context: Context,
) : AutoCloseable {
    val service = LocalService()
    val cache: File = Files.createTempDirectory("companion-cache").toFile()
    private val containers = mutableListOf<CompanionContainer>()
    private var count = 0

    /** A container over [cache]; [name] is its settings file, shared by a "restarted" one. */
    fun container(
        name: String = "settings-${count++}",
        settleMillis: Long = 50,
    ): CompanionContainer =
        CompanionContainer(
            context,
            settingsName = name,
            cacheDirectory = cache,
            patchTransport = UrlConnectionTransport(setPatchMethod = JdkPatch::allow),
            clock = TestClock.clock,
            settleMillis = settleMillis,
        ).also { containers += it }

    fun CompanionContainer.configure(
        url: String = service.baseUrl,
        token: String = service.token,
    ) {
        updateService(url, token)
        await("the settings are stored") { settings.value?.isConfigured == true }
    }

    /** Stops a container the way the process ending does; the files stay. */
    fun stop(container: CompanionContainer) {
        container.close()
        containers -= container
    }

    override fun close() {
        containers.forEach { it.close() }
        service.close()
        cache.deleteRecursively()
    }

    fun diskText(): String =
        cache.walkTopDown().filter { it.isFile }.joinToString("\n") { it.name + ":\n" + it.readText() }
}

/** A bound, not a pause: a condition that holds is seen within ten milliseconds. */
fun await(
    what: String,
    timeoutMs: Long = 60_000,
    diagnostics: () -> String = { "" },
    condition: () -> Boolean,
) {
    val end = System.nanoTime() + timeoutMs * 1_000_000
    while (System.nanoTime() < end) {
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Timed out waiting for: $what\n${diagnostics()}")
}

/** The secret as a test types it. */
fun secret(value: String) = SecretText(value)

fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }

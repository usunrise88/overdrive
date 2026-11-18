package app.overdrive.companion

import java.net.HttpURLConnection

/**
 * **Tests only.** The JDK's `HttpURLConnection` knows only the classic verbs and refuses `PATCH`; Android's (OkHttp inside) accepts
 * it, so production sets `requestMethod = "PATCH"` and nothing more. A test on a plain JVM passes this to
 * `UrlConnectionTransport(setPatchMethod = JdkPatch::allow)` to write the verb into the connection's `method` field, which the
 * test JVM is allowed to do by `--add-opens=java.base/java.net=ALL-UNNAMED` (this module's `build.gradle.kts`).
 */
object JdkPatch {
    fun allow(connection: HttpURLConnection) {
        val method = HttpURLConnection::class.java.getDeclaredField("method")
        method.isAccessible = true
        method.set(connection, "PATCH")
    }
}

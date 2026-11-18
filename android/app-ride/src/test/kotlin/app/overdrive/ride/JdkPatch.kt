package app.overdrive.ride

import java.net.HttpURLConnection

/**
 * **Tests only.** The JDK's `HttpURLConnection` knows only the classic verbs and refuses `PATCH`; Android's (OkHttp inside) accepts
 * it, so production sets `requestMethod = "PATCH"` and nothing more. A container test on a plain JVM gives its profile transport
 * `UrlConnectionTransport(setPatchMethod = JdkPatch::allow)`, which writes the verb into the connection's `method` field; the test
 * JVM may do that because of `--add-opens=java.base/java.net=ALL-UNNAMED` in `app-ride/build.gradle.kts`. (The same helper is in
 * `android/data`'s tests, which prove the transport itself.)
 */
object JdkPatch {
    fun allow(connection: HttpURLConnection) {
        val method = HttpURLConnection::class.java.getDeclaredField("method")
        method.isAccessible = true
        method.set(connection, "PATCH")
    }
}

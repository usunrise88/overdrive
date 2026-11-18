package app.overdrive.core.fit

import app.overdrive.core.record.FitGoldenRide
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.SyntheticRide
import org.junit.Assume
import java.io.ByteArrayInputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **Opt-in oracle, never run in CI.** Decodes the files our writer produces with Garmin's own decoder, `com.garmin:fit`,
 * and compares what it reads with the record. It runs only when the path to that jar is given:
 *
 * ```
 * cd core && ./gradlew jvmTest --tests '*GarminFitOracleTest*' -PfitOracleJar=/path/to/fit-21.222.0.jar
 * ```
 *
 * Without the property every test here is skipped. The jar is **not** a dependency of this build and is never
 * committed (`docs/protocols/fit.md` section 7: the licence terms for test use are unresolved); it is loaded from the
 * path at run time through reflection on its public API, so nothing of Garmin's is compiled into or shipped with
 * anything. `docs/adr/0007-ride-log-and-fit.md` says where to get the jar.
 */
class GarminFitOracleTest {
    private val jarPath: String? = System.getProperty("overdrive.fit.oracle.jar")?.takeIf { it.isNotBlank() }

    private fun requireOracle(): Oracle {
        Assume.assumeTrue("Garmin oracle not enabled: pass -PfitOracleJar=/path/to/fit.jar", jarPath != null)
        return Oracle(File(checkNotNull(jarPath)))
    }

    /** One decoded message of Garmin's decoder, read through reflection. */
    private class Message(
        private val message: Any,
    ) {
        val name: String = call("getName") as String

        private fun call(
            method: String,
            vararg args: Pair<Class<*>, Any>,
        ): Any? =
            try {
                message.javaClass
                    .getMethod(
                        method,
                        *args
                            .map {
                                it.first
                            }.toTypedArray(),
                    ).invoke(message, *args.map { it.second }.toTypedArray())
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        /** The raw integer of field [number], or null when the file has it as the invalid value or not at all. */
        fun long(number: Int): Long? = call("getFieldLongValue", Int::class.javaPrimitiveType!! to number) as Long?

        /** The scaled value of field [number], or null. */
        fun double(number: Int): Double? =
            call("getFieldDoubleValue", Int::class.javaPrimitiveType!! to number) as Double?

        /** The scaled elements of an array field, such as the seconds of an `hrv` message. */
        fun doubles(number: Int): List<Double?> =
            (call("getFieldDoubleValues", Int::class.javaPrimitiveType!! to number) as Array<*>).map {
                it as Double?
            }
    }

    private class Oracle(
        jar: File,
    ) {
        private val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader())
        private val decodeClass = loader.loadClass("com.garmin.fit.Decode")
        private val listenerClass = loader.loadClass("com.garmin.fit.MesgListener")

        private fun decoder(): Any = decodeClass.getConstructor().newInstance()

        private fun boolCall(
            method: String,
            bytes: ByteArray,
        ): Boolean =
            decodeClass
                .getMethod(
                    method,
                    java.io.InputStream::class.java,
                ).invoke(decoder(), ByteArrayInputStream(bytes)) as Boolean

        fun isFileFit(bytes: ByteArray): Boolean = boolCall("isFileFit", bytes)

        fun checkFileIntegrity(bytes: ByteArray): Boolean = boolCall("checkFileIntegrity", bytes)

        /** Every message Garmin's decoder produces for [bytes], in file order. Throws if the decoder rejects the file. */
        fun decode(bytes: ByteArray): List<Message> {
            val messages = mutableListOf<Message>()
            val listener =
                Proxy.newProxyInstance(loader, arrayOf(listenerClass)) { _, method, args ->
                    if (method.name == "onMesg") messages += Message(args[0])
                    null
                }
            try {
                decodeClass
                    .getMethod(
                        "read",
                        java.io.InputStream::class.java,
                        listenerClass,
                    ).invoke(decoder(), ByteArrayInputStream(bytes), listener)
            } catch (e: InvocationTargetException) {
                throw AssertionError("Garmin's decoder rejected the file: ${e.targetException}", e.targetException)
            }
            return messages
        }
    }

    private fun counts(messages: List<Message>) = messages.groupingBy { it.name }.eachCount()

    @Test
    fun `Garmin decodes the worked example of fit md section 6`() {
        val oracle = requireOracle()
        val bytes = FitWriter(FitOptions(utcOffsetSeconds = -2)).write(FitGoldenRide.record())
        assertTrue(oracle.isFileFit(bytes), "isFileFit")
        assertTrue(oracle.checkFileIntegrity(bytes), "checkFileIntegrity")
        val messages = oracle.decode(bytes)
        assertEquals(
            mapOf("file_id" to 1, "event" to 2, "record" to 3, "lap" to 1, "session" to 1, "activity" to 1),
            counts(messages),
        )
        val records = messages.filter { it.name == "record" }
        assertEquals(listOf(120L, 121L, 122L), records.map { it.long(3) })
        assertEquals(listOf(90L, 91L, 92L), records.map { it.long(4) })
        assertEquals(listOf(200L, 210L, 220L), records.map { it.long(7) })
        assertEquals(listOf(0.0, 8.0, 16.1), records.map { it.double(5) })
        assertEquals(listOf(8.0, 8.1, 8.2), records.map { it.double(6) })
        val session = messages.single { it.name == "session" }
        assertEquals(2L, session.long(5), "sport cycling")
        assertEquals(58L, session.long(6), "sub-sport virtual activity")
    }

    @Test
    fun `Garmin decodes the synthetic ride with the values of the record`() {
        val oracle = requireOracle()
        val record: RideRecord = SyntheticRide.record()
        val bytes = FitWriter().write(record)
        assertTrue(oracle.isFileFit(bytes), "isFileFit")
        assertTrue(oracle.checkFileIntegrity(bytes), "checkFileIntegrity")
        val messages = oracle.decode(bytes)
        assertEquals(parseFit(bytes).messages.groupingBy { it.name }.eachCount(), counts(messages))

        val records = messages.filter { it.name == "record" }
        assertEquals(record.samples.size, records.size)
        for ((fit, sample) in records.zip(record.samples)) {
            val second = sample.timeOffsetSeconds
            assertEquals(sample.power?.toLong(), fit.long(7), "power at $second")
            assertEquals(sample.heartRate?.toLong(), fit.long(3), "heart rate at $second")
            assertEquals(sample.cadence?.toLong(), fit.long(4), "cadence at $second")
        }
        val laps = messages.filter { it.name == "lap" }
        assertEquals(record.laps.size, laps.size)
        for ((fit, lap) in laps.zip(record.laps)) {
            assertEquals(lap.durationSeconds.toDouble(), fit.double(7), "lap elapsed time")
            assertEquals(2L, fit.long(25))
            assertEquals(58L, fit.long(39))
        }
        val session = messages.single { it.name == "session" }
        assertEquals(record.laps.size.toLong(), session.long(26))
        assertEquals(record.summary.maxPower?.toLong(), session.long(21))
        // Garmin scales the 16-bit milliseconds by 1000 into seconds.
        val hrvIntervals = messages.filter { it.name == "hrv" }.flatMap { it.doubles(0) }
        assertEquals(record.rrIntervals.map { it.intervalMs / 1000.0 }, hrvIntervals)
    }

    @Test
    fun `Garmin decodes the file under every option of the writer`() {
        val oracle = requireOracle()
        val record = SyntheticRide.record()
        val variants =
            listOf(
                FitOptions(protocolVersion = 0x02),
                FitOptions(subSport = FitOptions.SUB_SPORT_INDOOR_CYCLING),
                FitOptions(speedFields = FitSpeedFields.SPEED),
                FitOptions(speedFields = FitSpeedFields.ENHANCED_SPEED),
                FitOptions(hrvPlacement = FitHrvPlacement.BEFORE_RECORD),
                FitOptions(timerEvents = false),
                FitOptions(recordStamp = FitRecordStamp.SECOND_END, utcOffsetSeconds = 3_600),
                FitOptions(loadFields = false, stepLapTrigger = 1),
            )
        for (options in variants) {
            val bytes = FitWriter(options).write(record)
            assertTrue(oracle.isFileFit(bytes) && oracle.checkFileIntegrity(bytes), "integrity under $options")
            val messages = oracle.decode(bytes)
            assertEquals(300, messages.count { it.name == "record" }, "records under $options")
            assertEquals(record.laps.size, messages.count { it.name == "lap" }, "laps under $options")
        }
    }
}

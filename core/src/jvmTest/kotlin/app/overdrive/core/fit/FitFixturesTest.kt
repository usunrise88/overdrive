package app.overdrive.core.fit

import app.overdrive.core.record.FitGoldenRide
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The FIT fixtures under `contracts/fixtures/fit`, which the web service's tests upload (ADR 0014): `fit-golden-3s.fit` is
 * what [FitWriter] writes for the ride of `rides/valid/fit-golden-3s.json` (the worked example of `docs/protocols/fit.md`
 * section 6, as in [FitGoldenTest]), and every file there must carry a correct header and file CRC.
 */
class FitFixturesTest {
    private val dir = File(System.getProperty("overdrive.contracts.dir"), "fixtures/fit")

    private fun files(): List<File> = dir.listFiles { f -> f.extension == "fit" }.orEmpty().sortedBy { it.name }

    @Test
    fun `the golden fixture is the writer's output for the golden ride`() {
        val written = FitWriter(FitOptions(utcOffsetSeconds = -2)).write(FitGoldenRide.record())
        assertEquals(written.toList(), File(dir, "fit-golden-3s.fit").readBytes().toList())
    }

    @Test
    fun `every fixture has the sizes and checksums a FIT file needs`() {
        assertTrue(files().size >= 2, "too few FIT fixtures under $dir")
        for (file in files()) {
            val bytes = file.readBytes()
            val headerSize = bytes[0].toInt()
            assertTrue(headerSize == 12 || headerSize == 14, "${file.name}: header size $headerSize")
            assertEquals(".FIT", String(bytes, 8, 4, Charsets.US_ASCII), file.name)
            val dataSize = (4..7).sumOf { (bytes[it].toLong() and 0xFF) shl (8 * (it - 4)) }
            assertEquals(headerSize + dataSize + 2, bytes.size.toLong(), "${file.name}: data size")
            if (headerSize == 14) {
                assertEquals(crcAt(bytes, 12), FitReader.crc16(bytes, 0, 12), "${file.name}: header CRC")
            }
            assertEquals(
                crcAt(bytes, bytes.size - 2),
                FitReader.crc16(bytes, 0, bytes.size - 2),
                "${file.name}: file CRC",
            )
        }
    }

    private fun crcAt(
        bytes: ByteArray,
        at: Int,
    ): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
}

package app.overdrive.ble.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

/** The one zip of the Bluetooth logs (docs/adr/0010): order, cap, nothing but the files, and damaged files skipped and counted. */
class BleLogArchiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val names =
        listOf(
            "ble-20261118T180412Z-trainer.txt",
            "ble-20261118T180412Z-strap.txt",
            "ble-20261119T070000Z-trainer.txt",
            "ble-20261120T093000Z-click.txt",
        )

    private fun logs(
        dir: File = folder.newFolder("ble-logs"),
        text: (String) -> String = { "# overdrive-ble-session 1\n# model D500\n0 S - - connecting\n# $it\n" },
    ): List<File> = names.map { File(dir, it).also { file -> file.writeText(text(it)) } }

    private fun target() = File(folder.root, "out/overdrive-ble-logs-20261121-0745.zip")

    private fun entries(zip: File): List<String> =
        ZipFile(zip).use {
            it.entries().asSequence().map { e -> e.name }.toList()
        }

    @Test
    fun `the newest sessions come first and every entry is named as its file`() {
        val result = BleLogArchive.build(logs(), target())
        assertEquals(BleLogArchiveResult(4, 0, 0, target().length()), result)
        assertEquals(
            listOf(
                "ble-20261120T093000Z-click.txt",
                "ble-20261119T070000Z-trainer.txt",
                "ble-20261118T180412Z-trainer.txt",
                "ble-20261118T180412Z-strap.txt",
            ),
            entries(target()),
        )
    }

    @Test
    fun `the archive holds the log files and nothing else, byte for byte`() {
        val dir = folder.newFolder("ble-logs")
        val files = logs(dir)
        // Other things in the folder, and a folder, are not offered: the builder is given the files and adds nothing of its own.
        File(dir, "notes.md").writeText("not a log")
        File(dir, "sub").mkdirs()
        BleLogArchive.build(BleLogStore(dir).files(), target())

        ZipFile(target()).use { zip ->
            val all = zip.entries().asSequence().toList()
            assertEquals(
                "no README, no listing, no folders",
                files.map { it.name }.sorted(),
                all.map { it.name }.sorted(),
            )
            for (entry in all) {
                assertFalse(entry.isDirectory)
                assertFalse(
                    "a plain name, no path: ${entry.name}",
                    entry.name.contains('/') || entry.name.contains('\\'),
                )
                assertNull("no comments", entry.comment)
                assertNull("no extra fields", entry.extra)
                val original = files.single { it.name == entry.name }
                assertEquals(original.readText(), zip.getInputStream(entry).use { String(it.readBytes()) })
            }
            assertNull("no archive comment", zip.comment)
        }
        // Nothing resembling a hardware address or a path of the tablet anywhere in the archive's bytes.
        val raw = String(target().readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(dir.absolutePath))
        assertFalse(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}").containsMatchIn(raw))
    }

    @Test
    fun `no logs makes an empty result and an archive with no entries`() {
        val result = BleLogArchive.build(emptyList(), target())
        assertEquals(0, result.included)
        assertEquals(0, result.leftOut)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `an empty file has nothing to send and is left out without being counted`() {
        val files = logs()
        files[0].writeText("")
        val result = BleLogArchive.build(files, target())
        assertEquals(3, result.included)
        assertEquals(0, result.skipped)
        assertEquals(0, result.leftOut)
        assertFalse(entries(target()).contains(names[0]))
    }

    @Test
    fun `a file that cannot be read is skipped and counted and the others still go`() {
        val files = logs()
        // A directory under a log file's name: reading it fails like a damaged or locked file does.
        val broken = File(folder.root, "ble-20261119T070000Z-trainer.txt").also { it.mkdirs() }
        val withBroken = files.filter { it.name != broken.name } + broken
        val gone = files.first { it.name == names[1] }.also { it.delete() }

        val result = BleLogArchive.build(withBroken, target())
        assertEquals(2, result.included)
        assertEquals(2, result.skipped)
        assertEquals(0, result.leftOut)
        assertEquals(setOf(names[0], names[3]), entries(target()).toSet())
        assertFalse(entries(target()).contains(gone.name))
    }

    @Test
    fun `over the cap the oldest sessions are dropped first and counted`() {
        // Random text does not compress, so the cap is about the bytes themselves.
        val random = Random(7)
        val files =
            logs { name -> "# $name\n" + String(CharArray(30_000) { ('a' + random.nextInt(26)) }) }
        val oneFile = BleLogArchive.build(listOf(files[3]), target()).archiveBytes

        val result = BleLogArchive.build(files, target(), capBytes = oneFile * 2 + 200)
        assertEquals(2, result.included)
        assertEquals(2, result.leftOut)
        assertEquals(
            "the two newest sessions stay",
            listOf("ble-20261120T093000Z-click.txt", "ble-20261119T070000Z-trainer.txt"),
            entries(target()),
        )
        assertTrue("under the cap", result.archiveBytes <= oneFile * 2 + 200)
    }

    @Test
    fun `once one session does not fit no older one sneaks in`() {
        val random = Random(9)
        val dir = folder.newFolder("ble-logs")

        fun text(n: Int) = String(CharArray(n) { ('a' + random.nextInt(26)) })
        File(dir, names[0]).writeText(text(200)) // oldest and tiny
        File(dir, names[2]).writeText(text(40_000)) // too big for the cap
        File(dir, names[3]).writeText(text(2_000)) // newest, fits
        val result = BleLogArchive.build(BleLogStore(dir).files(), target(), capBytes = 3_500)
        assertEquals(1, result.included)
        assertEquals(2, result.leftOut)
        assertEquals(listOf(names[3]), entries(target()))
    }

    @Test
    fun `text compresses so the default cap is not reached by logs within the store's own limits`() {
        val dir = folder.newFolder("ble-logs")
        val line = "1250000000 N 2AD2 - 4400C409B400FA00\n"
        names.forEach { File(dir, it).writeText(line.repeat(40_000)) } // 4 x 1.4 MB
        val result = BleLogArchive.build(BleLogStore(dir).files(), target())
        assertEquals(4, result.included)
        assertEquals(0, result.leftOut)
        assertTrue(
            "a tenth of the text or less: ${result.archiveBytes}",
            result.archiveBytes < 4 * line.length * 40_000 / 10,
        )
    }

    @Test
    fun `an existing archive of the same name is replaced`() {
        val files = logs()
        BleLogArchive.build(files, target())
        BleLogArchive.build(files.take(1), target())
        assertEquals(1, entries(target()).size)
    }
}

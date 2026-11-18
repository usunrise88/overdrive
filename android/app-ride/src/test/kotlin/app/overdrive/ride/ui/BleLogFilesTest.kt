package app.overdrive.ride.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.overdrive.ble.log.BleLogSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Sharing the raw Bluetooth logs (ADR 0010): one chooser over the log files, readable by the receiver and only those
 * files, named by date and role; nothing leaves the tablet by itself. A plain Application, as in [RideFilesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BleLogFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logs = File(context.filesDir, "ble-logs")
    private val names =
        listOf("ble-20261118T180412Z-trainer.txt", "ble-20261118T180412Z-strap.txt", "ble-20261119T070000Z-trainer.txt")

    @Before
    fun putLogsInTheFolder() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        logs.mkdirs()
        for (name in names) File(logs, name).writeText("# overdrive-ble-session 1\n# model D500\n")
    }

    @After
    fun cleanUp() {
        logs.deleteRecursively()
        File(context.filesDir, "elsewhere.txt").delete()
    }

    private fun files() = names.map { File(logs, it) }

    private fun sendIntent(chooser: Intent): Intent =
        @Suppress("DEPRECATION")
        chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Test
    fun oneFileIsOfferedAsASingleSend() {
        val chooser = BleLogShare.chooser(context, listOf(files().first()))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = sendIntent(chooser)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("only reading is granted", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("app.overdrive.ride.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
    }

    @Test
    fun severalFilesAreOfferedTogetherAndEachOneIsInTheClip() {
        val send = sendIntent(BleLogShare.chooser(context, files()))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals("text/plain", send.type)
        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(3, uris.size)
        assertEquals(3, send.clipData!!.itemCount)
        assertEquals(uris, (0 until 3).map { send.clipData!!.getItemAt(it).uri })
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun theReceiverReadsTheFileAndSeesADateAndARoleAndNothingElse() {
        val send = sendIntent(BleLogShare.chooser(context, files()))

        @Suppress("DEPRECATION")
        val uris = send.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals(
            "# overdrive-ble-session 1\n# model D500\n",
            context.contentResolver
                .openInputStream(
                    uris[0],
                )!!
                .use {
                    String(it.readBytes())
                },
        )
        val shown =
            uris.map { uri ->
                context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        assertEquals(names, shown)
        for (name in shown) assertTrue(name, Regex("ble-\\d{8}T\\d{6}Z-(trainer|strap)\\.txt").matches(name))
        assertFalse(
            "no device name or address in what the receiver sees",
            uris.any {
                it.toString().contains(context.filesDir.absolutePath)
            },
        )
    }

    @Test
    fun aFileOutsideTheLogFolderOrTheRidesFolderIsRefused() {
        val outside = File(context.filesDir, "elsewhere.txt").also { it.writeText("x") }
        try {
            BleLogShare.chooser(context, listOf(files().first(), outside))
            fail("the provider must serve its two folders only")
        } catch (expected: IllegalArgumentException) {
            // shareOrExplain turns this into "The files could not be shared."
        }
    }

    @Test
    fun anEmptyListIsNotAChooser() {
        try {
            BleLogShare.chooser(context, emptyList())
            fail("there is nothing to share")
        } catch (expected: IllegalArgumentException) {
            // as it should
        }
    }

    @Test
    fun sharingWithNoLogsSaysSoAndOpensNothing() {
        assertEquals(
            "There are no logs yet. Ride once with the trainer connected.",
            shareOrExplain(context, emptyList()),
        )
        assertNull(shadowOf(context as Application).peekNextStartedActivity())
    }

    @Test
    fun sharingOpensTheChooserAndSaysNothingWhenItWorks() {
        // The screen calls it with its activity: a context that is not one needs a flag Android insists on.
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertNull(shareOrExplain(activity, files()))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun aFileTheProviderRefusesIsAPlainMessageNotACrash() {
        val outside = File(context.filesDir, "elsewhere.txt").also { it.writeText("x") }
        assertEquals("The files could not be shared.", shareOrExplain(context, listOf(outside)))
    }

    @Test
    fun theSummaryIsInPlainWords() {
        assertEquals("No logs yet", BleLogSummary.None.describe())
        assertEquals("1 file · 312 B", BleLogSummary(1, 312).describe())
        assertEquals("4 files · 120 KB", BleLogSummary(4, 120 * 1024L + 17).describe())
        assertEquals("7 files · 2.5 MB", BleLogSummary(7, (2.5 * 1024 * 1024).toLong()).describe())
    }
}

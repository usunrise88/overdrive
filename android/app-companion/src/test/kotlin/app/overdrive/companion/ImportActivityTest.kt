package app.overdrive.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import app.overdrive.companion.share.ShareIntents
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The share target (spec §11.8 path A, ADR 0028) from the intent in: an `ACTION_SEND` with a content URI to a synthetic FIT file, an
 * `ACTION_VIEW`, a file that is not FIT, a file that is too large (as its provider says, and as it really is), a scheme that is not
 * `content`, and the manifest's filters. The app is the real [CompanionApp] and the file comes through a real content resolver from a
 * provider that behaves like OnelapFit's; the service is the loopback stand-in. Every wait is for something on screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = CompanionApp::class)
class ImportActivityTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container get() = (context as CompanionApp).container
    private val service = LocalService()
    private var scenario: ActivityScenario<ImportActivity>? = null

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        Robolectric.setupContentProvider(TestFitProvider::class.java, TestFitProvider.AUTHORITY)
    }

    @After
    fun tearDown() {
        scenario?.close()
        container.close()
        service.close()
        TestFitProvider.clear()
        File(context.filesDir, "cache").deleteRecursively()
    }

    private fun connect() {
        container.updateService(service.baseUrl, service.token)
        await("the settings are stored") { container.settings.value?.isConfigured == true }
    }

    private fun send(
        uri: Uri,
        type: String?,
    ): Intent =
        Intent(context, ImportActivity::class.java)
            .setAction(Intent.ACTION_SEND)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .also { if (type != null) it.type = type }
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch(intent)
    }

    private fun showsText(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitForText(text: String) = rule.waitUntil(60_000) { showsText(text) }

    private fun outbox(): List<File> =
        File(context.filesDir, "cache/fit-outbox")
            .listFiles { f ->
                f.isFile && f.name.endsWith(".fit")
            }.orEmpty()
            .toList()

    // region a FIT file in

    @Test
    fun `an ACTION_SEND with a content URI to a FIT file is imported and the screen says what it is`() {
        connect()
        val fit = SyntheticFit.bytes(1, 4000)
        launch(send(TestFitProvider.put("a", "Ride_2027-03-14.fit", fit), "application/octet-stream"))
        waitForText("Imported")
        assertTrue(showsText("31.4 km"))
        assertTrue(showsText("average heart rate 142 bpm"))
        assertTrue(showsText("load 74"))
        assertEquals(1, service.fitFiles.size)
        assertArrayEquals(fit, service.fitFiles.values.single())
        await("nothing waits on the phone") { outbox().isEmpty() && container.fitStatus.value.waiting == 0 }
        rule.onNodeWithText("Done").performClick()
        var finishing = false
        scenario?.onActivity { finishing = it.isFinishing }
        assertTrue("Done closes the screen", finishing)
    }

    @Test
    fun `an ACTION_VIEW of a FIT type is imported whatever the name is`() {
        connect()
        val fit = SyntheticFit.bytes(2, 2000)
        val intent =
            Intent(context, ImportActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setDataAndType(TestFitProvider.put("b", "export (3)", fit), "application/vnd.ant.fit")
        launch(intent)
        waitForText("Imported")
        assertArrayEquals(fit, service.fitFiles.values.single())
    }

    @Test
    fun `the same file shared twice is told as already imported`() {
        connect()
        val uri = TestFitProvider.put("c", "ride.fit", SyntheticFit.bytes(3))
        launch(send(uri, "application/octet-stream"))
        waitForText("Imported")
        scenario?.close()
        launch(send(uri, "*/*"))
        waitForText("Already imported")
        assertEquals(1, service.fitFiles.size)
    }

    // region what is not a FIT file

    @Test
    fun `a file whose name is not FIT is refused before a byte is read, and nothing is kept or sent`() {
        connect()
        launch(send(TestFitProvider.put("d", "holiday.jpg", SyntheticFit.bytes(4)), "application/octet-stream"))
        waitForText("Could not import")
        assertTrue(showsText("not a valid FIT file"))
        assertTrue(service.fitBodies.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a file named FIT whose bytes are not is refused on the phone with the same words`() {
        connect()
        launch(send(TestFitProvider.put("e", "notes.fit", "a diary, not a ride".repeat(8).encodeToByteArray()), "*/*"))
        waitForText("Could not import")
        assertTrue(showsText("not a valid FIT file"))
        assertTrue(service.fitBodies.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a zip archive is told as one`() {
        connect()
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)
        launch(send(TestFitProvider.put("f", "ride.fit", zip), "application/octet-stream"))
        waitForText("zip archive")
        assertTrue(service.fitBodies.isEmpty())
    }

    @Test
    fun `a file larger than 16 MiB as its provider says is refused without being read`() {
        connect()
        // Only the size is reported: the bytes are a few, so a read would not hit the limit and the test would see a different answer.
        launch(
            send(
                TestFitProvider.put("g", "huge.fit", SyntheticFit.bytes(5), reportedSize = 20L * 1024 * 1024),
                "application/octet-stream",
            ),
        )
        waitForText("larger than 16 MB")
        assertTrue(service.fitBodies.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a provider that lies about its size costs no more than the limit, and the file is too large`() {
        connect()
        val over = SyntheticFit.bytes(6, 16 * 1024 * 1024 + 1)
        launch(send(TestFitProvider.put("h", "liar.fit", over, reportedSize = null), "application/octet-stream"))
        waitForText("larger than 16 MB")
        assertTrue(service.fitBodies.isEmpty())
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a file exactly 16 MiB is read whole and sent`() {
        connect()
        val exact = SyntheticFit.bytes(7, 16 * 1024 * 1024)
        launch(send(TestFitProvider.put("i", "big.fit", exact), "application/octet-stream"))
        waitForText("Imported")
        assertEquals(exact.size, service.fitFiles.values.single().size)
    }

    @Test
    fun `only content is read, and a path is not a file another app shares`() {
        connect()
        val own = File(context.filesDir, "settings-secret.fit").also { it.writeBytes(SyntheticFit.bytes(8)) }
        launch(send(Uri.fromFile(own), "application/octet-stream"))
        waitForText("Could not read the file")
        assertTrue(service.fitBodies.isEmpty())
    }

    @Test
    fun `a provider that fails in its own way is a file that cannot be read, and the app goes on`() {
        connect()
        val broken = IllegalStateException("the provider's own bug, with SENTINEL-177 in it")
        launch(
            send(
                TestFitProvider.put("m", "ride.fit", SyntheticFit.bytes(12), failure = broken),
                "application/octet-stream",
            ),
        )
        waitForText("Could not read the file")
        assertTrue("what failed is not said", !showsText("SENTINEL-177"))
        assertTrue(service.fitBodies.isEmpty())
    }

    // region the service cannot be reached

    @Test
    fun `with no service set up the file is kept and the screen says to connect`() {
        launch(send(TestFitProvider.put("j", "ride.fit", SyntheticFit.bytes(9)), "application/octet-stream"))
        waitForText("Saved for later")
        assertTrue(showsText("not connected to your service"))
        await("it is on disk") { outbox().size == 1 }
        assertTrue(service.fitBodies.isEmpty())
    }

    @Test
    fun `a service that is down keeps the file and says so in plain words`() {
        connect()
        service.down = true
        launch(send(TestFitProvider.put("k", "ride.fit", SyntheticFit.bytes(10)), "application/octet-stream"))
        waitForText("Saved for later")
        assertTrue(showsText("The service had a problem"))
        await("it is on disk") { outbox().size == 1 }
    }

    @Test
    fun `a refused token keeps the file and offers Settings`() {
        connect()
        service.rejectToken = true
        launch(send(TestFitProvider.put("l", "ride.fit", SyntheticFit.bytes(11)), "application/octet-stream"))
        waitForText("Saved, not sent")
        assertTrue(showsText("did not accept the token"))
        assertNotNull(rule.onNodeWithText("Settings").fetchSemanticsNode())
        await("it is on disk") { outbox().size == 1 }
    }

    // region reading the intent

    @Test
    fun `the file is the stream of a send, or its clip, or the data of a view, and nothing else is`() {
        val uri = Uri.parse("content://${TestFitProvider.AUTHORITY}/x")
        val stream = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).setType("application/octet-stream")
        assertEquals(uri, ShareIntents.sharedFileOf(stream)?.uri)
        assertEquals("application/octet-stream", ShareIntents.sharedFileOf(stream)?.mimeType)
        val clip = Intent(Intent.ACTION_SEND).setType("*/*")
        clip.clipData = android.content.ClipData.newRawUri("file", uri)
        assertEquals(uri, ShareIntents.sharedFileOf(clip)?.uri)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.ant.fit")
        assertEquals(uri, ShareIntents.sharedFileOf(view)?.uri)
        assertNull(
            ShareIntents.sharedFileOf(
                Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello"),
            ),
        )
        assertNull(ShareIntents.sharedFileOf(Intent(Intent.ACTION_MAIN)))
        assertNull(ShareIntents.sharedFileOf(Intent(Intent.ACTION_SEND_MULTIPLE).putExtra(Intent.EXTRA_STREAM, uri)))
        assertNull(ShareIntents.sharedFileOf(null))
    }

    @Test
    fun `the manifest takes a FIT type, a generic type and any type, shared or opened, and only for content`() {
        val packages = context.packageManager

        fun resolves(intent: Intent) =
            packages
                .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .any { it.activityInfo.name == ImportActivity::class.java.name }
        val uri = Uri.parse("content://${TestFitProvider.AUTHORITY}/x")
        for (type in listOf("application/vnd.ant.fit", "application/x-fit", "application/octet-stream", "*/*")) {
            assertTrue("send $type", resolves(Intent(Intent.ACTION_SEND).setType(type)))
            assertTrue("view $type", resolves(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)))
        }
        assertTrue(
            "a view of a path is not taken",
            !resolves(
                Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("file:///sdcard/r.fit"), "application/vnd.ant.fit"),
            ),
        )
    }

    @Test
    fun `opened with nothing to import it says so, and a share target holds no state of its own`() {
        launch(Intent(context, ImportActivity::class.java))
        waitForText("Nothing to import")
    }
}

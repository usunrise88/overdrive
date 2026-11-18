package app.overdrive.ride.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.overdrive.ride.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * Sharing a FIT file (ADR 0008): one `content://` URI for one file under `files/rides/`, served by a provider that nothing
 * else can reach, and an app that has no network permission and is not backed up.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application, not OverdriveApp: OverdriveApp builds the container at start, whose ride library scans
// files/rides/ in the background and deletes a `.fit` that has no record or log, so under load it could remove
// this test's file between its creation and its reading. The test is about the provider and the manifest only.
@Config(sdk = [35], application = android.app.Application::class)
class RideFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val rides = File(context.filesDir, "rides")
    private val name = "2026-11-18 18-04 Sweet spot 3×10.fit"
    private val bytes = byteArrayOf(14, 0x20, 0, 0, 1, 2, 3, 4, 0x2E, 0x46, 0x49, 0x54)

    @Before
    fun putAFileInTheRidesFolder() {
        // FileProvider remembers the folders of the first test's app in a static map, and every Robolectric test has
        // a new files folder: forget them, as a new process would.
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        rides.mkdirs()
        File(rides, "ride-1.fit").writeBytes(bytes)
    }

    @After
    fun cleanUp() {
        rides.deleteRecursively()
        File(context.filesDir, "elsewhere.fit").delete()
    }

    private fun sendIntent(chooser: Intent): Intent =
        @Suppress("DEPRECATION")
        chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    @Test
    fun theChooserOffersOneFitFileForReadingOnly() {
        val chooser = RideShare.chooser(context, File(rides, "ride-1.fit"), name)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = sendIntent(chooser)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(FIT_MIME_TYPE, send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("only reading is granted", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("app.overdrive.ride.files", uri.authority)
        assertEquals(name, send.getStringExtra(Intent.EXTRA_TITLE))
        val clip: ClipData = send.clipData!!
        assertEquals(uri, clip.getItemAt(0).uri)
    }

    @Test
    fun theUriReadsTheFileAndReportsTheFriendlyName() {
        val send = sendIntent(RideShare.chooser(context, File(rides, "ride-1.fit"), name))

        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertTrue(bytes.contentEquals(context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }))
        context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(name, cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
            assertEquals(bytes.size.toLong(), cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
    }

    @Test
    fun theUriNeverShowsTheRideIdOrTheFolderPath() {
        val send = sendIntent(RideShare.chooser(context, File(rides, "ride-1.fit"), name))

        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertFalse(uri.toString().contains(context.filesDir.absolutePath))
    }

    @Test
    fun aFileOutsideTheRidesFolderIsRefused() {
        val outside = File(context.filesDir, "elsewhere.fit").also { it.writeBytes(bytes) }
        RideShare.chooser(context, File(rides, "ride-1.fit"), name) // the same call works inside the folder
        try {
            RideShare.chooser(context, outside, name)
            fail("the provider must serve the rides folder only")
        } catch (expected: IllegalArgumentException) {
            // The activity turns this into "The file could not be shared".
        }
    }

    @Test
    fun theProviderIsNotExportedAndGrantsUrisOnly() {
        val provider = context.packageManager.resolveContentProvider(RideShare.authority(context), 0)
        assertNotNull(provider)
        assertEquals("androidx.core.content.FileProvider", provider!!.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
    }

    @Test
    fun theProviderServesTheRidesAndTheBluetoothLogFoldersAndNothingElse() {
        val xml = context.resources.getXml(R.xml.file_paths)
        val paths = mutableListOf<Triple<String, String?, String?>>()
        while (xml.next() != XmlPullParser.END_DOCUMENT) {
            if (xml.eventType == XmlPullParser.START_TAG) {
                paths += Triple(xml.name, xml.getAttributeValue(null, "name"), xml.getAttributeValue(null, "path"))
            }
        }
        assertEquals(
            listOf(
                Triple("paths", null, null),
                Triple("files-path", "rides", "rides/"),
                Triple("files-path", "ble_logs", "ble-logs/"),
            ),
            paths,
        )
    }

    @Test
    fun theAppAsksForNoNetworkAndIsNotBackedUp() {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toList()
        assertFalse(requested.contains(Manifest.permission.INTERNET))
        assertFalse(requested.contains(Manifest.permission.ACCESS_NETWORK_STATE))
        // Bluetooth needs the Nearby-devices permissions only: a location permission would be a worse question to ask.
        assertFalse(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertFalse(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertTrue(requested.contains(Manifest.permission.BLUETOOTH_SCAN))
        assertTrue(requested.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertEquals(0, context.applicationInfo.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }
}

package app.overdrive.ride.devices

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.overdrive.core.ble.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The remembered trainer and strap (ADR 0010): what survives a restart, and how a damaged file reads. */
class DeviceStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val trainer = RememberedDevice(DeviceId("trainer-a"), "D500")
    private val strap = RememberedDevice(DeviceId("strap-a"), "HR7")

    private fun memory() = DeviceStore(InMemoryPreferences())

    private fun <T> withFileStore(
        name: String = "devices",
        block: suspend (DataStore<Preferences>) -> T,
    ): T {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) { File(folder.root, "$name.preferences_pb") }
            return runBlocking { block(store) }
        } finally {
            // Wait until the store's own coroutine has finished: DataStore frees the file for the next store only then,
            // and a plain cancel() returns before that, so a second store over the same file ("the next launch") could
            // find it still taken and fail with "multiple DataStores active for the same file".
            runBlocking { job.cancelAndJoin() }
        }
    }

    @Test
    fun nothingIsRememberedAtFirst() =
        runBlocking {
            assertEquals(RememberedDevices(), memory().read())
        }

    @Test
    fun aTrainerAndAStrapAreRememberedSeparately() =
        runBlocking {
            val store = memory()
            store.remember(DeviceRole.Trainer, trainer)
            store.remember(DeviceRole.Strap, strap)
            assertEquals(RememberedDevices(trainer, strap), store.read())
            assertEquals(trainer, store.read()[DeviceRole.Trainer])
            assertEquals(strap, store.read()[DeviceRole.Strap])
        }

    @Test
    fun rememberingAnotherDeviceReplacesTheOldOneOfThatRoleOnly() =
        runBlocking {
            val store = memory()
            store.remember(DeviceRole.Trainer, trainer)
            store.remember(DeviceRole.Strap, strap)
            val other = RememberedDevice(DeviceId("trainer-b"), "Other D500")
            store.remember(DeviceRole.Trainer, other)
            assertEquals(RememberedDevices(other, strap), store.read())
        }

    @Test
    fun forgettingRemovesOneRoleAndLeavesTheOther() =
        runBlocking {
            val store = memory()
            store.remember(DeviceRole.Trainer, trainer)
            store.remember(DeviceRole.Strap, strap)
            store.forget(DeviceRole.Trainer)
            assertEquals(RememberedDevices(null, strap), store.read())
            store.forget(DeviceRole.Strap)
            assertEquals(RememberedDevices(), store.read())
            store.forget(DeviceRole.Strap) // nothing there: not an error
            assertEquals(RememberedDevices(), store.read())
        }

    @Test
    fun theNameIsCleanedWhenItIsStoredAndWhenItIsRead() =
        runBlocking {
            val store = memory()
            store.remember(DeviceRole.Trainer, RememberedDevice(DeviceId("t"), "  D500 \u0007  Pro\n"))
            assertEquals("D500 Pro", store.read().trainer?.name)
            store.remember(DeviceRole.Strap, RememberedDevice(DeviceId("s"), ""))
            assertEquals(DeviceRole.Strap.fallbackName, store.read().strap?.name)
        }

    @Test
    fun aBlankIdIsNothingPairedAndAMissingNameIsTheRolesName() =
        runBlocking {
            val prefs = InMemoryPreferences()
            prefs.edit {
                it[stringPreferencesKey("trainer_id")] = "   "
                it[stringPreferencesKey("trainer_name")] = "D500"
                it[stringPreferencesKey("strap_id")] = "strap-a"
            }
            val read = DeviceStore(prefs).read()
            assertNull(read.trainer)
            assertEquals(RememberedDevice(DeviceId("strap-a"), DeviceRole.Strap.fallbackName), read.strap)
        }

    @Test
    fun whatIsRememberedSurvivesARestart() {
        withFileStore { first -> DeviceStore(first).remember(DeviceRole.Trainer, trainer) }
        // A new DataStore over the same file is what the next launch has.
        val read = withFileStore { second -> DeviceStore(second).read() }
        assertEquals(RememberedDevices(trainer, null), read)
    }

    @Test
    fun anUnreadableFileIsTheSameAsAnEmptyOne() {
        File(folder.root, "devices.preferences_pb").writeBytes(ByteArray(64) { (it * 37 + 11).toByte() })
        val read = withFileStore { DeviceStore(it).read() }
        assertEquals(RememberedDevices(), read)
    }

    @Test
    fun theAddressIsStoredOnlyInThePrivateFileAndPrintsAsAFingerprint() {
        val address = DeviceId("AA:BB:CC:DD:EE:FF")
        val remembered = RememberedDevice(address, "D500")
        assertEquals(false, remembered.toString().contains("AA:BB:CC:DD:EE:FF"))
        assertEquals(false, address.toString().contains("AA:BB"))
    }
}

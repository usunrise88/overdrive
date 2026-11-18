package app.overdrive.ble.log

import app.overdrive.ble.AndroidGattTransportFactory
import app.overdrive.ble.gatt.BleLog
import app.overdrive.ble.gatt.CorePlatform
import app.overdrive.ble.gatt.GattTimings
import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.FtmsUuids
import app.overdrive.core.ble.GattCharacteristicInfo
import app.overdrive.core.ble.GattProperty
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.fake.FakeBle
import app.overdrive.core.ble.fake.FakeFtmsTrainer
import app.overdrive.core.ble.fake.FakePeripheral
import app.overdrive.core.ble.link.BackoffPolicy
import app.overdrive.core.ble.recording.BleDirection
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.device.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A peripheral that offers the Device Information serial number, so the redaction has something to redact. */
private class SerialPeripheral : FakePeripheral("D500", setOf(FtmsUuids.SERVICE)) {
    override val characteristics =
        listOf(GattCharacteristicInfo(GattUuid.short(0x180A), IdentityUuids.SERIAL_NUMBER, setOf(GattProperty.READ)))

    override fun onRead(characteristic: GattUuid): ByteArray? = "SN-00123456".toByteArray()
}

/** The raw Bluetooth logs: where they are, what is in them, what is not, how many are kept. */
@OptIn(ExperimentalCoroutinesApi::class)
class BleLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val executors = mutableListOf<java.util.concurrent.ExecutorService>()

    @After
    fun stopThreads() {
        executors.forEach { it.shutdownNow() }
    }

    private fun writeScope(): CoroutineScope {
        val executor = Executors.newSingleThreadExecutor().also { executors += it }
        return CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher())
    }

    private val directory get() = File(folder.root, "ble-logs")

    // region the store

    @Test
    fun `a file is named by its date and role and by nothing that identifies a device`() {
        val store = BleLogStore(directory, clock = { 1_700_000_000_000L })
        val trainer = store.newFile(BleLogRole.TRAINER)
        val strap = store.newFile(BleLogRole.STRAP)
        assertEquals("ble-20231114T221320Z-trainer.txt", trainer.name)
        assertEquals("ble-20231114T221320Z-strap.txt", strap.name)
        assertEquals(directory, trainer.parentFile)
    }

    @Test
    fun `two sessions in the same second get distinct names that still sort by time`() {
        val store = BleLogStore(directory, clock = { 1_700_000_000_000L })
        val names =
            (1..3).map {
                store.newFile(BleLogRole.TRAINER).also { it.writeText("x") }.name
            }
        assertEquals(names.sorted(), names)
        assertEquals(3, names.toSet().size)
        assertEquals(names, store.files().map { it.name })
    }

    @Test
    fun `the oldest files go when there are too many or they are too big, and the one being written stays`() {
        var now = 1_700_000_000_000L
        val store =
            BleLogStore(directory, { now }, BleLogLimits(maxFiles = 3, maxTotalBytes = 100, maxFileBytes = 50))
        val made = mutableListOf<File>()
        repeat(5) {
            now += 60_000
            made += store.newFile(BleLogRole.TRAINER).also { it.writeText("0123456789") }
        }
        assertEquals(
            "three files kept, room made for the next",
            made.takeLast(3).map {
                it.name
            },
            store.files().map { it.name },
        )

        // By size: 5 files of 30 bytes against a limit of 100 leaves three.
        now += 60_000
        val big =
            BleLogStore(
                File(folder.root, "big"),
                { now },
                BleLogLimits(maxFiles = 50, maxTotalBytes = 100, maxFileBytes = 50),
            )
        repeat(5) {
            now += 60_000
            big.newFile(BleLogRole.STRAP).writeText("x".repeat(30))
        }
        now += 60_000
        val current = big.newFile(BleLogRole.STRAP)
        current.writeText("x".repeat(30))
        assertTrue(big.summary().bytes <= 100)
        assertTrue("the newest file is never the one pruned", current.exists())
        assertEquals(BleLogSummary(big.files().size, big.summary().bytes), big.summary())
    }

    @Test
    fun `summary counts only log files`() {
        val store = BleLogStore(directory)
        directory.mkdirs()
        File(directory, "notes.md").writeText("not a log")
        assertEquals(BleLogSummary.None, store.summary())
        store.newFile(BleLogRole.TRAINER).writeText("1234")
        assertEquals(BleLogSummary(1, 4), store.summary())
    }

    // endregion

    // region a connection session becomes a file

    private class Rig(
        val test: TestScope,
        val dir: File,
        val writer: CoroutineScope,
        val enabled: () -> Boolean = { true },
        val limits: BleLogLimits = BleLogLimits(),
        peripheral: FakePeripheral = FakeFtmsTrainer(),
        val deviceId: DeviceId = DeviceId("log-trainer"),
    ) {
        private val clock = { test.testScheduler.currentTime * 1_000_000 }
        val world = FakeBle(test.backgroundScope, clock).also { it.add(deviceId, peripheral) }
        val store = BleLogStore(dir, { 1_700_000_000_000L + test.testScheduler.currentTime }, limits)
        val logger =
            BleSessionLogger(store, BleLogRole.TRAINER, test.backgroundScope, writer, clock, { "D500" }, enabled)
        val transports =
            logger.wrap(
                AndroidGattTransportFactory(
                    CorePlatform(world, test.backgroundScope),
                    test.backgroundScope,
                    clock,
                    GattTimings(),
                    BleLog.None,
                ),
            )

        fun trainer() =
            FtmsTrainerDevice(
                test.backgroundScope,
                deviceId,
                transports,
                world,
                "D500",
                reconnect = BackoffPolicy.EXACT,
                timeSource = test.testTimeSource,
            )

        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
    }

    /** Waits for the writer thread, which the test scheduler does not control. */
    private fun settle(vararg writers: CoroutineScope) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            Thread.sleep(20)
            val busy =
                writers.any {
                    it.coroutineContext[kotlinx.coroutines.Job]?.children?.any { child -> child.isActive } ==
                        true
                }
            if (!busy) return
        }
    }

    private fun lines(file: File): List<String> = file.readLines()

    @Test
    fun `a connection session of the trainer is one redacted file with the whole conversation in it`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(5_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            trainer.setTargetPower(160)
            rig.advance(3_000)
            trainer.close()
            rig.advance(3_000)
            settle(writer)

            val files = rig.store.files()
            assertEquals(1, files.size)
            assertTrue(files.single().name.endsWith("-trainer.txt"))
            val parsed = BleSessionFormat.parseWithDiagnostics(files.single().readText())
            assertEquals(1, parsed.version)
            assertEquals("D500", parsed.session.model)
            assertEquals(0, parsed.skippedLines)
            val directions = parsed.session.events.map { it.direction }.toSet()
            assertTrue(
                directions.containsAll(
                    listOf(
                        BleDirection.STATE,
                        BleDirection.DISCOVERY,
                        BleDirection.CCCD,
                        BleDirection.WRITE,
                        BleDirection.NOTIFICATION,
                    ),
                ),
            )
            val states = parsed.session.events.filter { it.direction == BleDirection.STATE }.map { it.data }
            assertEquals("connecting", states.first())
            assertTrue(states.contains("connected"))
            assertEquals("closed", states.last())
            // A 160 W target is `05 A0 00` on the Control Point.
            assertTrue(parsed.session.events.any { it.direction == BleDirection.WRITE && it.data == "05A000" })
            // Redacted by core's policy: the file names no address and no device.
            val text = files.single().readText()
            assertFalse(text.contains("log-trainer"))
            assertFalse(text.contains("DeviceId"))
        }

    @Test
    fun `identity values are replaced and never reach the file`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer, peripheral = SerialPeripheral())
            val transport = rig.transports.open(rig.deviceId)
            transport.connect()
            val services = (transport.discoverServices() as app.overdrive.core.ble.GattResult.Ok).value
            val serial = services.characteristics.single { it.characteristic == IdentityUuids.SERIAL_NUMBER }
            transport.read(serial)
            rig.advance(1_000)
            transport.close()
            rig.advance(3_000)
            settle(writer)
            val text = rig.store.files().single().readText()
            assertTrue(text.lines().any { it.contains(" R 2A25 0 *") })
            assertFalse("the serial number's bytes", text.contains("534E2D3030313233343536"))
            assertFalse(text.contains("SN-00123456"))
        }

    @Test
    fun `failed attempts and the connection after them share a file, and the next connection starts a new one`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer)
            (rig.world.peripheral(rig.deviceId) as FakeFtmsTrainer).faults.failNextConnects(2)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(30_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            rig.world.peripheral(rig.deviceId).dropLink()
            rig.advance(30_000)
            assertEquals(ConnectionState.CONNECTED, trainer.connection.value)
            trainer.close()
            rig.advance(3_000)
            settle(writer)

            val files = rig.store.files()
            assertEquals("the first connection (after two failures) and the second one: $files", 2, files.size)
            val first = BleSessionFormat.parse(files[0].readText())
            val connects = first.events.count { it.direction == BleDirection.STATE && it.data == "connecting" }
            assertEquals("two failed attempts and the one that worked", 3, connects)
            assertEquals(1, BleSessionFormat.parse(files[1].readText()).events.count { it.data == "connecting" })
        }

    @Test
    fun `attempts that never connect keep appending to one file`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer)
            val fake = rig.world.peripheral(rig.deviceId)
            fake.powered = false
            fake.absentFailsAfter = kotlin.time.Duration.parse("1s")
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(120_000)
            trainer.close()
            rig.advance(3_000)
            settle(writer)
            val files = rig.store.files()
            assertEquals(1, files.size)
            assertTrue(BleSessionFormat.parse(files.single().readText()).events.count { it.data == "connecting" } >= 5)
        }

    @Test
    fun `with the setting off nothing is written, and turning it on starts with the next connection`() =
        runTest {
            val writer = writeScope()
            var on = false
            val rig = Rig(this, directory, writer, enabled = { on })
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(5_000)
            settle(writer)
            assertEquals(0, rig.store.files().size)
            on = true
            rig.world.peripheral(rig.deviceId).dropLink()
            rig.advance(30_000)
            trainer.close()
            rig.advance(3_000)
            settle(writer)
            assertEquals(1, rig.store.files().size)
        }

    @Test
    fun `a file stops growing at its limit and the connection goes on regardless`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer, limits = BleLogLimits(maxFileBytes = 600))
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(60_000)
            assertEquals("the ride did not notice", ConnectionState.CONNECTED, trainer.connection.value)
            trainer.close()
            rig.advance(3_000)
            settle(writer)
            val file = rig.store.files().first()
            assertTrue("${file.length()} bytes", file.length() <= 600)
            assertTrue(file.length() > 300)
            assertEquals("never a half line", 0, BleSessionFormat.parseWithDiagnostics(file.readText()).skippedLines)
        }

    @Test
    fun `replacing the device starts a new file, so two devices never share one`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer)
            val other = DeviceId("another-trainer")
            rig.world.add(other, FakeFtmsTrainer())
            val first = rig.transports.open(rig.deviceId)
            first.connect()
            val second = rig.transports.open(other)
            second.connect()
            rig.advance(1_000)
            first.close()
            second.close()
            rig.advance(3_000)
            settle(writer)
            assertEquals(2, rig.store.files().size)
        }

    @Test
    fun `a stalled disk delays nothing, the radio and the adapters never wait for the writer`() {
        val release = CountDownLatch(1)
        val blocker = Executors.newSingleThreadExecutor().also { executors += it }
        blocker.execute { release.await() }
        val writer = CoroutineScope(SupervisorJob() + blocker.asCoroutineDispatcher())
        runBlocking {
            runTest {
                val rig = Rig(this, directory, writer)
                val trainer = rig.trainer()
                trainer.start()
                rig.advance(5_000)
                assertEquals("connected while the disk is stuck", ConnectionState.CONNECTED, trainer.connection.value)
                trainer.setTargetPower(150)
                rig.advance(5_000)
                trainer.close()
                rig.advance(3_000)
                assertTrue(
                    "nothing was written yet",
                    rig.store.files().isEmpty() || rig.store.files().single().length() == 0L,
                )
            }
        }
        release.countDown()
        settle(writer)
        val file = BleLogStore(directory).files().single()
        assertTrue(
            "everything queued is written once the disk is back",
            BleSessionFormat.parse(file.readText()).events.size > 10,
        )
        writer.cancel()
    }

    @Test
    fun `Bluetooth going off ends the session cleanly`() =
        runTest {
            val writer = writeScope()
            val rig = Rig(this, directory, writer)
            val trainer = rig.trainer()
            trainer.start()
            rig.advance(5_000)
            rig.world.setAvailability(BleAvailability.BLUETOOTH_OFF)
            rig.advance(1_000)
            trainer.close()
            rig.advance(3_000)
            settle(writer)
            val events = BleSessionFormat.parse(rig.store.files().single().readText()).events
            assertEquals("closed", events.last().data)
        }

    // endregion
}

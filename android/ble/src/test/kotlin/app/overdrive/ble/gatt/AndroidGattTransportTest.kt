package app.overdrive.ble.gatt

import app.overdrive.ble.AndroidGattTransportFactory
import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.GattFailure
import app.overdrive.core.ble.GattFailureKind
import app.overdrive.core.ble.GattLinkState
import app.overdrive.core.ble.GattNotification
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.ValueUpdate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The logic of the Android transport against a hand-driven platform: what each callback does, in which order, and what
 * the caller sees (docs/adr/0010-android-ble-and-pairing.md). No radio, no Android: the same contract as core's fake.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidGattTransportTest {
    private val log = mutableListOf<String>()

    private fun TestScope.transport(
        platform: ScriptedPlatform,
        timings: GattTimings = GattTimings(),
        mode: ConnectMode = ConnectMode.ACTIVE,
    ) = AndroidGattTransport(
        opener = platform,
        device = TEST_DEVICE,
        scope = backgroundScope,
        nanoClock = { testScheduler.currentTime * 1_000_000 },
        timings = timings,
        log = { log += it },
        mode = mode,
    )

    /** A transport that is connected: `connect` answered with the platform's success. */
    private fun TestScope.connected(
        platform: ScriptedPlatform = ScriptedPlatform(),
        timings: GattTimings = GattTimings(),
    ): AndroidGattTransport {
        val transport = transport(platform, timings)
        val result = async { transport.connect() }
        runCurrent()
        platform.connected()
        runCurrent()
        assertEquals(GattResult.Ok(Unit), result.getCompleted())
        return transport
    }

    private fun failure(result: GattResult<*>): GattFailure = (result as GattResult.Failed).failure

    // region connect

    @Test
    fun `connect goes idle, connecting, connected and returns when the platform says so`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform)
            assertEquals(GattLinkState.Idle, transport.state.value)

            val result = async { transport.connect() }
            runCurrent()
            assertEquals(GattLinkState.Connecting, transport.state.value)
            assertFalse(result.isCompleted)
            assertEquals(1, platform.opened)

            platform.connected()
            runCurrent()
            assertEquals(GattResult.Ok(Unit), result.await())
            assertEquals(GattLinkState.Connected, transport.state.value)
        }

    @Test
    fun `a failed connect with status 133 is final, reports the status and releases the platform object`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform)
            val result = async { transport.connect() }
            runCurrent()
            platform.dropped(status = 133)
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.STATUS, 133), failure(result.await()))
            assertTrue(failure(result.await()).isGattError)
            assertEquals(GattLinkState.Disconnected(133), transport.state.value)
            // The platform said the link is down, so the object is released at once, without a disconnect first.
            assertEquals(listOf("close"), platform.gatt.calls)
            // And it is dead: it cannot connect again, which is how a stale BluetoothGatt is never reused.
            assertEquals(GattFailureKind.CLOSED, failure(transport.connect()).kind)
            assertEquals(1, platform.opened)
        }

    @Test
    fun `a callback that arrives before connectGatt returns is not lost`() =
        runTest {
            val platform = ScriptedPlatform()
            platform.eventsDuringOpen += PlatformEvent.ConnectionChanged(status = 133, connected = false)
            val transport = transport(platform)
            val result = transport.connect()
            assertEquals(GattFailure(GattFailureKind.STATUS, 133), failure(result))
            runCurrent()
            assertEquals(GattLinkState.Disconnected(133), transport.state.value)
            assertEquals(
                "the object connectGatt returned late is released",
                listOf("disconnect", "close"),
                platform.gatt.calls,
            )
        }

    @Test
    fun `a connect without the permission is unavailable, not a crash`() =
        runTest {
            val platform = ScriptedPlatform().apply { openThrows = SecurityException("needs BLUETOOTH_CONNECT") }
            val transport = transport(platform)
            assertEquals(GattFailure(GattFailureKind.UNAVAILABLE), failure(transport.connect()))
            assertEquals(GattLinkState.Disconnected(GattStatus.SUCCESS), transport.state.value)
        }

    @Test
    fun `an id that is not an address, or a connectGatt that returns nothing, fails the connect`() =
        runTest {
            val bad =
                ScriptedPlatform().apply {
                    openThrows =
                        IllegalArgumentException("$TEST_ADDRESS is not a valid address")
                }
            assertEquals(GattFailureKind.STATUS, failure(transport(bad).connect()).kind)

            val none = ScriptedPlatform().apply { openReturnsNull = true }
            val transport = transport(none)
            assertEquals(GattFailureKind.UNAVAILABLE, failure(transport.connect()).kind)
            assertTrue(transport.state.value is GattLinkState.Disconnected)
        }

    @Test
    fun `a connect nobody answers times out, ends the link and asks the platform to stop`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform, GattTimings(connect = 10.seconds))
            val result = async { transport.connect() }
            runCurrent()
            advanceTimeBy(10_001)
            runCurrent()
            assertEquals(GattFailureKind.TIMEOUT, failure(result.await()).kind)
            assertEquals(GattLinkState.Disconnected(GattStatus.UNKNOWN), transport.state.value)
            assertEquals("disconnect", platform.gatt.calls.first())
            // The platform never reports: the object is released after the grace period.
            advanceTimeBy(2_001)
            assertEquals(listOf("disconnect", "close"), platform.gatt.calls)
        }

    @Test
    fun `a cancelled connect leaves a dead transport, never a half-open one`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform)
            val job = launch(start = CoroutineStart.UNDISPATCHED) { transport.connect() }
            runCurrent()
            job.cancel()
            runCurrent()
            assertEquals(GattLinkState.Disconnected(GattStatus.UNKNOWN), transport.state.value)
            assertTrue(platform.gatt.calls.contains("disconnect"))
        }

    // endregion

    // region operations

    @Test
    fun `discovery returns every characteristic with its properties`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val result = async { transport.discoverServices() }
            runCurrent()
            assertEquals(listOf("discover"), platform.gatt.calls)
            platform.emit(PlatformEvent.ServicesDiscovered(0, listOf(INDOOR_BIKE_INFO, CONTROL_POINT_INFO)))
            runCurrent()
            val services = (result.await() as GattResult.Ok).value
            assertEquals(INDOOR_BIKE_INFO, services.find(SERVICE, INDOOR_BIKE))
            assertEquals(CONTROL_POINT_INFO, services.find(SERVICE, CONTROL_POINT))
        }

    @Test
    fun `a status other than success fails the operation with that status`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, status = 5, value = ByteArray(0)))
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.STATUS, 5), failure(read.await()))
            // The slot is free again: the transport is still usable.
            val write = async { transport.write(CONTROL_POINT_INFO, byteArrayOf(0)) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicWritten(CONTROL_POINT, 0))
            runCurrent()
            assertEquals(GattResult.Ok(Unit), write.await())
        }

    @Test
    fun `read and write carry their bytes and write copies the callers array`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val bytes = byteArrayOf(0x05, 0x64, 0x00)
            val write = async { transport.write(CONTROL_POINT_INFO, bytes) }
            runCurrent()
            bytes[1] = 0x7F // the caller reuses its array while the platform still holds ours
            assertEquals("write 2AD9 056400", platform.gatt.calls.last())
            platform.emit(PlatformEvent.CharacteristicWritten(CONTROL_POINT, 0))
            runCurrent()
            write.await()

            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf(1, 2, 3)))
            runCurrent()
            assertEquals(listOf<Byte>(1, 2, 3), (read.await() as GattResult.Ok).value.toList())
        }

    // region write without response (the Click's handshake)

    @Test
    fun `a write without response reaches the platform as one, copies the bytes and completes on the write callback`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val bytes = byteArrayOf(0x52, 0x69)
            val write = async { transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, bytes) }
            runCurrent()
            bytes[0] = 0
            assertEquals(
                "no `write` (with response) call: the platform got the other one",
                listOf("writenr 0003 5269"),
                platform.gatt.calls.filter { it.startsWith("write") },
            )
            assertFalse(write.isCompleted)
            platform.emit(PlatformEvent.CharacteristicWritten(CLICK_SYNC_RX, 0))
            runCurrent()
            assertEquals(GattResult.Ok(Unit), write.await())
        }

    @Test
    fun `a write without response takes the one operation slot like any write`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val first = async { transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, byteArrayOf(1)) }
            runCurrent()
            val second = async { transport.write(CONTROL_POINT_INFO, byteArrayOf(2)) }
            runCurrent()
            assertEquals(GattFailureKind.BUSY, failure(second.await()).kind)
            platform.emit(PlatformEvent.CharacteristicWritten(CLICK_SYNC_RX, 0))
            runCurrent()
            assertEquals(GattResult.Ok(Unit), first.await())
            // The slot is free again.
            val third = async { transport.write(CONTROL_POINT_INFO, byteArrayOf(3)) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicWritten(CONTROL_POINT, 0))
            runCurrent()
            assertEquals(GattResult.Ok(Unit), third.await())
        }

    @Test
    fun `a characteristic that does not offer write without response is refused before the platform is asked`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val before = platform.gatt.calls.size
            // The fake radio says "write not permitted" (3); so does this, instead of a command nothing will answer.
            assertEquals(
                GattFailure(GattFailureKind.STATUS, 3),
                failure(transport.writeWithoutResponse(INDOOR_BIKE_INFO, byteArrayOf(1))),
            )
            assertEquals(before, platform.gatt.calls.size)
            assertTrue(log.toString(), log.any { it.contains("does not offer write without response") })
        }

    @Test
    fun `a write without response that the platform refuses, or that fails with a status, says so`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            platform.gatt.answer = Start.BUSY
            assertEquals(
                GattFailureKind.BUSY,
                failure(transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, byteArrayOf(1))).kind,
            )
            platform.gatt.answer = Start.PERMISSION
            assertEquals(
                GattFailureKind.UNAVAILABLE,
                failure(transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, byteArrayOf(1))).kind,
            )
            val write = async { transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, byteArrayOf(1)) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicWritten(CLICK_SYNC_RX, 133))
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.STATUS, 133), failure(write.await()))
        }

    @Test
    fun `a drop while a write without response waits answers it with DISCONNECTED`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val write = async { transport.writeWithoutResponse(CLICK_SYNC_RX_INFO, byteArrayOf(1)) }
            runCurrent()
            platform.dropped(status = 8)
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.DISCONNECTED, 8), failure(write.await()))
        }

    // endregion

    @Test
    fun `an operation while another is outstanding is answered with BUSY and does not disturb it`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val first = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            val second = async { transport.write(CONTROL_POINT_INFO, byteArrayOf(0)) }
            runCurrent()
            assertEquals(GattFailureKind.BUSY, failure(second.await()).kind)
            assertEquals(
                "the platform was asked once",
                1,
                platform.gatt.calls.count {
                    it.startsWith("read") ||
                        it.startsWith("write")
                },
            )
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf(9)))
            runCurrent()
            assertEquals(
                GattResult.Ok(listOf<Byte>(9)),
                (first.await() as GattResult.Ok).let { GattResult.Ok(it.value.toList()) },
            )
        }

    @Test
    fun `operations before the link is up fail as closed, like the fake`() =
        runTest {
            val transport = transport(ScriptedPlatform())
            assertEquals(GattFailureKind.CLOSED, failure(transport.discoverServices()).kind)
            assertEquals(GattFailureKind.CLOSED, failure(transport.read(INDOOR_BIKE_INFO)).kind)
        }

    @Test
    fun `a result that does not belong to the outstanding operation is ignored`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicWritten(CONTROL_POINT, 0)) // not a read
            platform.emit(PlatformEvent.MtuChanged(0, 247)) // an exchange nobody asked for
            runCurrent()
            assertFalse(read.isCompleted)
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf(1)))
            runCurrent()
            assertTrue(read.await() is GattResult.Ok)
        }

    @Test
    fun `a failed platform start is the failure the caller sees, and frees the slot`() =
        runTest {
            val cases =
                mapOf(
                    Start.REFUSED to GattFailure(GattFailureKind.STATUS, GattStatus.UNKNOWN),
                    Start.BUSY to GattFailure(GattFailureKind.BUSY),
                    Start.NOT_CONNECTED to GattFailure(GattFailureKind.DISCONNECTED),
                    Start.NOT_FOUND to GattFailure(GattFailureKind.NOT_FOUND),
                    Start.PERMISSION to GattFailure(GattFailureKind.UNAVAILABLE),
                    Start.UNAVAILABLE to GattFailure(GattFailureKind.UNAVAILABLE),
                    Start.ERROR to GattFailure(GattFailureKind.STATUS, GattStatus.UNKNOWN),
                )
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            for ((start, expected) in cases) {
                platform.gatt.answer = start
                assertEquals("$start", expected, failure(transport.read(INDOOR_BIKE_INFO)))
            }
            // Each refusal released the slot: the next read starts.
            val next = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf()))
            runCurrent()
            assertTrue(next.await() is GattResult.Ok)
        }

    @Test
    fun `a platform call that throws is a failure, never an exception, and the permission case is unavailable`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            platform.gatt.throwing["read"] = SecurityException("permission revoked")
            assertEquals(GattFailureKind.UNAVAILABLE, failure(transport.read(INDOOR_BIKE_INFO)).kind)
            platform.gatt.throwing["read"] = IllegalStateException("dead stack")
            assertEquals(GattFailureKind.STATUS, failure(transport.read(INDOOR_BIKE_INFO)).kind)
            platform.gatt.throwing.clear()
            // The link itself is still up: the environment, not the transport, decides what a lost permission means.
            assertEquals(GattLinkState.Connected, transport.state.value)
            // Closing a transport whose platform now throws is still safe.
            platform.gatt.throwing["disconnect"] = SecurityException("gone")
            platform.gatt.throwing["close"] = SecurityException("gone")
            transport.close()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(GattLinkState.Disconnected(GattStatus.SUCCESS), transport.state.value)
        }

    // endregion

    // region enabling and notifications

    @Test
    fun `enable subscribes before the write completes and tags notifications with the mode it asked for`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val received = mutableListOf<GattNotification>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.notifications.collect {
                    received +=
                        it
                }
            }

            val bike = async { transport.enable(INDOOR_BIKE_INFO, ValueUpdate.NOTIFICATION) }
            runCurrent()
            assertEquals("enable 2AD2 NOTIFICATION", platform.gatt.calls.last())
            // The peripheral pushes right after the descriptor write, before its acknowledgement reaches us.
            advanceTimeBy(5)
            platform.emit(PlatformEvent.Changed(INDOOR_BIKE, byteArrayOf(0x44, 0x00)))
            platform.emit(PlatformEvent.CccdWritten(INDOOR_BIKE, 0))
            runCurrent()
            assertEquals(GattResult.Ok(Unit), bike.await())

            val control = async { transport.enable(CONTROL_POINT_INFO, ValueUpdate.INDICATION) }
            runCurrent()
            platform.emit(PlatformEvent.CccdWritten(CONTROL_POINT, 0))
            runCurrent()
            control.await()
            advanceTimeBy(1000)
            platform.emit(PlatformEvent.Changed(CONTROL_POINT, byteArrayOf(0x80.toByte(), 0x00, 0x01)))
            runCurrent()

            assertEquals(listOf(INDOOR_BIKE, CONTROL_POINT), received.map { it.characteristic })
            assertEquals(listOf(ValueUpdate.NOTIFICATION, ValueUpdate.INDICATION), received.map { it.kind })
            assertEquals(5_000_000L, received[0].timestampNanos)
            assertEquals(1_005_000_000L, received[1].timestampNanos)
        }

    @Test
    fun `a value for a characteristic that was never enabled is not delivered`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val received = mutableListOf<GattNotification>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.notifications.collect {
                    received +=
                        it
                }
            }
            platform.emit(PlatformEvent.Changed(INDOOR_BIKE, byteArrayOf(1)))
            runCurrent()
            assertTrue(received.isEmpty())
        }

    @Test
    fun `a refused subscription is forgotten, so a stray value after it is not delivered`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val received = mutableListOf<GattNotification>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.notifications.collect {
                    received +=
                        it
                }
            }
            val enable = async { transport.enable(INDOOR_BIKE_INFO, ValueUpdate.NOTIFICATION) }
            runCurrent()
            platform.emit(PlatformEvent.CccdWritten(INDOOR_BIKE, status = 133))
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.STATUS, 133), failure(enable.await()))
            platform.emit(PlatformEvent.Changed(INDOOR_BIKE, byteArrayOf(1)))
            runCurrent()
            assertTrue(received.isEmpty())
        }

    @Test
    fun `notifications keep their order when they come from another thread while an operation runs`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val enable = async { transport.enable(INDOOR_BIKE_INFO, ValueUpdate.NOTIFICATION) }
            runCurrent()
            platform.emit(PlatformEvent.CccdWritten(INDOOR_BIKE, 0))
            runCurrent()
            enable.await()
            val received = mutableListOf<Int>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                transport.notifications.collect { received += it.value[0].toInt() and 0xFF }
            }
            val count = 200
            val binder =
                Thread { repeat(count) { platform.emit(PlatformEvent.Changed(INDOOR_BIKE, byteArrayOf(it.toByte()))) } }
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            binder.start()
            binder.join()
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf()))
            runCurrent()
            assertTrue(read.await() is GattResult.Ok)
            assertEquals((0 until count).toList(), received)
        }

    // endregion

    // region the end of the link

    @Test
    fun `a drop while an operation waits answers it with DISCONNECTED and the status, and releases the platform`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val write = async { transport.write(CONTROL_POINT_INFO, byteArrayOf(0)) }
            runCurrent()
            platform.dropped(status = 8)
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.DISCONNECTED, 8), failure(write.await()))
            assertEquals(GattLinkState.Disconnected(8), transport.state.value)
            assertEquals(GattFailure(GattFailureKind.DISCONNECTED, 8), failure(transport.read(INDOOR_BIKE_INFO)))
            assertEquals(listOf("write 2AD9 00", "close"), platform.gatt.calls)
            transport.close()
            runCurrent()
            assertEquals("close() after a drop does not touch the platform again", 1, platform.gatt.count("close"))
        }

    @Test
    fun `close while an operation is pending answers it, asks to disconnect and releases after the report`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            transport.close()
            runCurrent()
            assertEquals(GattFailure(GattFailureKind.DISCONNECTED, GattStatus.SUCCESS), failure(read.await()))
            assertEquals(GattLinkState.Disconnected(GattStatus.SUCCESS), transport.state.value)
            assertEquals("disconnect asked, not yet released", listOf("read 2AD2", "disconnect"), platform.gatt.calls)

            platform.dropped(status = 0) // the platform reports the disconnect
            runCurrent()
            assertEquals(listOf("read 2AD2", "disconnect", "close"), platform.gatt.calls)
            assertEquals(GattFailureKind.CLOSED, failure(transport.read(INDOOR_BIKE_INFO)).kind)
        }

    @Test
    fun `close releases the platform after the grace period when it never reports the disconnect`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            transport.close()
            advanceTimeBy(1_999)
            assertEquals(listOf("disconnect"), platform.gatt.calls)
            advanceTimeBy(2)
            assertEquals(listOf("disconnect", "close"), platform.gatt.calls)
        }

    @Test
    fun `close is idempotent and safe in every state`() =
        runTest {
            // Never connected.
            val idle = transport(ScriptedPlatform())
            idle.close()
            idle.close()
            assertEquals(GattLinkState.Disconnected(GattStatus.SUCCESS), idle.state.value)
            assertEquals(GattFailureKind.CLOSED, failure(idle.connect()).kind)

            // Connected, closed twice.
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            transport.close()
            transport.close()
            advanceTimeBy(3_000)
            assertEquals(1, platform.gatt.count("disconnect"))
            assertEquals(1, platform.gatt.count("close"))

            // Closed while connecting.
            val connecting = ScriptedPlatform()
            val third = transport(connecting)
            val result = async { third.connect() }
            runCurrent()
            third.close()
            runCurrent()
            assertEquals(GattFailureKind.DISCONNECTED, failure(result.await()).kind)
            assertTrue(connecting.gatt.calls.contains("disconnect"))
        }

    @Test
    fun `a state that is not final never comes back after the link is gone`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val states = mutableListOf<GattLinkState>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { transport.state.collect { states += it } }
            platform.dropped(8)
            platform.connected() // a late, wrong word from the platform
            runCurrent()
            assertEquals(GattLinkState.Disconnected(8), transport.state.value)
            assertEquals(listOf(GattLinkState.Connected, GattLinkState.Disconnected(8)), states)
        }

    // endregion

    // region callers that give up

    @Test
    fun `a caller that gives up leaves the operation outstanding, and the next one waits for the late callback`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val abandoned =
                launch(start = CoroutineStart.UNDISPATCHED) { transport.write(CONTROL_POINT_INFO, byteArrayOf(1)) }
            runCurrent()
            abandoned.cancel()
            runCurrent()
            assertEquals("still one write at the platform", 1, platform.gatt.count("write"))

            val next = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            assertEquals("the read waits: the platform would refuse it", 0, platform.gatt.count("read"))
            assertFalse(next.isCompleted)

            platform.emit(PlatformEvent.CharacteristicWritten(CONTROL_POINT, 0)) // the late callback
            runCurrent()
            assertEquals("now it starts", 1, platform.gatt.count("read"))
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf(7)))
            runCurrent()
            assertTrue(next.await() is GattResult.Ok)
            assertEquals(GattLinkState.Connected, transport.state.value)
        }

    @Test
    fun `a callback that never comes ends the link after the grace period and fails whoever waited`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform, GattTimings(abandonedGrace = 5.seconds))
            val abandoned =
                launch(start = CoroutineStart.UNDISPATCHED) { transport.write(CONTROL_POINT_INFO, byteArrayOf(1)) }
            runCurrent()
            abandoned.cancel()
            runCurrent()
            val next = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            advanceTimeBy(5_001)
            runCurrent()
            assertEquals(GattLinkState.Disconnected(GattStatus.UNKNOWN), transport.state.value)
            assertEquals(GattFailureKind.DISCONNECTED, failure(next.await()).kind)
            assertEquals("the link was asked to end", 1, platform.gatt.count("disconnect"))
        }

    @Test
    fun `the transports own timeout answers TIMEOUT and then treats the operation as abandoned`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform, GattTimings(operation = 15.seconds, abandonedGrace = 5.seconds))
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            advanceTimeBy(15_001)
            runCurrent()
            assertEquals(GattFailureKind.TIMEOUT, failure(read.await()).kind)
            assertEquals(GattLinkState.Connected, transport.state.value)
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue("never answered: dead", transport.state.value is GattLinkState.Disconnected)
        }

    @Test
    fun `the MTU request does not hold the slot, so an unanswered one times out and the link carries on`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val mtu = async { transport.requestMtu(64) }
            runCurrent()
            advanceTimeBy(3_001)
            runCurrent()
            assertEquals(GattFailureKind.TIMEOUT, failure(mtu.await()).kind)
            val read = async { transport.read(INDOOR_BIKE_INFO) }
            runCurrent()
            assertEquals("the read started at once", 1, platform.gatt.count("read"))
            platform.emit(PlatformEvent.MtuChanged(0, 64)) // the late answer is harmless
            platform.emit(PlatformEvent.CharacteristicRead(INDOOR_BIKE, 0, byteArrayOf()))
            runCurrent()
            assertTrue(read.await() is GattResult.Ok)
            assertEquals(GattLinkState.Connected, transport.state.value)
        }

    @Test
    fun `an MTU request returns the negotiated size`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = connected(platform)
            val mtu = async { transport.requestMtu(64) }
            runCurrent()
            assertEquals("mtu 64", platform.gatt.calls.last())
            platform.emit(PlatformEvent.MtuChanged(0, 185))
            runCurrent()
            assertEquals(GattResult.Ok(185), mtu.await())
        }

    // endregion

    @Test
    fun `no log line holds the address, a name or an exception message`() =
        runTest {
            val platform =
                ScriptedPlatform().apply {
                    openThrows =
                        IllegalArgumentException("$TEST_ADDRESS is not a valid address")
                }
            val first = transport(platform)
            first.connect()
            val second =
                connected(
                    ScriptedPlatform().also {
                        it.gatt.throwing["read"] =
                            SecurityException("for $TEST_ADDRESS")
                    },
                )
            second.read(INDOOR_BIKE_INFO)
            val dead = CompletableDeferred<Unit>()
            dead.complete(Unit)
            assertTrue("something was logged", log.isNotEmpty())
            assertTrue(
                log.toString(),
                log.none {
                    it.contains("AA:BB") || it.contains(TEST_ADDRESS) ||
                        it.contains("valid address")
                },
            )
            assertTrue("the fingerprint identifies the device", log.any { it.contains("#") })
        }

    // region background connect (docs/adr/0040)

    @Test
    fun `an active transport connects with autoConnect off and gives up at its own limit`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform)
            val result = async { transport.connect() }
            runCurrent()
            assertEquals(listOf(false), platform.backgroundFlags)
            advanceTimeBy(36.seconds)
            runCurrent()
            assertEquals(GattFailureKind.TIMEOUT, failure(result.await()).kind)
        }

    @Test
    fun `a background transport connects with autoConnect on and has no limit of its own`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform, mode = ConnectMode.BACKGROUND)
            val result = async { transport.connect() }
            runCurrent()
            assertEquals(listOf(true), platform.backgroundFlags)

            advanceTimeBy(30 * 60 * 1000L) // half an hour of a Click asleep
            runCurrent()
            assertFalse("still pending: the controller waits for the device", result.isCompleted)
            assertEquals(GattLinkState.Connecting, transport.state.value)

            platform.connected() // it advertised
            runCurrent()
            assertEquals(GattResult.Ok(Unit), result.await())
            assertEquals(GattLinkState.Connected, transport.state.value)
        }

    @Test
    fun `closing a pending background transport cancels the connection and fails the waiting connect`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform, mode = ConnectMode.BACKGROUND)
            val result = async { transport.connect() }
            runCurrent()

            transport.close()
            runCurrent()
            assertTrue("the platform was told to stop connecting", "disconnect" in platform.gatt.calls)
            platform.dropped(status = 0) // the platform answers the disconnect
            runCurrent()
            assertTrue(result.await() is GattResult.Failed)
            assertTrue(transport.state.value is GattLinkState.Disconnected)
            advanceTimeBy(5.seconds)
            runCurrent()
            assertTrue("and released it", "close" in platform.gatt.calls)
        }

    @Test
    fun `a background connect that fails with 133 is a dead transport like any other`() =
        runTest {
            val platform = ScriptedPlatform()
            val transport = transport(platform, mode = ConnectMode.BACKGROUND)
            val result = async { transport.connect() }
            runCurrent()
            platform.dropped(status = 133)
            runCurrent()
            val failed = failure(result.await())
            assertEquals(133, failed.status)
            assertTrue(failed.isGattError)
            assertEquals(GattLinkState.Disconnected(133), transport.state.value)
        }

    @Test
    fun `the factory passes the mode of the supervisor to the platform`() =
        runTest {
            val platform = ScriptedPlatform()
            val factory =
                AndroidGattTransportFactory(
                    platform,
                    backgroundScope,
                    { testScheduler.currentTime * 1_000_000 },
                    GattTimings(),
                    BleLog.None,
                )
            val active = factory.open(TEST_DEVICE)
            val explicit = factory.open(TEST_DEVICE, ConnectMode.ACTIVE)
            val background = factory.open(TEST_DEVICE, ConnectMode.BACKGROUND)
            listOf(active, explicit, background).forEach { backgroundScope.launch { it.connect() } }
            runCurrent()
            assertEquals(listOf(false, false, true), platform.backgroundFlags)
        }

    // endregion
}

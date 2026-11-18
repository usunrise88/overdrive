package app.overdrive.core.click

import app.overdrive.core.ble.BleAvailability
import app.overdrive.core.ble.BleRig
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.GattStatus
import app.overdrive.core.ble.codec.DecodeErrorKind
import app.overdrive.core.ble.codec.hex
import app.overdrive.core.ble.fake.ClickEncoder
import app.overdrive.core.ble.fake.ClickScript
import app.overdrive.core.ble.fake.FakeClick
import app.overdrive.core.ble.fake.FakeClickLock
import app.overdrive.core.ble.fake.FakeClickOptions
import app.overdrive.core.ble.fake.ReplayPeripheral
import app.overdrive.core.ble.link.LinkFailureKind
import app.overdrive.core.ble.link.LinkStatus
import app.overdrive.core.ble.recording.BleSessionFormat
import app.overdrive.core.device.ConnectionState
import app.overdrive.core.device.collectAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The Click adapter over the simulated Click (docs/protocols/click-v2.md §3.1, §6; spec §4.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class ClickDeviceTest {
    private fun BleRig.withClick(
        options: FakeClickOptions = FakeClickOptions(),
        lock: FakeClickLock = FakeClickLock.NONE,
    ): FakeClick =
        addClick(FakeClick(options)).also {
            it.lock = lock
        }

    // region set-up

    @Test
    fun `set-up order is the notes' 3-1, from discovery to the optional steps, as the real puck answers it`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val recorded = rig.recording(model = "Click")
            val click = rig.click(factory = recorded.factory)
            click.start()
            rig.advance(1_000)
            rig.settle()

            val async = ClickUuids.ASYNC.toString()
            val rx = ClickUuids.SYNC_RX.toString()
            val tx = ClickUuids.SYNC_TX.toString()
            assertEquals(
                listOf(
                    "S - connecting",
                    "S - connected",
                    "D $async FC82/N",
                    "D $rx FC82/Ww",
                    "D $tx FC82/RI",
                    "D 2A26 *", // the recorder redacts every line of an identifying characteristic, discovery included
                    "D 2A19 180F/RN",
                    "S - mtu=128",
                    "C $async 0100",
                    "C $tx 0200",
                    "W $rx 526964654F6E",
                    "I $tx 526964654F6E", // bare RideOn: the plain path, no version, no key
                    "W $rx FF0400",
                    "R 2A26 *",
                    "R 2A19 64", // the battery by read: 100 %, and no frame follows it
                ),
                recorded.compact(),
            )
            assertEquals(ConnectionState.CONNECTED, click.connection.value)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(1, fake.handshakeCount)
            rig.assertNoViolations()
        }

    @Test
    fun `the handshake is written without response, then FF 04 00, and the reply tail is ignored`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick(FakeClickOptions.byNotes(replyTailLength = 64))
            val click = rig.click()
            val events = collectAll(click.events)
            click.startAndSettle(rig)
            assertEquals(listOf(false, false), fake.writes.map { it.withResponse })
            assertContentEquals("RideOn".encodeToByteArray(), fake.writes[0].bytes)
            assertContentEquals(byteArrayOf(0xFF.toByte(), 0x04, 0x00), fake.writes[1].bytes)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(
                listOf(ClickEvent.HandshakeAnswered("02 03", 64)),
                events.filterIsInstance<ClickEvent.HandshakeAnswered>(),
            )
            rig.assertNoViolations()
        }

    @Test
    fun `the firmware and the battery are read once, and a battery frame updates it`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick(FakeClickOptions.byNotes(batteryPercent = 87))
            val click = rig.click()
            assertNull(click.battery.value)
            assertNull(click.firmware.value)
            click.startAndSettle(rig)
            assertEquals("1.2.0", click.firmware.value)
            assertEquals(87, click.battery.value)
            rig.fakeClickScript { battery(64, at = 3.seconds) }
            rig.advance(3_000)
            assertEquals(64, click.battery.value)
            rig.fakeClickScript { battery(200, at = 4.seconds) }
            rig.advance(1_000)
            assertEquals(64, click.battery.value, "a percentage above 100 is not a battery")
        }

    @Test
    fun `an MTU of 128 is asked for, so a reply with a 64 byte key fits, and a refusal only shows in the events`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val recorded = rig.recording()
            rig.click(factory = recorded.factory).startAndSettle(rig)
            rig.settle()
            assertTrue(recorded.compact().contains("S - mtu=128"))

            val rig2 = BleRig(this)
            val fake = rig2.withClick()
            val click = rig2.click(ClickOptions(requestedMtu = null))
            click.startAndSettle(rig2)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(2, fake.writes.size)
        }

    // endregion

    // region options flip behaviour

    @Test
    fun `without the post-handshake write only RideOn is sent`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            rig.click(ClickOptions(sendPostHandshake = false)).startAndSettle(rig)
            assertEquals(1, fake.writes.size)
        }

    @Test
    fun `a controller that needs FF 04 00 is silent without it and delivers with it (bench question 6)`() =
        runTest {
            // The silence rule is what tells the two apart, so it is on here; the fake sends frames once it delivers.
            val needing = FakeClickOptions.byNotes(needsPostHandshake = true)
            val rig = BleRig(this)
            rig.withClick(needing)
            val without =
                rig.click(ClickOptions(sendPostHandshake = false, silentMeansLocked = true)).startAndSettle(rig)
            rig.advance(31_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), without.state.value)

            val rig2 = BleRig(this)
            rig2.withClick(needing)
            val with = rig2.click(ClickOptions(sendPostHandshake = true, silentMeansLocked = true)).startAndSettle(rig2)
            assertEquals(ClickState.Active, with.state.value)
        }

    @Test
    fun `writes can all be with response`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            rig.click(ClickOptions(writeWithResponse = true)).startAndSettle(rig)
            assertEquals(listOf(true, true), fake.writes.map { it.withResponse })
            rig.assertNoViolations()
        }

    @Test
    fun `the strict reply check refuses another version, naming the bytes, and any-RideOn accepts it`() =
        runTest {
            val odd = FakeClickOptions(replyVersion = 0x0103)
            val rig = BleRig(this)
            rig.withClick(odd)
            val strict = rig.click()
            val events = collectAll(strict.events)
            strict.start()
            rig.advance(1_000)
            assertIs<ClickState.SetupFailed>(strict.state.value)
            assertEquals(LinkFailureKind.SETUP_FAILED, (strict.state.value as ClickState.SetupFailed).failure.kind)
            assertEquals(
                ClickEvent.HandshakeReplyRejected("526964654F6E0103"),
                events.filterIsInstance<ClickEvent.HandshakeReplyRejected>().first(),
            )
            assertEquals(ConnectionState.DISCONNECTED, strict.connection.value)

            val rig2 = BleRig(this)
            rig2.withClick(odd)
            val lenient = rig2.click(ClickOptions(handshakeReply = HandshakeReplyCheck.ANY_RIDE_ON))
            lenient.startAndSettle(rig2)
            assertEquals(ClickState.Active, lenient.state.value)
        }

    @Test
    fun `a bare RideOn passes the default check, RideOn 02 03 too, and any other version does not`() =
        runTest {
            fun state(options: FakeClickOptions): ClickState {
                val rig = BleRig(this)
                rig.withClick(options)
                return rig.click().startAndSettle(rig).state.value
            }
            assertEquals(ClickState.Active, state(FakeClickOptions(replyVersion = null)), "the real puck's reply")
            assertEquals(ClickState.Active, state(FakeClickOptions(replyVersion = 0x0203)), "the notes' reply")
            assertEquals(ClickState.Active, state(FakeClickOptions(replyVersion = 0x0203, replyTailLength = 64)))
            assertIs<ClickState.SetupFailed>(state(FakeClickOptions(replyVersion = 0x0103)), "the encrypted variant")
            assertIs<ClickState.SetupFailed>(state(FakeClickOptions(replyVersion = 0x0001)))

            // The lenient check still takes any version.
            for (version in listOf(0x0103, 0x0001)) {
                val rig = BleRig(this)
                rig.withClick(FakeClickOptions(replyVersion = version))
                val lenient = rig.click(ClickOptions(handshakeReply = HandshakeReplyCheck.ANY_RIDE_ON))
                assertEquals(ClickState.Active, lenient.startAndSettle(rig).state.value)
            }
        }

    @Test
    fun `no reply within the timeout fails the set-up, and not waiting for one connects anyway`() =
        runTest {
            val mute = FakeClickOptions(answersHandshake = false)
            val rig = BleRig(this)
            rig.withClick(mute)
            val waiting = rig.click()
            val events = collectAll(waiting.events)
            waiting.start()
            rig.advance(6_000)
            assertIs<ClickState.SetupFailed>(waiting.state.value)
            assertTrue(ClickEvent.HandshakeTimedOut in events)

            val rig2 = BleRig(this)
            rig2.withClick(mute)
            val noWait =
                rig2
                    .click(ClickOptions(handshakeReply = HandshakeReplyCheck.NONE, silentMeansLocked = true))
                    .startAndSettle(rig2)
            assertEquals(ConnectionState.CONNECTED, noWait.connection.value)
            assertEquals(ClickState.Waiting, noWait.state.value, "connected, no frame yet, and the silence rule is on")

            val rig3 = BleRig(this)
            rig3.withClick(mute)
            val byDefault = rig3.click(ClickOptions(handshakeReply = HandshakeReplyCheck.NONE)).startAndSettle(rig3)
            assertEquals(ClickState.Active, byDefault.state.value, "with the rule off a link that is up is active")
        }

    @Test
    fun `with the check off a reply that does come is only reported`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val click = rig.click(ClickOptions(handshakeReply = HandshakeReplyCheck.NONE))
            val events = collectAll(click.events)
            click.startAndSettle(rig)
            assertEquals(ClickState.Active, click.state.value)
            assertTrue(events.any { it == ClickEvent.HandshakeAnswered(null, 0) })
        }

    @Test
    fun `the older service is used when it is what the controller offers, and not when it is switched off`() =
        runTest {
            val legacy = FakeClickOptions(service = ClickService.LEGACY)
            val rig = BleRig(this)
            rig.withClick(legacy)
            assertEquals(ClickState.Active, rig.click().startAndSettle(rig).state.value)

            val rig2 = BleRig(this)
            rig2.withClick(legacy)
            assertEquals(
                ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN),
                rig2.click(ClickOptions(acceptLegacyService = false)).startAndSettle(rig2).state.value,
            )
        }

    @Test
    fun `a keep-alive is written at its interval, and there is none by default`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            rig.click(ClickOptions(keepAliveInterval = 10.seconds)).startAndSettle(rig)
            rig.advance(30_000)
            val keepAlives = fake.writes.filter { it.bytes.contentEquals(ClickCodec.neutralFrame()) }
            assertEquals(3, keepAlives.size)
            assertTrue(keepAlives.all { !it.withResponse })
            assertContentEquals(byteArrayOf(0x23, 0x08, -1, -1, -1, -1, 0x0F), ClickCodec.neutralFrame())

            val rig2 = BleRig(this)
            val fake2 = rig2.withClick()
            rig2.click().startAndSettle(rig2)
            rig2.advance(120_000)
            assertEquals(2, fake2.writes.size, "RideOn and FF 04 00 only")
        }

    @Test
    fun `the keep-alive frame is an option`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            rig
                .click(
                    ClickOptions(keepAliveInterval = 5.seconds, keepAliveFrame = byteArrayOf(0x15)),
                ).startAndSettle(rig)
            rig.advance(5_000)
            assertContentEquals(byteArrayOf(0x15), fake.writes.last().bytes)
        }

    @Test
    fun `without the standard reads nothing is read`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val recorded = rig.recording()
            rig.click(ClickOptions(readStandardCharacteristics = false), factory = recorded.factory).startAndSettle(rig)
            rig.settle()
            assertTrue(recorded.compact().none { it.startsWith("R ") })
        }

    @Test
    fun `a stuck bit can be ignored by the option`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click(ClickOptions(ignoredBits = ClickBits.PLUS_SHIFT_DOWN))
            val changes = collectAll(click.buttons)
            click.startAndSettle(rig)
            fake.press(ClickBits.PLUS_SHIFT_DOWN)
            rig.advance(100)
            assertTrue(changes.isEmpty())
            fake.press(ClickBits.A)
            rig.advance(100)
            assertEquals(ClickBits.A, changes.single().held)
        }

    // endregion

    // region what the UI can explain (spec §4.7)

    @Test
    fun `connecting, then active as soon as the handshake is answered, with no frame at all`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val click = rig.click()
            val states = collectAll(click.state)
            click.start()
            rig.advance(1_000)
            assertEquals(listOf(ClickState.Stopped, ClickState.Connecting(1), ClickState.Active), states.toList())
        }

    @Test
    fun `with the silence rule on it waits for the first frame, a press makes it active`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click(ClickOptions(silentMeansLocked = true))
            val states = collectAll(click.state)
            click.start()
            rig.advance(1_000)
            assertEquals(listOf(ClickState.Stopped, ClickState.Connecting(1), ClickState.Waiting), states.toList())
            fake.press(ClickBits.A)
            rig.advance(100)
            assertEquals(
                listOf(ClickState.Stopped, ClickState.Connecting(1), ClickState.Waiting, ClickState.Active),
                states.toList(),
            )
        }

    @Test
    fun `with the silence rule on, silence after the handshake needs unlocking after the no-event time`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick(lock = FakeClickLock.SILENT)
            val click = rig.click(ClickOptions(silentMeansLocked = true))
            click.startAndSettle(rig)
            assertEquals(ConnectionState.CONNECTED, click.connection.value)
            assertEquals(ClickState.Waiting, click.state.value)
            rig.advance(28_000)
            assertEquals(ClickState.Waiting, click.state.value, "30 s from the handshake, not before")
            rig.advance(2_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), click.state.value)
            assertEquals(ConnectionState.CONNECTED, click.connection.value, "the link stays up")
            assertTrue(fake.isHandshaken)

            fake.unlock() // the rider unlocked it through Zwift; a real Click says nothing until a button is pressed
            rig.advance(100)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), click.state.value)
            fake.press(ClickBits.A) // and then a frame arrives
            rig.advance(100)
            assertEquals(ClickState.Active, click.state.value)
        }

    @Test
    fun `the silence rule is off by default, and its time is an option when it is on`() =
        runTest {
            assertFalse(ClickOptions().silentMeansLocked, "bench report 1: a healthy Click is silent until a press")
            val rig = BleRig(this)
            rig.withClick(lock = FakeClickLock.SILENT)
            val off = rig.click().startAndSettle(rig)
            rig.advance(120_000)
            assertEquals(ClickState.Active, off.state.value)

            val rig2 = BleRig(this)
            rig2.withClick(lock = FakeClickLock.SILENT)
            val quick =
                rig2.click(ClickOptions(silentMeansLocked = true, noEventTimeout = 5.seconds)).startAndSettle(rig2)
            rig2.advance(4_000)
            assertEquals(ClickState.Waiting, quick.state.value)
            rig2.advance(1_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), quick.state.value)
        }

    @Test
    fun `any frame at all counts as delivering, a heartbeat or a type nobody knows`() =
        runTest {
            val quiet = FakeClickOptions(heartbeatInterval = null, batteryPercent = null)
            val rig = BleRig(this)
            val fake = rig.withClick(quiet)
            val click = rig.click(ClickOptions(silentMeansLocked = true)).startAndSettle(rig)
            assertEquals(ClickState.Waiting, click.state.value)
            fake.sendFrame(ClickEncoder.frame(0x3C, byteArrayOf(1, 2)))
            rig.advance(10)
            assertEquals(ClickState.Active, click.state.value)

            val rig2 = BleRig(this)
            rig2.withClick(FakeClickOptions(heartbeatInterval = 2.seconds, batteryPercent = null))
            val beating = rig2.click(ClickOptions(silentMeansLocked = true)).startAndSettle(rig2)
            assertEquals(ClickState.Waiting, beating.state.value)
            rig2.advance(2_000)
            assertEquals(ClickState.Active, beating.state.value)
        }

    @Test
    fun `a malformed frame is reported and is not a sign of life`() =
        runTest {
            val quiet = FakeClickOptions(heartbeatInterval = null, batteryPercent = null)
            val rig = BleRig(this)
            val fake = rig.withClick(quiet)
            val click = rig.click(ClickOptions(silentMeansLocked = true))
            val events = collectAll(click.events)
            click.startAndSettle(rig)
            fake.sendFrame(ClickEncoder.frame(0x23)) // a button frame without its bitmap
            rig.advance(10)
            assertEquals(ClickState.Waiting, click.state.value)
            assertEquals(
                DecodeErrorKind.TRUNCATED,
                events.filterIsInstance<ClickEvent.MalformedFrame>().single().error.kind,
            )
        }

    @Test
    fun `unrecognised frames and the disconnect notice are events, not errors`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val events = collectAll(click.events)
            click.startAndSettle(rig)
            fake.sendFrame(ClickEncoder.frame(0x3C, byteArrayOf(9, 9, 9)))
            fake.sendFrame(ClickEncoder.disconnectNotice())
            rig.advance(10)
            assertTrue(ClickEvent.UnrecognisedFrame(0x3C, 3) in events)
            assertTrue(ClickEvent.DisconnectNoticeReceived in events)
            assertEquals(ClickState.Active, click.state.value)
        }

    @Test
    fun `a hidden service is the unlock notice, retried with backoff until it shows`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick(lock = FakeClickLock.HIDDEN)
            val click = rig.click()
            click.start()
            rig.advance(500)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN), click.state.value)
            val waiting = assertIs<LinkStatus.WaitingToRetry>(click.status.value)
            assertEquals(LinkFailureKind.SERVICE_MISSING, waiting.failure.kind)
            assertEquals(1_000L, waiting.retryInMillis)

            rig.advance(10_000)
            assertTrue(rig.ble.transports.size >= 3, "it keeps trying: ${rig.ble.transports.size}")
            assertTrue(
                rig.ble.transports.dropLast(1).all { it.isClosed },
                "every failed transport is closed, none reused",
            )
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN), click.state.value)

            fake.unlock() // unlocked through Zwift: the service shows at the next attempt
            rig.advance(40_000)
            assertEquals(ClickState.Active, click.state.value)
            rig.assertNoViolations()
        }

    @Test
    fun `a missing service is an ordinary failure when the hidden signal is switched off`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick(lock = FakeClickLock.HIDDEN)
            val click = rig.click(ClickOptions(serviceHiddenMeansLocked = false))
            click.start()
            rig.advance(500)
            val failed = assertIs<ClickState.SetupFailed>(click.state.value)
            assertEquals(LinkFailureKind.SERVICE_MISSING, failed.failure.kind)
        }

    @Test
    fun `a controller that is not there is not found, and so is one that took too long`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            fake.powerOff()
            fake.absentFailsAfter = 2.seconds
            val click = rig.click()
            click.start()
            rig.advance(3_000)
            assertEquals(ClickState.NotFound(1, 1_000, wasConnected = false), click.state.value)

            val rig2 = BleRig(this)
            rig2.withClick().powerOff() // it never answers at all
            val silent = rig2.click()
            silent.start()
            rig2.advance(45_500)
            assertEquals(ClickState.NotFound(1, 1_000, wasConnected = false), silent.state.value)
        }

    @Test
    fun `a link that drops is not found with wasConnected, and the click comes back by itself`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click().startAndSettle(rig)
            assertEquals(ClickState.Active, click.state.value)
            fake.dropLink(GattStatus.GATT_ERROR)
            rig.advance(10)
            assertEquals(ClickState.NotFound(1, 1_000, wasConnected = true), click.state.value)
            rig.advance(3_000)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(2, fake.handshakeCount)
            rig.assertNoViolations()
        }

    @Test
    fun `bluetooth off is its own state, and the click connects the moment it is back`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val click = rig.click()
            click.start()
            rig.ble.setAvailability(BleAvailability.BLUETOOTH_OFF)
            rig.advance(1_000)
            assertEquals(ClickState.BluetoothUnavailable(BleAvailability.BLUETOOTH_OFF), click.state.value)
            rig.ble.setAvailability(BleAvailability.PERMISSION_DENIED)
            rig.advance(10)
            assertEquals(ClickState.BluetoothUnavailable(BleAvailability.PERMISSION_DENIED), click.state.value)
            rig.ble.setAvailability(BleAvailability.READY)
            rig.advance(1_000)
            assertEquals(ClickState.Active, click.state.value)
            rig.assertNoViolations()
        }

    @Test
    fun `retry now on a connected but silent click drops the link and starts over`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick(lock = FakeClickLock.SILENT)
            val click = rig.click(ClickOptions(silentMeansLocked = true)).startAndSettle(rig)
            rig.advance(31_000)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.NO_EVENTS), click.state.value)
            fake.lock = FakeClickLock.NONE // unlocked through Zwift while we were connected and silent
            click.retryNow()
            rig.advance(3_000)
            assertEquals(ClickState.Waiting, click.state.value, "reconnected, and nothing until a press")
            fake.press(ClickBits.A)
            rig.advance(100)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(2, fake.handshakeCount)
            assertEquals(2, rig.ble.transports.size)
            rig.assertNoViolations()
        }

    @Test
    fun `retry now while the click is active does nothing`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click().startAndSettle(rig)
            click.retryNow()
            rig.advance(5_000)
            assertEquals(1, fake.handshakeCount)
        }

    @Test
    fun `close stops it, and it is stopped again if started never`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val click = rig.click()
            assertEquals(ClickState.Stopped, click.state.value)
            click.startAndSettle(rig)
            click.close()
            rig.advance(100)
            assertEquals(ClickState.Stopped, click.state.value)
            assertEquals(ConnectionState.DISCONNECTED, click.connection.value)
            assertTrue(rig.ble.transports.all { it.isClosed })
        }

    // endregion

    // region buttons

    @Test
    fun `presses and releases arrive as edges with the whole state, and repeated frames are ignored`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val changes = collectAll(click.buttons)
            click.startAndSettle(rig)
            fake.press(ClickBits.PLUS)
            rig.advance(50)
            fake.sendFrame(ClickEncoder.pressed(ClickBits.PLUS)) // the same state again
            fake.press(ClickBits.A)
            rig.advance(50)
            fake.release(ClickBits.PLUS or ClickBits.A)
            rig.advance(50)
            assertEquals(
                listOf(
                    Triple(ClickBits.PLUS, 0, ClickBits.PLUS),
                    Triple(ClickBits.A, 0, ClickBits.PLUS or ClickBits.A),
                    Triple(0, ClickBits.PLUS or ClickBits.A, 0),
                ),
                changes.map { Triple(it.pressed, it.released, it.held) },
            )
            assertTrue(changes.none { it.synthetic })
        }

    @Test
    fun `all five buttons at once, then all released`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val changes = collectAll(click.buttons)
            click.startAndSettle(rig)
            fake.press(ClickBits.RIGHT_PUCK)
            rig.advance(10)
            fake.release(ClickBits.RIGHT_PUCK)
            rig.advance(10)
            assertEquals(listOf(ClickBits.RIGHT_PUCK, 0), changes.map { it.held })
        }

    @Test
    fun `a drop with a button held releases it with a synthetic change, and a reconnect starts from nothing`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val changes = collectAll(click.buttons)
            click.startAndSettle(rig)
            fake.press(ClickBits.PLUS or ClickBits.A)
            rig.advance(100)
            fake.dropLink()
            rig.advance(10)
            val lost = changes.last()
            assertTrue(lost.synthetic)
            assertEquals(ClickBits.PLUS or ClickBits.A, lost.released)
            assertEquals(0, lost.held)

            // The rider let go while the link was down: the controller will never say so.
            fake.release(ClickBits.PLUS or ClickBits.A)
            rig.advance(5_000)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(2, changes.size, "nothing more arrives until a button changes")

            fake.press(ClickBits.B)
            rig.advance(10)
            assertEquals(ClickBits.B, changes.last().pressed)
            assertEquals(ClickBits.B, changes.last().held, "only B: nothing is left held from before the drop")
            assertFalse(changes.last().synthetic)
        }

    @Test
    fun `a drop with nothing held is silent`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val changes = collectAll(click.buttons)
            click.startAndSettle(rig)
            fake.dropLink()
            rig.advance(5_000)
            assertTrue(changes.isEmpty())
        }

    @Test
    fun `the scripted drop and silence of the fake behave`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick()
            val click = rig.click()
            val changes = collectAll(click.buttons)
            fake.script {
                silence(from = 3.seconds, until = 5.seconds)
                press(ClickBits.Y, at = 4.seconds) // inside the gap: never reaches the host
                release(ClickBits.Y, at = 4_500.milliseconds)
                tap(ClickBits.Z, at = 6.seconds)
            }
            click.start()
            rig.advance(8_000)
            assertEquals(listOf(ClickBits.Z, 0), changes.map { it.held }, "the gap swallowed Y completely")
        }

    // endregion

    // region the defaults of the first bench (docs/bench-reports/2026-11-18-first-ride.md)

    @Test
    fun `a bare RideOn goes connecting to active with no frame at all, and stays active for ten minutes`() =
        runTest {
            val rig = BleRig(this)
            val fake = rig.withClick(FakeClickOptions.realPuck(announces = false))
            val recorded = rig.recording(model = "Click")
            val click = rig.click(factory = recorded.factory)
            val states = collectAll(click.state)
            val changes = collectAll(click.buttons)
            val events = collectAll(click.events)
            click.start()
            rig.advance(1_000)
            assertEquals(listOf(ClickState.Stopped, ClickState.Connecting(1), ClickState.Active), states.toList())

            rig.advance(600_000)
            assertEquals(ClickState.Active, click.state.value)
            assertEquals(
                listOf(ClickState.Stopped, ClickState.Connecting(1), ClickState.Active),
                states.toList(),
                "no change of state in ten minutes",
            )
            assertEquals(ConnectionState.CONNECTED, click.connection.value)
            assertEquals(1, fake.handshakeCount, "the link was never dropped to try again")
            rig.settle()
            assertTrue(recorded.compact().none { it.startsWith("N ") }, "not one frame: ${recorded.compact()}")
            assertTrue(changes.isEmpty())
            assertEquals(
                listOf(ClickEvent.HandshakeAnswered(null, 0)),
                events.filterIsInstance<ClickEvent.HandshakeAnswered>(),
            )
            rig.assertNoViolations()
        }

    @Test
    fun `RideOn 01 03, a seven byte reply and other bytes fail the default check, with the bytes in the event`() =
        runTest {
            val cases =
                listOf(
                    FakeClickOptions(replyVersion = 0x0103) to "526964654F6E0103",
                    FakeClickOptions(rawReply = hex("52 69 64 65 4F 6E 02")) to "526964654F6E02",
                    FakeClickOptions(rawReply = "Nothing".encodeToByteArray()) to "4E6F7468696E67",
                )
            for ((options, prefix) in cases) {
                val rig = BleRig(this)
                rig.withClick(options)
                val click = rig.click()
                val states = collectAll(click.state)
                val events = collectAll(click.events)
                click.startAndSettle(rig)
                val failed = assertIs<ClickState.SetupFailed>(click.state.value, prefix)
                assertEquals(LinkFailureKind.SETUP_FAILED, failed.failure.kind)
                assertEquals(
                    listOf(ClickEvent.HandshakeReplyRejected(prefix)),
                    events.filterIsInstance<ClickEvent.HandshakeReplyRejected>(),
                )
                assertEquals(ConnectionState.DISCONNECTED, click.connection.value)
                assertFalse(ClickState.Active in states, "never active: ${states.toList()}")
            }
        }

    @Test
    fun `a hidden service is still the unlock notice with the defaults, on the real puck's table`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick(FakeClickOptions.realPuck(), lock = FakeClickLock.HIDDEN)
            val click = rig.click()
            click.start()
            rig.advance(500)
            assertEquals(ClickState.NeedsUnlock(UnlockSignal.SERVICE_HIDDEN), click.state.value)
            assertTrue(ClickOptions().serviceHiddenMeansLocked)
        }

    @Test
    fun `the battery is read once at set-up, 0x64 is 100 percent, and no battery frame is needed`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick()
            val recorded = rig.recording(model = "Click")
            val click = rig.click(factory = recorded.factory)
            assertNull(click.battery.value, "unknown until it is read")
            click.startAndSettle(rig)
            rig.settle()
            assertEquals(100, click.battery.value)
            assertTrue("R 2A19 64" in recorded.compact())
            assertTrue(recorded.compact().none { it.startsWith("N ") }, "no 0x19 frame")
        }

    @Test
    fun `an unknown 0x2A message before the reply does not break the set-up and is reported as unrecognised`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick(FakeClickOptions.realPuck(announces = true))
            val recorded = rig.recording(model = "Click")
            val click = rig.click(factory = recorded.factory)
            val events = collectAll(click.events)
            click.startAndSettle(rig)
            rig.settle()
            assertEquals(ClickState.Active, click.state.value)

            // 23 bytes, opcode 0x2A: one type byte and 22 of body.
            assertEquals(
                listOf(ClickEvent.UnrecognisedFrame(0x2A, 22)),
                events.filterIsInstance<ClickEvent.UnrecognisedFrame>(),
            )
            assertTrue(events.none { it is ClickEvent.MalformedFrame })
            assertEquals(1, events.filterIsInstance<ClickEvent.HandshakeAnswered>().size)

            val lines = recorded.compact()
            val announcement = "N ${ClickUuids.ASYNC} 2A0803121212100818100018D8042205FFFFFFFF1F2800"
            val reply = "I ${ClickUuids.SYNC_TX} 526964654F6E"
            assertTrue(announcement in lines, "$lines")
            assertTrue(lines.indexOf(announcement) < lines.indexOf(reply), "it arrived before the reply")
            assertTrue("S - mtu=251" in lines, "the MTU the real puck grants for the 128 that was asked")
        }

    @Test
    fun `a recording of the real puck's button frames decodes with the existing bit table`() =
        runTest {
            // Synthetic: the frames are the ones the first bench reported (23 08 <varint>; a clear bit is a pressed button),
            // with the all-released frames that follow a press, which are repeats and change nothing.
            val async = ClickUuids.ASYNC
            val released = "2308FFFFFFFF0F"
            val frames =
                listOf(
                    3_000 to "2308EFFFFFFF0F", // A
                    3_095 to released,
                    3_200 to released,
                    5_000 to "2308DFFFFFFF0F", // B
                    5_110 to released,
                    7_000 to "2308BFFFFFFF0F", // Y
                    7_100 to released,
                    9_000 to "2308FFFDFFFF0F", // Minus
                    9_100 to released,
                    11_000 to "2308FFBFFFFF0F", // Plus, shift down
                    11_120 to released,
                    11_230 to released,
                )
            val session =
                buildString {
                    appendLine("# overdrive-ble-session 1")
                    appendLine("# model Click")
                    appendLine("0 S - - connecting")
                    appendLine("200000000 S - 0 connected")
                    appendLine("220000000 D $async - FC82/N")
                    appendLine("220000000 D ${ClickUuids.SYNC_RX} - FC82/Ww")
                    appendLine("220000000 D ${ClickUuids.SYNC_TX} - FC82/RI")
                    appendLine("260000000 C $async 0 0100")
                    appendLine("280000000 C ${ClickUuids.SYNC_TX} 0 0200")
                    appendLine("300000000 W ${ClickUuids.SYNC_RX} 0 526964654F6E")
                    appendLine("350000000 I ${ClickUuids.SYNC_TX} - 526964654F6E")
                    appendLine("370000000 W ${ClickUuids.SYNC_RX} 0 FF0400")
                    for ((millis, bytes) in frames) appendLine("${millis * 1_000_000L} N $async - $bytes")
                    appendLine("15000000000 S - 0 disconnected")
                    appendLine("15000000000 S - - closed")
                }
            val rig = BleRig(this)
            rig.ble.add(CLICK_ID, ReplayPeripheral(BleSessionFormat.parse(session)))
            val click = rig.click()
            val changes = collectAll(click.buttons)
            val events = collectAll(click.events)
            click.start()
            rig.advance(14_000)

            assertEquals(
                listOf(
                    ClickBits.A,
                    0,
                    ClickBits.B,
                    0,
                    ClickBits.Y,
                    0,
                    ClickBits.MINUS,
                    0,
                    ClickBits.PLUS_SHIFT_DOWN,
                    0,
                ),
                changes.map { it.held },
            )
            assertTrue(changes.all { it.pressed.countOneBits() <= 1 }, "no press set two bits")
            assertTrue(events.none { it is ClickEvent.MalformedFrame || it is ClickEvent.UnrecognisedFrame })
            assertEquals(ClickState.Active, click.state.value)
        }

    // endregion

    // region recording (privacy)

    @Test
    fun `a recorded Click session keeps no key, no firmware and no address, and the length of the reply`() =
        runTest {
            val rig = BleRig(this)
            rig.withClick(FakeClickOptions.byNotes(replyTailLength = 64))
            val recorded = rig.recording(model = "Click")
            val click = rig.click(factory = recorded.factory).startAndSettle(rig)
            assertEquals(ClickState.Active, click.state.value)
            rig.settle()
            val reply = recorded.events().first { it.direction.code == 'I' }
            assertEquals(72 * 2, reply.data.length, "the length of the reply survives")
            assertEquals("526964654F6E0203", reply.data.take(16))
            assertTrue(reply.data.drop(16).all { it == '0' }, "the key is zeros")
            val firmware = recorded.events().first { it.characteristic?.shortForm == "2A26" }
            assertEquals("*", firmware.data)
            val text = recorded.lines.joinToString("\n")
            assertFalse(text.contains("312E322E30"), "1.2.0 as hex")
            assertFalse(Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}").containsMatchIn(text))
            assertEquals("# model Click", recorded.lines[1])
        }

    // endregion

    private fun BleRig.fakeClickScript(block: ClickScript.() -> Unit) {
        (ble.peripheral(CLICK_ID) as FakeClick).script(block)
    }
}

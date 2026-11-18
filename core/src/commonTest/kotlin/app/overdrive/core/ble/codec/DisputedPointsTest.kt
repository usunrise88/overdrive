package app.overdrive.core.ble.codec

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The points on which the sources disagree, as explicit cases: each is settled by one default (or one option) that a
 * bench result flips (docs/protocols/ftms-hr.md §2.4, §5, §6.5; bench questions FT 1, FT 8, FT 11). A test here fails
 * the moment a default changes, which is the point: it names exactly what the flip changes.
 */
class DisputedPointsTest {
    // region §2.4: which bit announces Average Speed and which Instantaneous Cadence (FT 1)

    @Test
    fun `the default reads bit 1 as average speed and bit 2 as cadence, as the two decoders do`() {
        assertEquals(IndoorBikeFlagMap.DECODERS, IndoorBikeFlagMap.DEFAULT)
        // IBD2: flags 0x0044 = bit 2 and bit 6.
        val ibd2 = IndoorBikeDataCodec.parse(hex("44 00 C4 09 B4 00 FA 00")).ok()
        assertEquals(90.0, ibd2.instantaneousCadenceRpm)
        assertNull(ibd2.averageSpeedKmh)
        assertEquals(250, ibd2.instantaneousPowerWatts)
    }

    @Test
    fun `flipping to the SIG XML labels turns the same bytes into an average speed and no cadence`() {
        val ibd2 = IndoorBikeDataCodec.parse(hex("44 00 C4 09 B4 00 FA 00"), IndoorBikeFlagMap.SIG_XML_LABELS).ok()
        assertNull(ibd2.instantaneousCadenceRpm, "bit 2 would announce Average Speed")
        assertEquals(1.8, ibd2.averageSpeedKmh, "0x00B4 = 180 hundredths of a km/h")
        assertEquals(250, ibd2.instantaneousPowerWatts, "the fields after bit 2 do not move")
    }

    @Test
    fun `with both bits set the two readings differ by nothing, because the wire order is fixed`() {
        // IBD3: flags 0x0006.
        val bytes = hex("06 00 B8 0B 22 0B A0 00")
        assertEquals(
            IndoorBikeDataCodec.parse(bytes).ok(),
            IndoorBikeDataCodec.parse(bytes, IndoorBikeFlagMap.SIG_XML_LABELS).ok(),
        )
    }

    @Test
    fun `a flag map must use bits 1 and 2 once each`() {
        assertEquals(
            setOf(1, 2),
            setOf(IndoorBikeFlagMap.DECODERS.averageSpeedBit, IndoorBikeFlagMap.DECODERS.instantaneousCadenceBit),
        )
        val error = runCatching { IndoorBikeFlagMap(1, 1) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
    }

    // endregion

    // region §6.5: contact bits (FT 11)

    @Test
    fun `the default follows the SIG XML, only 3 is contact detected`() {
        assertEquals(HeartRateContactRule.SIG_XML, HeartRateContactRule.DEFAULT)
        assertEquals(SensorContact.NOT_DETECTED, HeartRateCodec.parse(hex("04 3C")).ok().contact, "HR4: bits = 2")
        assertEquals(SensorContact.NOT_SUPPORTED, HeartRateCodec.parse(hex("02 46")).ok().contact, "HR5: bits = 1")
        assertEquals(SensorContact.DETECTED, HeartRateCodec.parse(hex("06 96")).ok().contact, "HR3: bits = 3")
        assertEquals(SensorContact.NOT_SUPPORTED, HeartRateCodec.parse(hex("00 48")).ok().contact, "HR1: bits = 0")
    }

    @Test
    fun `flipping to any-nonzero reads HR4 and HR5 as contact, as pycycling does`() {
        val rule = HeartRateContactRule.ANY_NONZERO
        assertEquals(SensorContact.DETECTED, HeartRateCodec.parse(hex("04 3C"), rule).ok().contact)
        assertEquals(SensorContact.DETECTED, HeartRateCodec.parse(hex("02 46"), rule).ok().contact)
        assertEquals(SensorContact.DETECTED, HeartRateCodec.parse(hex("06 96"), rule).ok().contact)
        assertEquals(SensorContact.NOT_SUPPORTED, HeartRateCodec.parse(hex("00 48"), rule).ok().contact)
    }

    // endregion

    // region §5: signedness of the simulation status (FT 8)

    @Test
    fun `the default reads the simulation status as signed, so ST3 is minus two percent`() {
        assertTrue(FitnessMachineStatusCodec.DEFAULT_SIMULATION_SIGNED)
        val st3 =
            FitnessMachineStatusCodec
                .parse(
                    hex("12 00 00 38 FF 28 33"),
                ).ok() as FitnessMachineStatus.SimulationChanged
        assertEquals(-2.0, st3.gradePercent, 1e-9)
    }

    @Test
    fun `flipping to unsigned reads ST3 as 653 percent, as pycycling does`() {
        val st3 =
            FitnessMachineStatusCodec
                .parse(
                    hex("12 00 00 38 FF 28 33"),
                    simulationSigned = false,
                ).ok() as FitnessMachineStatus.SimulationChanged
        assertEquals(653.36, st3.gradePercent, 1e-9)
        assertFalse(st3.gradePercent < 0)
    }

    // endregion
}

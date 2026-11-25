package app.overdrive.core.ble.codec

import app.overdrive.core.device.RrIntervals
import app.overdrive.core.device.SimulationParameters
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The test vectors of docs/protocols/ftms-hr.md §8, which are the oracle for the codecs. IDs match the document. */
class VectorsTest {
    // region 8.1 Indoor Bike Data

    @Test
    fun `IBD1 More Data set speed absent power 200`() {
        val data = IndoorBikeDataCodec.parse(hex("41 00 C8 00")).ok()
        assertEquals(0x0041, data.flags)
        assertTrue(data.moreData)
        assertNull(data.instantaneousSpeedKmh)
        assertEquals(200, data.instantaneousPowerWatts)
    }

    @Test
    fun `IBD2 the common case speed cadence and power`() {
        val data = IndoorBikeDataCodec.parse(hex("44 00 C4 09 B4 00 FA 00")).ok()
        assertEquals(0x0044, data.flags)
        assertFalse(data.moreData)
        assertEquals(25.0, data.instantaneousSpeedKmh)
        assertEquals(90.0, data.instantaneousCadenceRpm)
        assertEquals(250, data.instantaneousPowerWatts)
    }

    @Test
    fun `IBD3 bit 1 is average speed`() {
        val data = IndoorBikeDataCodec.parse(hex("06 00 B8 0B 22 0B A0 00")).ok()
        assertEquals(30.0, data.instantaneousSpeedKmh)
        assertEquals(28.5, data.averageSpeedKmh)
        assertEquals(80.0, data.instantaneousCadenceRpm)
    }

    @Test
    fun `IBD4 total distance is a 24-bit value`() {
        val data = IndoorBikeDataCodec.parse(hex("10 00 E8 03 39 30 00")).ok()
        assertEquals(10.0, data.instantaneousSpeedKmh)
        assertEquals(12345, data.totalDistanceMetres)
    }

    @Test
    fun `IBD5 heart rate in the bike data`() {
        val data = IndoorBikeDataCodec.parse(hex("00 02 00 00 96")).ok()
        assertEquals(0.0, data.instantaneousSpeedKmh)
        assertEquals(150, data.heartRateBpm)
    }

    @Test
    fun `IBD6 power is signed`() {
        val data = IndoorBikeDataCodec.parse(hex("41 00 EC FF")).ok()
        assertEquals(-20, data.instantaneousPowerWatts)
    }

    @Test
    fun `IBD7 expended energy triple`() {
        val data = IndoorBikeDataCodec.parse(hex("00 01 00 00 2C 01 E8 03 10")).ok()
        assertEquals(300, data.totalEnergyKcal)
        assertEquals(1000, data.energyPerHourKcal)
        assertEquals(16, data.energyPerMinuteKcal)
    }

    @Test
    fun `IBD8 elapsed and remaining time`() {
        val data = IndoorBikeDataCodec.parse(hex("00 18 00 00 F2 02 B0 04")).ok()
        assertEquals(754, data.elapsedTimeSeconds)
        assertEquals(1200, data.remainingTimeSeconds)
    }

    @Test
    fun `IBD9 resistance average power and MET`() {
        val data = IndoorBikeDataCodec.parse(hex("A0 04 00 00 19 00 B4 00 2D")).ok()
        assertEquals(0x04A0, data.flags)
        assertEquals(25, data.resistanceLevel)
        assertEquals(180, data.averagePowerWatts)
        assertEquals(4.5, data.metabolicEquivalent)
    }

    @Test
    fun `IBD10 the maximal 30-byte notification`() {
        val bytes = hex("FE 1F 10 0E 7A 0D B4 00 AB 00 D2 04 00 1E 00 2C 01 18 01 F4 01 08 07 1E A0 50 08 07 58 02")
        assertEquals(30, bytes.size)
        val data = IndoorBikeDataCodec.parse(bytes).ok()
        assertEquals(
            IndoorBikeData(
                flags = 0x1FFE,
                moreData = false,
                instantaneousSpeedKmh = 36.0,
                averageSpeedKmh = 34.5,
                instantaneousCadenceRpm = 90.0,
                averageCadenceRpm = 85.5,
                totalDistanceMetres = 1234,
                resistanceLevel = 30,
                instantaneousPowerWatts = 300,
                averagePowerWatts = 280,
                totalEnergyKcal = 500,
                energyPerHourKcal = 1800,
                energyPerMinuteKcal = 30,
                heartRateBpm = 160,
                metabolicEquivalent = 8.0,
                elapsedTimeSeconds = 1800,
                remainingTimeSeconds = 600,
            ),
            data,
        )
    }

    @Test
    fun `reserved flag bits and trailing bytes are ignored`() {
        val data = IndoorBikeDataCodec.parse(hex("44 E0 C4 09 B4 00 FA 00 DE AD")).ok()
        assertEquals(250, data.instantaneousPowerWatts)
        assertEquals(90.0, data.instantaneousCadenceRpm)
    }

    // endregion

    // region 8.2 Heart Rate Measurement

    @Test
    fun `HR1 eight-bit heart rate no contact support`() {
        val hr = HeartRateCodec.parse(hex("00 48")).ok()
        assertEquals(72, hr.bpm)
        assertNull(hr.rrIntervalsRaw)
        assertNull(hr.energyKilojoules)
    }

    @Test
    fun `HR2 sixteen-bit heart rate`() {
        assertEquals(200, HeartRateCodec.parse(hex("01 C8 00")).ok().bpm)
    }

    @Test
    fun `HR3 to HR5 contact bits`() {
        assertEquals(150, HeartRateCodec.parse(hex("06 96")).ok().bpm)
        assertEquals(60, HeartRateCodec.parse(hex("04 3C")).ok().bpm)
        assertEquals(70, HeartRateCodec.parse(hex("02 46")).ok().bpm)
    }

    @Test
    fun `HR6 two RR intervals raw and converted by the domain layer`() {
        val hr = HeartRateCodec.parse(hex("16 48 00 04 00 02")).ok()
        assertEquals(72, hr.bpm)
        assertEquals(listOf(1024, 512), hr.rrIntervalsRaw)
        assertEquals(listOf(1000, 500), RrIntervals.toMillis(hr.rrIntervalsRaw!!))
    }

    @Test
    fun `HR7 sixteen-bit heart rate with RR 900 rounds to 879 ms`() {
        val hr = HeartRateCodec.parse(hex("17 96 00 84 03 00 04")).ok()
        assertEquals(150, hr.bpm)
        assertEquals(listOf(900, 1024), hr.rrIntervalsRaw)
        assertEquals(listOf(879, 1000), RrIntervals.toMillis(hr.rrIntervalsRaw!!))
        assertEquals(SensorContact.DETECTED, hr.contact)
    }

    @Test
    fun `HR8 energy comes before the RR intervals`() {
        val hr = HeartRateCodec.parse(hex("18 78 64 00 00 04")).ok()
        assertEquals(120, hr.bpm)
        assertEquals(100, hr.energyKilojoules)
        assertEquals(listOf(1024), hr.rrIntervalsRaw)
    }

    @Test
    fun `HR9 energy only`() {
        val hr = HeartRateCodec.parse(hex("08 5A 58 02")).ok()
        assertEquals(90, hr.bpm)
        assertEquals(600, hr.energyKilojoules)
        assertNull(hr.rrIntervalsRaw)
    }

    @Test
    fun `HR10 the RR flag with no RR bytes is an empty list not an error and not null`() {
        val hr = HeartRateCodec.parse(hex("10 50")).ok()
        assertEquals(80, hr.bpm)
        assertEquals(emptyList(), hr.rrIntervalsRaw)
        assertFalse(hr.strayRrByte)
    }

    @Test
    fun `HR11 a stray byte after the last RR interval is ignored and reported`() {
        val hr = HeartRateCodec.parse(hex("10 50 00 04 01")).ok()
        assertEquals(listOf(1024), hr.rrIntervalsRaw)
        assertTrue(hr.strayRrByte)
    }

    @Test
    fun `RR conversion rounds halves up and keeps the domain rule out of the codec`() {
        assertEquals(879, RrIntervals.rawToMillis(900))
        assertEquals(1000, RrIntervals.rawToMillis(1024))
        assertEquals(500, RrIntervals.rawToMillis(512))
        // 1.024 raw counts per ms: 1 count is 0.977 ms, 513 is 500.98 -> 501; 1025 is 1000.98 -> 1001.
        assertEquals(1, RrIntervals.rawToMillis(1))
        assertEquals(501, RrIntervals.rawToMillis(513))
        assertEquals(1001, RrIntervals.rawToMillis(1025))
        // Exactly half: 64 counts are 62.5 ms and halves go up.
        assertEquals(63, RrIntervals.rawToMillis(64))
        assertEquals(listOf(879), RrIntervals.toMillis(listOf(900, 0)), "a raw 0 is a glitch and is dropped")
    }

    // endregion

    // region 8.3 Control Point commands

    private fun encode(command: ControlPointCommand) = ControlPointCodec.encode(command)

    @Test
    fun `CP1 to CP7 are the commands without parameters or with a power`() {
        assertContentEquals(hex("00"), encode(ControlPointCommand.RequestControl))
        assertContentEquals(hex("01"), encode(ControlPointCommand.Reset))
        assertContentEquals(hex("05 C8 00"), encode(ControlPointCommand.SetTargetPower(200)))
        assertContentEquals(hex("05 DC 05"), encode(ControlPointCommand.SetTargetPower(1500)))
        assertContentEquals(hex("07"), encode(ControlPointCommand.StartOrResume))
        assertContentEquals(hex("08 01"), encode(ControlPointCommand.Stop))
        assertContentEquals(hex("08 02"), encode(ControlPointCommand.Pause))
    }

    @Test
    fun `CP8 and CP9 simulation parameters`() {
        assertContentEquals(
            hex("11 00 00 5E 01 28 33"),
            encode(
                ControlPointCommand.SetSimulation(
                    SimulationParameters(gradePercent = 3.5, crr = 0.004, windResistance = 0.51),
                ),
            ),
        )
        assertContentEquals(
            hex("11 00 00 38 FF 28 33"),
            encode(
                ControlPointCommand.SetSimulation(
                    SimulationParameters(gradePercent = -2.0, crr = 0.004, windResistance = 0.51),
                ),
            ),
        )
    }

    @Test
    fun `simulation parameters at the edges of their ranges encode without overflow`() {
        val top =
            encode(
                ControlPointCommand.SetSimulation(
                    SimulationParameters(200.0, SimulationParameters.MAX_CRR, SimulationParameters.MAX_WIND_RESISTANCE),
                ),
            )
        assertContentEquals(hex("11 00 00 20 4E FF FF"), top, "20000 = 0x4E20")
        val bottom = encode(ControlPointCommand.SetSimulation(SimulationParameters(-200.0, 0.0, 0.0)))
        assertContentEquals(hex("11 00 00 E0 B1 00 00"), bottom, "-20000 = 0xB1E0")
    }

    @Test
    fun `the engine's real flat and gear commands encode as the gear model intends`() {
        // 15 % at 0.0040 and 0.2144 kg/m: raw 1500 = 0x05DC, 40, 21.
        val steep = encode(ControlPointCommand.SetSimulation(SimulationParameters(15.0, 0.004, 0.2144)))
        assertContentEquals(hex("11 00 00 DC 05 28 15"), steep)
    }

    // endregion

    // region 8.4 Response Code

    @Test
    fun `RC1 to RC5 response codes`() {
        val rc1 = ControlPointCodec.parseResponse(hex("80 00 01")).ok()
        assertEquals(0x00, rc1.requestOpcode)
        assertEquals(ControlPointResult.SUCCESS, rc1.result)
        assertTrue(rc1.isSuccess)

        val rc2 = ControlPointCodec.parseResponse(hex("80 05 02")).ok()
        assertEquals(0x05, rc2.requestOpcode)
        assertEquals(ControlPointResult.OP_CODE_NOT_SUPPORTED, rc2.result)

        val rc3 = ControlPointCodec.parseResponse(hex("80 11 05")).ok()
        assertEquals(0x11, rc3.requestOpcode)
        assertEquals(ControlPointResult.CONTROL_NOT_PERMITTED, rc3.result)

        assertEquals(ControlPointResult.INVALID_PARAMETER, ControlPointCodec.parseResponse(hex("80 05 03")).ok().result)

        val rc5 = ControlPointCodec.parseResponse(hex("80 08 04")).ok()
        assertEquals(0x08, rc5.requestOpcode)
        assertEquals(ControlPointResult.OPERATION_FAILED, rc5.result)
        assertFalse(rc5.isSuccess)
    }

    @Test
    fun `an unlisted result code keeps its raw value`() {
        val response = ControlPointCodec.parseResponse(hex("80 05 7F")).ok()
        assertEquals(ControlPointResult.UNKNOWN, response.result)
        assertEquals(0x7F, response.resultCode)
    }

    // endregion

    // region 8.5 Fitness Machine Status

    private fun status(text: String) = FitnessMachineStatusCodec.parse(hex(text)).ok()

    @Test
    fun `ST1 to ST7 status notifications`() {
        assertEquals(FitnessMachineStatus.TargetPowerChanged(200), status("08 C8 00"))
        val st2 = status("12 00 00 5E 01 28 33") as FitnessMachineStatus.SimulationChanged
        assertEquals(3.5, st2.gradePercent, 1e-9)
        assertEquals(0.0, st2.windSpeedMetresPerSecond, 1e-9)
        assertEquals(0.004, st2.crr, 1e-9)
        assertEquals(0.51, st2.windResistanceKgPerMetre, 1e-9)
        val st3 = status("12 00 00 38 FF 28 33") as FitnessMachineStatus.SimulationChanged
        assertEquals(-2.0, st3.gradePercent, 1e-9)
        assertEquals(FitnessMachineStatus.ControlPermissionLost, status("FF"))
        assertEquals(FitnessMachineStatus.StoppedByUser, status("02 01"))
        assertEquals(FitnessMachineStatus.PausedByUser, status("02 02"))
        assertEquals(FitnessMachineStatus.StartedByUser, status("04"))
    }

    @Test
    fun `the other status opcodes of the table`() {
        assertEquals(FitnessMachineStatus.Reset, status("01"))
        assertEquals(FitnessMachineStatus.StoppedBySafetyKey, status("03"))
        assertEquals(FitnessMachineStatus.TargetPowerChanged(-5), status("08 FB FF"))
        assertEquals(
            FitnessMachineStatus.Other(0x15, listOf(0x5A, 0x00)),
            status("15 5A 00"),
            "targeted cadence is not used",
        )
        assertEquals(
            FitnessMachineStatus.Other(0x7E, emptyList()),
            status("7E"),
            "an unknown opcode is kept, not an error",
        )
    }

    // endregion

    // region 8.6 Fitness Machine Feature

    @Test
    fun `FMF1 and FMF2 feature words`() {
        val features = FitnessMachineFeatureCodec.parse(hex("06 54 00 00 08 20 00 00")).ok()
        assertEquals(0x00005406L, features.machineFeatures)
        assertEquals(0x00002008L, features.targetSettingFeatures)
        assertTrue(features.cadenceSupported)
        assertTrue(features.totalDistanceSupported)
        assertTrue(features.heartRateSupported)
        assertTrue(features.powerMeasurementSupported)
        assertTrue(features.powerTargetSettingSupported)
        assertTrue(features.indoorBikeSimulationSupported)
        assertTrue(features.supportsErg)
        assertTrue(features.supportsGears)
        assertEquals(emptyList(), features.missingForRide)
    }

    @Test
    fun `a trainer without power target setting is reported as lacking it`() {
        val features = FitnessMachineFeatureCodec.parse(hex("06 54 00 00 00 20 00 00")).ok()
        assertFalse(features.supportsErg)
        assertEquals(listOf(RequiredFeature.POWER_TARGET_SETTING), features.missingForRide)
        val none = FitnessMachineFeatureCodec.parse(hex("00 00 00 00 00 00 00 00")).ok()
        assertEquals(RequiredFeature.entries, none.missingForRide)
    }

    @Test
    fun `a high feature bit is read as an unsigned 32-bit word`() {
        val features = FitnessMachineFeatureCodec.parse(hex("00 00 00 80 00 00 00 80")).ok()
        assertEquals(0x80000000L, features.machineFeatures)
        assertEquals(0x80000000L, features.targetSettingFeatures)
    }

    // endregion
}

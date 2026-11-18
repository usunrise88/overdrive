package app.overdrive.ride.overlay

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The foreground service types of the overlay service (ADR 0006, ADR 0010): the decision for a missing Bluetooth permission
 * on Android 14 and later, where `startForeground` throws if a type's prerequisite is not held.
 */
class ForegroundServiceTypesTest {
    private val android14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private val android15 = Build.VERSION_CODES.VANILLA_ICE_CREAM
    private val android13 = Build.VERSION_CODES.TIRAMISU
    private val android12 = Build.VERSION_CODES.S

    @Test
    fun withNearbyDevicesAllowedBothTypesAreUsedFromAndroid14() {
        val both = ForegroundServiceTypes.SPECIAL_USE or ForegroundServiceTypes.CONNECTED_DEVICE
        assertEquals(both, ForegroundServiceTypes.choose(android14, bluetoothConnectGranted = true))
        assertEquals(both, ForegroundServiceTypes.choose(android15, bluetoothConnectGranted = true))
    }

    @Test
    fun withoutItOnlySpecialUseIsUsedSoTheCallDoesNotThrow() {
        assertEquals(
            ForegroundServiceTypes.SPECIAL_USE,
            ForegroundServiceTypes.choose(android14, bluetoothConnectGranted = false),
        )
        assertEquals(
            ForegroundServiceTypes.SPECIAL_USE,
            ForegroundServiceTypes.choose(android15, bluetoothConnectGranted = false),
        )
    }

    @Test
    fun beforeAndroid14ThereIsNoTypeArgumentWhatever() {
        assertEquals(
            ForegroundServiceTypes.NONE,
            ForegroundServiceTypes.choose(android13, bluetoothConnectGranted = true),
        )
        assertEquals(
            ForegroundServiceTypes.NONE,
            ForegroundServiceTypes.choose(android12, bluetoothConnectGranted = false),
        )
    }

    @Test
    fun theTwoTypesAreDistinctBits() {
        assertTrue(ForegroundServiceTypes.SPECIAL_USE != ForegroundServiceTypes.CONNECTED_DEVICE)
        assertEquals(0, ForegroundServiceTypes.SPECIAL_USE and ForegroundServiceTypes.CONNECTED_DEVICE)
    }

    @Test
    fun theSystemRefusingTheConnectedDeviceTypeIsAnsweredWithSpecialUseAlone() {
        // The permission was revoked between the check and the call: the system throws, the overlay still starts.
        val calls = mutableListOf<Int>()
        var reported: SecurityException? = null
        ForegroundServiceTypes.enter(
            android15,
            bluetoothConnectGranted = true,
            onFallback = { reported = it },
        ) { types ->
            calls += types
            if (types and ForegroundServiceTypes.CONNECTED_DEVICE !=
                0
            ) {
                throw SecurityException("needs BLUETOOTH_CONNECT")
            }
        }
        assertEquals(
            listOf(
                ForegroundServiceTypes.SPECIAL_USE or ForegroundServiceTypes.CONNECTED_DEVICE,
                ForegroundServiceTypes.SPECIAL_USE,
            ),
            calls,
        )
        assertTrue(reported != null)
    }

    @Test
    fun aCallThatSucceedsIsMadeOnceAndNothingIsReported() {
        val calls = mutableListOf<Int>()
        ForegroundServiceTypes.enter(
            android15,
            bluetoothConnectGranted = false,
            onFallback = { error("not expected") },
        ) {
            calls +=
                it
        }
        assertEquals(listOf(ForegroundServiceTypes.SPECIAL_USE), calls)
    }

    @Test
    fun aRefusalOfTheWeakestTypesIsNotSwallowed() {
        var calls = 0
        try {
            ForegroundServiceTypes.enter(
                android15,
                bluetoothConnectGranted = false,
                onFallback = { error("not expected") },
            ) {
                calls++
                throw SecurityException("no")
            }
            error("the exception should have been rethrown")
        } catch (expected: SecurityException) {
            assertEquals(1, calls)
        }
        // Before Android 14 there is nothing to fall back from either.
        try {
            ForegroundServiceTypes.enter(
                android13,
                bluetoothConnectGranted = true,
                onFallback = { error("not expected") },
            ) {
                throw SecurityException("no")
            }
            error("the exception should have been rethrown")
        } catch (expected: SecurityException) {
            // as it should
        }
    }

    @Test
    fun aRefusedPairFallsBackToSpecialUseAndSpecialUseHasNothingWeaker() {
        val both = ForegroundServiceTypes.choose(android15, bluetoothConnectGranted = true)
        assertEquals(ForegroundServiceTypes.SPECIAL_USE, ForegroundServiceTypes.fallback(android15, both))
        assertNull(
            "nothing weaker: the refusal is not swallowed",
            ForegroundServiceTypes.fallback(android15, ForegroundServiceTypes.SPECIAL_USE),
        )
        assertNull(
            "before Android 14 there is no type to fall back from",
            ForegroundServiceTypes.fallback(android13, ForegroundServiceTypes.NONE),
        )
    }
}

package app.overdrive.data.health

import android.content.Context
import android.os.Build
import android.os.RemoteException
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthChanges
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthPage
import app.overdrive.core.health.HealthPermissionException
import app.overdrive.core.health.HealthSource
import app.overdrive.core.health.HealthUnavailableException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Health Connect through the AndroidX client (spec §6.2): no Google Play Services, nothing but the phone's own Health Connect, and
 * read only (the manifest names read permissions and no write one). This class is thin on purpose: the reading logic, the mapping and
 * the outbox are in `core` and tested there with a fake [HealthSource]; what only a phone can settle is in `docs/bench-checklist.md`.
 *
 * - **Availability** is the SDK's status: on Android 12 and 13 Health Connect is an app from the Play Store (missing or too old), from
 *   Android 14 it is part of the system.
 * - **Access** is re-read at every use, with whether the platform has the history and background permissions at all (older Health
 *   Connect versions do not).
 * - **Failures** are the two [HealthSource] knows: a refused read (`SecurityException`) and a Health Connect that cannot answer
 *   (`RemoteException`, `IOException`, `IllegalStateException`). Their messages are dropped: they could quote a record.
 * - **An unusable changes token** (expired, or one Health Connect no longer knows) reads as expired: the reader falls back to a window
 *   since the last read, which the service takes as duplicates.
 */
class HealthConnectSource(
    context: Context,
) : HealthSource {
    private val appContext = context.applicationContext
    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(appContext) }

    override suspend fun availability(): HealthAvailability =
        when (HealthConnectClient.getSdkStatus(appContext)) {
            HealthConnectClient.SDK_AVAILABLE -> {
                HealthAvailability.AVAILABLE
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                HealthAvailability.UPDATE_REQUIRED
            }

            else -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HealthAvailability.NOT_INSTALLED
                } else {
                    HealthAvailability.NOT_SUPPORTED
                }
            }
        }

    override suspend fun access(): HealthAccess =
        guarded {
            val granted = client.permissionController.getGrantedPermissions()
            HealthAccess(
                granted = HealthDataType.entries.filter { HealthPermissions.read(it) in granted }.toSet(),
                history = HealthPermissions.HISTORY in granted,
                background = HealthPermissions.BACKGROUND in granted,
                historyNeeded = featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY),
                backgroundNeeded = featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND),
            )
        }

    /**
     * What one request asks for: a read permission for every type, and the history and background permissions where this Health Connect
     * has them. One flow, one dialog (design/companion.md §4.10).
     */
    suspend fun permissionsToRequest(): Set<String> =
        buildSet {
            for (type in HealthDataType.entries) add(HealthPermissions.read(type))
            if (featureAvailable(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)) add(HealthPermissions.HISTORY)
            if (featureAvailable(
                    HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND,
                )
            ) {
                add(HealthPermissions.BACKGROUND)
            }
        }

    private fun featureAvailable(feature: Int): Boolean =
        try {
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        } catch (e: RuntimeException) {
            false
        }

    override suspend fun readPage(
        type: HealthDataType,
        fromMillis: Long,
        toMillis: Long,
        pageToken: String?,
    ): HealthPage =
        guarded {
            when (type) {
                HealthDataType.RESTING_HEART_RATE -> {
                    page(RestingHeartRateRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.SLEEP -> {
                    page(SleepSessionRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.RESPIRATORY_RATE -> {
                    page(RespiratoryRateRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.OXYGEN_SATURATION -> {
                    page(OxygenSaturationRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.HEART_RATE -> {
                    page(HeartRateRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.EXERCISE -> {
                    page(ExerciseSessionRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.WEIGHT -> {
                    page(WeightRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.HEART_RATE_VARIABILITY -> {
                    page(HeartRateVariabilityRmssdRecord::class, fromMillis, toMillis, pageToken)
                }

                HealthDataType.STEPS -> {
                    page(StepsRecord::class, fromMillis, toMillis, pageToken)
                }
            }
        }

    private suspend fun <T : Record> page(
        kind: KClass<T>,
        fromMillis: Long,
        toMillis: Long,
        pageToken: String?,
    ): HealthPage {
        val response =
            client.readRecords(
                ReadRecordsRequest(
                    recordType = kind,
                    timeRangeFilter =
                        TimeRangeFilter.between(
                            Instant.ofEpochMilli(fromMillis),
                            Instant.ofEpochMilli(toMillis),
                        ),
                    pageSize = PAGE_SIZE,
                    pageToken = pageToken,
                ),
            )
        return HealthPage(response.records.mapNotNull { it.toHealthRecord() }, response.pageToken)
    }

    override suspend fun newChangesToken(types: Set<HealthDataType>): String =
        guarded {
            client.getChangesToken(
                ChangesTokenRequest(recordTypes = types.map { HealthPermissions.recordClass(it) }.toSet()),
            )
        }

    override suspend fun changes(token: String): HealthChanges =
        guarded {
            val response =
                try {
                    client.getChanges(token)
                } catch (e: IOException) {
                    return@guarded expired(token)
                } catch (e: IllegalArgumentException) {
                    return@guarded expired(token)
                }
            HealthChanges(
                upserts =
                    response.changes.filterIsInstance<UpsertionChange>().mapNotNull {
                        it.record.toHealthRecord()
                    },
                deletions = response.changes.count { it is DeletionChange },
                nextToken = response.nextChangesToken,
                hasMore = response.hasMore,
                tokenExpired = response.changesTokenExpired,
            )
        }

    private fun expired(token: String) = HealthChanges(emptyList(), 0, token, hasMore = false, tokenExpired = true)

    /** Runs [block], turning Health Connect's failures into the two kinds [HealthSource] names, without their words. */
    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            throw HealthPermissionException()
        } catch (e: RemoteException) {
            throw HealthUnavailableException()
        } catch (e: IOException) {
            throw HealthUnavailableException()
        } catch (e: IllegalStateException) {
            throw HealthUnavailableException()
        }

    private companion object {
        /** Health Connect allows up to 5000 records a page; a page of long heart-rate records is heavy, so fewer. */
        const val PAGE_SIZE = 500
    }
}

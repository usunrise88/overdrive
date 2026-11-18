package app.overdrive.core.click

import app.overdrive.core.ble.Advertisement
import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.ScanFilter

/** Which puck of a Zwift Click v2 an advertisement belongs to (docs/protocols/click-v2.md §1). */
public enum class ClickSide {
    RIGHT,
    LEFT,
}

/** Which of the two services a Click was found under (docs/protocols/click-v2.md §2). */
public enum class ClickService {
    /** `FC82`: the service a Click v2 advertises. */
    CURRENT,

    /** `00000001-19CA-…`: the older service of Click v1 and Play, a fallback. */
    LEGACY,
}

/** An advertisement recognised as a Click v2. */
public data class ClickMatch(
    val side: ClickSide,
    /** The service that was advertised, or null when none was and `requireAdvertisedService` is off. */
    val service: ClickService?,
)

/**
 * Recognises a Click v2 in a scan (docs/protocols/click-v2.md §1): by the advertised service **and** the manufacturer data,
 * never by name. Pure; the rules are the notes' and every guess in them is an option of [ClickOptions].
 *
 * | Rule | Notes | Option |
 * |---|---|---|
 * | the service `FC82` is advertised, or the older service when the fallback is on | §1, §2 | `requireAdvertisedService`, `acceptLegacyService` |
 * | manufacturer data of company `0x094A` is present | §1 | none |
 * | its first byte is `0x0A` (right) or `0x0B` (left); the notes call both "unconfirmed" | §1 | `rightSideByte`, `leftSideByte` |
 * | the side is one the app wants (the right puck by default) | §4.7 | `acceptedSides` |
 */
public object ClickAdvertisements {
    /** The scan filter that finds Clicks: the services of [options], or everything when the service is not required. */
    public fun scanFilter(options: ClickOptions = ClickOptions()): ScanFilter =
        if (!options.requireAdvertisedService) {
            ScanFilter()
        } else {
            ScanFilter(
                buildSet {
                    add(ClickUuids.SERVICE)
                    if (options.acceptLegacyService) add(ClickUuids.LEGACY_SERVICE)
                },
            )
        }

    /** The match for [advertisement], or null when it is not a Click v2 this app wants. */
    public fun recognise(
        advertisement: Advertisement,
        options: ClickOptions = ClickOptions(),
    ): ClickMatch? {
        val service =
            when {
                ClickUuids.SERVICE in advertisement.serviceUuids -> ClickService.CURRENT

                options.acceptLegacyService &&
                    ClickUuids.LEGACY_SERVICE in advertisement.serviceUuids -> ClickService.LEGACY

                else -> null
            }
        if (service == null && options.requireAdvertisedService) return null
        val type =
            advertisement.manufacturerData
                .firstOrNull { it.companyId == ClickUuids.MANUFACTURER_ID }
                ?.firstByte ?: return null
        val side =
            when (type) {
                options.rightSideByte -> ClickSide.RIGHT
                options.leftSideByte -> ClickSide.LEFT
                else -> return null
            }
        return if (side in options.acceptedSides) ClickMatch(side, service) else null
    }
}

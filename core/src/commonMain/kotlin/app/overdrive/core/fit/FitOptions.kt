package app.overdrive.core.fit

/**
 * Every choice of the FIT writer that `docs/protocols/fit.md` (section 8) leaves open until a file has been imported
 * into intervals.icu (and Strava). Each default is what the document recommends; a bench finding is a change of one
 * default here. The numbers are values of Garmin's published profile, listed in fit.md section 4.9.
 */
public data class FitOptions(
    /**
     * The protocol version byte of the file header (fit.md 8.1). `0x20` is protocol 2.0 as Garmin's Java SDK writes it;
     * its JavaScript SDK writes `0x02`.
     */
    val protocolVersion: Int = PROTOCOL_VERSION_2_0,
    /** The profile version in the file header, `major * 1000 + minor` (fit.md 1.1). It only records which profile was followed. */
    val profileVersion: Int = PROFILE_VERSION,
    /** `sport` of the laps and the session (fit.md 4.4, 4.5): 2 is cycling. */
    val sport: Int = SPORT_CYCLING,
    /**
     * `sub_sport` of the laps and the session (fit.md 8.2): 58 is virtual activity, which spec §4.8 asks for; 6 is
     * indoor cycling, which Strava may want for a trainer ride.
     */
    val subSport: Int = SUB_SPORT_VIRTUAL_ACTIVITY,
    /** Which of the two speed fields each record carries (fit.md 8.3). */
    val speedFields: FitSpeedFields = FitSpeedFields.BOTH,
    /** Where an `hrv` message goes relative to the record of the second it covers (fit.md 8.4). */
    val hrvPlacement: FitHrvPlacement = FitHrvPlacement.AFTER_RECORD,
    /** `file_id.manufacturer` (fit.md 8.5): 255 is `development`, we have no registered id. */
    val manufacturer: Int = MANUFACTURER_DEVELOPMENT,
    /** `file_id.product`: ours to choose. */
    val product: Int = 1,
    /** `file_id.serial_number`, a `uint32z`, so it must not be 0 (fit.md section 2). */
    val serialNumber: Long = 1,
    /**
     * Whether the file has `event` messages for the timer start and stop (fit.md 8.6). The `activity` message and the
     * last lap are always written.
     */
    val timerEvents: Boolean = true,
    /** `lap_trigger` of every lap but the last, which is `session_end` (7). 0 is `manual` (fit.md 4.4). */
    val stepLapTrigger: Int = LAP_TRIGGER_MANUAL,
    /**
     * What a record's timestamp is. The default stamps the sample of second *k* with the start of that second, so
     * the first record is at the ride's start time as in the worked example of fit.md 6. See [FitRecordStamp].
     */
    val recordStamp: FitRecordStamp = FitRecordStamp.SECOND_START,
    /**
     * Seconds east of UTC at the ride's start, for `activity.local_timestamp` (fit.md section 3). The ride record holds
     * UTC only, so the caller supplies the device's offset; the default is UTC.
     */
    val utcOffsetSeconds: Int = 0,
    /**
     * Whether the session carries the load fields (normalised power, TSS, intensity factor, work) when the ride has
     * a normalised power (fit.md 4.5). A ride under 30 s of power has none and the fields are left out.
     */
    val loadFields: Boolean = true,
    /**
     * Whether a ride on a route that has coordinates writes `position_lat` and `position_long` into its records (fit.md 4.3,
     * spec §11.4): what should make Strava draw a map. A route with no coordinates (a profile) never writes positions, and a ride
     * with no route has none in this file at all. Open until a real upload (fit.md 8.10).
     */
    val positions: Boolean = true,
    /**
     * Whether a ride on a route writes the route's altitude into its records, `altitude` and `enhanced_altitude` with the same value
     * (fit.md 4.3), a profile's included. Open until a real upload (fit.md 8.10).
     */
    val altitude: Boolean = true,
) {
    init {
        require(protocolVersion in 0..BYTE_MAX) { "Protocol version is one byte, got $protocolVersion" }
        require(profileVersion in 0..SHORT_MAX) { "Profile version is two bytes, got $profileVersion" }
        require(serialNumber in 1..UINT32_MAX) { "The serial number is a non-zero 32-bit value, got $serialNumber" }
        require(manufacturer in 0..SHORT_MAX && product in 0..SHORT_MAX) { "Manufacturer and product are 16-bit" }
        require(sport in 0..BYTE_MAX && subSport in 0..BYTE_MAX && stepLapTrigger in 0..BYTE_MAX) {
            "Sport, sub-sport and lap trigger are enums of one byte"
        }
    }

    public companion object {
        /** Protocol 2.0 in the header byte, as Garmin's Java SDK writes it (fit.md 1.1). */
        public const val PROTOCOL_VERSION_2_0: Int = 0x20

        /** Profile 21.222 as `major * 1000 + minor` (fit.md section 0). */
        public const val PROFILE_VERSION: Int = 21222

        public const val SPORT_CYCLING: Int = 2
        public const val SUB_SPORT_INDOOR_CYCLING: Int = 6
        public const val SUB_SPORT_VIRTUAL_ACTIVITY: Int = 58
        public const val MANUFACTURER_DEVELOPMENT: Int = 255
        public const val LAP_TRIGGER_MANUAL: Int = 0

        private const val BYTE_MAX = 0xFF
        private const val SHORT_MAX = 0xFFFF
        private const val UINT32_MAX = 0xFFFFFFFFL
    }
}

/** Which speed fields a `record` message has (fit.md 4.3, open question 8.3). Both are in m/s with scale 1000. */
public enum class FitSpeedFields {
    /** `speed` (6, 16 bits) and `enhanced_speed` (73, 32 bits) with the same value: the recommendation. */
    BOTH,

    /** Only `speed`, which older readers take. */
    SPEED,

    /** Only `enhanced_speed`, without the 65.5 m/s limit. */
    ENHANCED_SPEED,
}

/** Where `hrv` messages go (fit.md 4.7, open question 8.4): the profile gives them no timestamp. */
public enum class FitHrvPlacement {
    /** Just after the record of the second the intervals arrived in. */
    AFTER_RECORD,

    /** Just before it. */
    BEFORE_RECORD,
}

/**
 * The time a sample is stamped with. A sample of second *k* (offset *k*) holds the readings at the end of the second
 * that began *k* − 1 seconds after the ride's start.
 */
public enum class FitRecordStamp {
    /**
     * Stamped with the start of its second, `start + k − 1`: the first record is at the start time, and a lap or the
     * session ends at the timestamp of its last record, as in fit.md section 6.
     */
    SECOND_START,

    /** Stamped with the end of its second, `start + k`: the last record is at start plus the duration. */
    SECOND_END,
}

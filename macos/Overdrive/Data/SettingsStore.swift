import Foundation
import OverdriveCore

public enum DeviceSourceMode: String, Codable, Sendable, CaseIterable {
    case bluetooth
    case simulated
}

public enum MacDeviceRole: String, Codable, Sendable, CaseIterable {
    case trainer
    case strap
    case click

    public var title: String {
        switch self {
        case .trainer: return "Trainer"
        case .strap: return "Heart rate"
        case .click: return "Click"
        }
    }

    public var fallbackName: String {
        switch self {
        case .trainer: return "Trainer"
        case .strap: return "Heart-rate strap"
        case .click: return "Zwift Click"
        }
    }
}

public struct RememberedDeviceRecord: Codable, Equatable, Sendable {
    public let id: String
    public let name: String

    public init(id: String, name: String) {
        self.id = id
        self.name = Self.clean(name)
    }

    public static func clean(_ raw: String) -> String {
        let cleaned = raw
            .filter { !$0.isNewline }
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        return String(cleaned.prefix(40))
    }
}

public struct RememberedDevicesRecord: Codable, Equatable, Sendable {
    public var trainer: RememberedDeviceRecord?
    public var strap: RememberedDeviceRecord?
    public var click: RememberedDeviceRecord?

    public subscript(role: MacDeviceRole) -> RememberedDeviceRecord? {
        get {
            switch role {
            case .trainer: return trainer
            case .strap: return strap
            case .click: return click
            }
        }
        set {
            switch role {
            case .trainer: trainer = newValue
            case .strap: strap = newValue
            case .click: click = newValue
            }
        }
    }
}

public struct MacAppSettings: Codable, Equatable, Sendable {
    // Overlay
    public var overlayMode: OverlayMode = .compact
    public var overlayOpacity: Double = OverdriveDimens.overlayOpacityDefault
    public var overlayBlur: Bool = false
    public var overlayExpandAtStepChange: Bool = true
    public var overlayAlwaysShow: Bool = false
    public var overlayX: Double? = nil
    public var overlayY: Double? = nil

    // Rider & gears
    public var ftp: Int = 200
    public var gearCount: Int = 24
    public var gradeStepPercent: Double = 1.0
    public var routeDifficultyPercent: Int = 50

    // Physics
    public var systemMassKg: Double = 80.0
    public var cdA: Double = 0.32
    public var crr: Double = 0.004

    // Ride
    public var cadenceGuardEnabled: Bool = true
    public var cadenceFloorRpm: Int = 60
    public var liveCoachEnabled: Bool = true
    public var coachVolume: Double = 1.0

    // Sync (URL only — bearer token lives in KeychainStore)
    public var syncUrl: String = ""

    // Handlebar Click
    public var buttonMappingSerialized: String = ""
    public var clickHoldThresholdMs: Int = 500
    public var clickVolumeRepeatMs: Int = 150
    public var clickSilenceMeansLocked: Bool = false
    public var clickEitherPuck: Bool = false

    // Developer
    public var deviceSource: DeviceSourceMode = .bluetooth
    public var connectInBackground: Bool = true
    public var recordBleSessions: Bool = true
    public var demoSpeedMultiplier: Int = 1
    public var scriptedDropouts: Bool = false

    public init() {}

    public func sanitized() -> MacAppSettings {
        var copy = self
        copy.overlayOpacity = min(1.0, max(0.85, (copy.overlayOpacity * 20).rounded() / 20))
        copy.ftp = min(2000, max(30, copy.ftp))
        copy.gearCount = min(30, max(10, copy.gearCount))
        copy.gradeStepPercent = min(2.0, max(0.5, (copy.gradeStepPercent * 10).rounded() / 10))
        copy.routeDifficultyPercent = min(100, max(0, (copy.routeDifficultyPercent / 10) * 10))
        copy.systemMassKg = min(200, max(35, copy.systemMassKg))
        copy.cdA = min(0.80, max(0.15, (copy.cdA * 100).rounded() / 100))
        copy.crr = min(0.02, max(0.001, (copy.crr * 10_000).rounded() / 10_000))
        copy.cadenceFloorRpm = min(90, max(30, copy.cadenceFloorRpm))
        copy.coachVolume = min(1.0, max(0.1, copy.coachVolume))
        copy.clickHoldThresholdMs = min(1000, max(250, copy.clickHoldThresholdMs))
        copy.clickVolumeRepeatMs = min(500, max(50, copy.clickVolumeRepeatMs))
        copy.demoSpeedMultiplier = [1, 5, 10, 30].contains(copy.demoSpeedMultiplier) ? copy.demoSpeedMultiplier : 1
        return copy
    }

    public func validateSyncUrl(_ raw: String) -> (normalized: String, warning: String?, error: String?) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return ("", nil, nil) }
        guard trimmed.count <= 2048,
              let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              let host = components.host?.lowercased(), !host.isEmpty else {
            return (syncUrl, nil, "Address must start with http:// or https:// and include a host.")
        }
        if components.user != nil || components.password != nil {
            return (syncUrl, nil, "Do not put a username or password in the address.")
        }
        var clean = components
        clean.scheme = scheme
        clean.host = host
        let out = (clean.string ?? trimmed).trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let warning = scheme == "http" ? "Not encrypted: the token is sent as plain text" : nil
        return (out, warning, nil)
    }

    public func toRideSettings(effectiveFtp: Int, effectivePhysics: PhysicsSettings) -> RideSettings {
        let neutralGear = Int32((gearCount + 1) / 2)
        let gears = GearSettings(
            count: Int32(gearCount),
            neutralGear: neutralGear,
            gradePercentPerGear: gradeStepPercent,
            maxGradePercent: 15.0
        )
        let stall = StallGuardSettings(
            enabled: cadenceGuardEnabled,
            cadenceFloorRpm: Int32(cadenceFloorRpm),
            triggerSeconds: StallGuardSettings.Companion.shared.DEFAULT_TRIGGER_SECONDS,
            recoveryCadenceMarginRpm: StallGuardSettings.Companion.shared.DEFAULT_RECOVERY_MARGIN_RPM,
            reducedFactor: StallGuardSettings.Companion.shared.DEFAULT_REDUCED_FACTOR,
            releaseRampSeconds: StallGuardSettings.Companion.shared.DEFAULT_RELEASE_RAMP_SECONDS,
            resumeGraceSeconds: StallGuardSettings.Companion.shared.DEFAULT_RESUME_GRACE_SECONDS
        )
        return RideSettings(
            ftp: Int32(effectiveFtp),
            physics: effectivePhysics,
            gears: gears,
            autoPause: true,
            autoPauseAfterSeconds: RideSettings.Companion.shared.DEFAULT_AUTO_PAUSE_SECONDS,
            rampInSeconds: WorkoutPlayer.Companion.shared.DEFAULT_RAMP_IN_SECONDS,
            pausedTargetWatts: 0,
            maxTargetWatts: RideSettings.Companion.shared.DEFAULT_MAX_TARGET_WATTS,
            trainerTimeoutSeconds: RideSettings.Companion.shared.DEFAULT_TRAINER_TIMEOUT_SECONDS,
            heartRateTimeoutSeconds: RideSettings.Companion.shared.DEFAULT_HEART_RATE_TIMEOUT_SECONDS,
            autoResumePowerWatts: RideSettings.Companion.shared.DEFAULT_AUTO_RESUME_POWER_WATTS,
            autoResumeFlywheelStepKmh: RideSettings.Companion.shared.DEFAULT_AUTO_RESUME_FLYWHEEL_STEP_KMH,
            autoPauseTargetWatts: RideSettings.Companion.shared.DEFAULT_AUTO_PAUSE_TARGET_WATTS,
            stallGuard: stall,
            heartRateHold: HeartRateHoldSettings()
        )
    }

    public func toClickOptions() -> ClickOptions {
        ClickOptions(
            sendSetupCommand: true,
            handshakeWithResponse: false,
            strictHandshakeReply: false,
            keepAliveInterval: ClickOptions.Companion.shared.DEFAULT_KEEP_ALIVE,
            silentMeansLocked: clickSilenceMeansLocked,
            noEventTimeout: ClickOptions.Companion.shared.DEFAULT_NO_EVENT_TIMEOUT,
            requestMtu: ClickOptions.Companion.shared.DEFAULT_MTU,
            requireServiceInAdvertisement: true,
            acceptEitherPuck: clickEitherPuck
        )
    }

    public func toButtonMapping() -> ButtonMapping {
        if !buttonMappingSerialized.isEmpty,
           let parsed = ButtonMapping.Companion.shared.deserialize(text: buttonMappingSerialized) {
            return parsed
        }
        return ButtonMapping.Companion.shared.DEFAULT
    }
}

public final class SettingsStore {
    private let settingsURL: URL
    private let devicesURL: URL

    public init(directory: URL) {
        self.settingsURL = directory.appendingPathComponent("settings.json")
        self.devicesURL = directory.appendingPathComponent("devices.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public func loadSettings() -> MacAppSettings {
        guard let data = try? Data(contentsOf: settingsURL),
              let decoded = try? JSONDecoder().decode(MacAppSettings.self, from: data) else {
            return MacAppSettings()
        }
        return decoded.sanitized()
    }

    public func saveSettings(_ settings: MacAppSettings) {
        let clean = settings.sanitized()
        if let data = try? JSONEncoder().encode(clean) {
            try? data.write(to: settingsURL, options: .atomic)
        }
    }

    public func loadDevices() -> RememberedDevicesRecord {
        guard let data = try? Data(contentsOf: devicesURL),
              let decoded = try? JSONDecoder().decode(RememberedDevicesRecord.self, from: data) else {
            return RememberedDevicesRecord()
        }
        return decoded
    }

    public func saveDevices(_ devices: RememberedDevicesRecord) {
        if let data = try? JSONEncoder().encode(devices) {
            try? data.write(to: devicesURL, options: .atomic)
        }
    }
}

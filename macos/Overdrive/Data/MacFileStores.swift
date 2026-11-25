import Foundation
import OverdriveCore

// MARK: - Atomic Text Store & Bounded Directory Store

public final class MacTextStore: NSObject, TextStore {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
        super.init()
    }

    public func read() async throws -> String? {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
        return try? String(contentsOf: fileURL, encoding: .utf8)
    }

    public func write(text: String) async throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(text.utf8).write(to: fileURL, options: .atomic)
    }
}

public final class MacTextStoreDirectory {
    private let directory: URL
    private let maxFiles: Int

    public init(directory: URL, maxFiles: Int = 30) {
        self.directory = directory
        self.maxFiles = maxFiles
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public func store(forKey key: String) -> TextStore {
        let safe = key.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        let file = directory.appendingPathComponent(safe.isEmpty ? "item" : safe)
        pruneIfNeeded()
        return MacTextStore(fileURL: file)
    }

    public func readSync(key: String) -> String? {
        let safe = key.filter { $0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" }
        let file = directory.appendingPathComponent(safe.isEmpty ? "item" : safe)
        return try? String(contentsOf: file, encoding: .utf8)
    }

    private func pruneIfNeeded() {
        let fm = FileManager.default
        guard let urls = try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey]),
              urls.count > maxFiles else { return }
        let sorted = urls.sorted { a, b in
            let da = (try? a.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            let db = (try? b.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? .distantPast
            return da < db
        }
        for obsolete in sorted.prefix(urls.count - maxFiles) {
            try? fm.removeItem(at: obsolete)
        }
    }
}

// MARK: - Plan & Profile Caches

public final class MacPlanCache: NSObject, PlanCache {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
        super.init()
    }

    public func read() async throws -> CachedPlan? {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return nil }
        return PlanCacheFormat.shared.decode(text: text)
    }

    public func write(plan: CachedPlan) async throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoded = PlanCacheFormat.shared.encode(plan: plan)
        try Data(encoded.utf8).write(to: fileURL, options: .atomic)
    }
}

public final class MacProfileCache: NSObject, ProfileCache {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
        super.init()
    }

    public func read() async throws -> CachedProfile? {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return nil }
        return ProfileCacheFormat.shared.decode(text: text)
    }

    public func write(profile: CachedProfile) async throws {
        try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoded = ProfileCacheFormat.shared.encode(cached: profile)
        try Data(encoded.utf8).write(to: fileURL, options: .atomic)
    }
}

// MARK: - Persistent Outbox (spec §4.9, ADR 0012)

public final class MacFileOutbox: NSObject, Outbox {
    private struct PersistedEntry: Codable {
        let rideId: String
        let enqueuedAtMillis: Int64
        let startedAtMillis: Int64
        let durationSeconds: Int32
        let title: String?
    }

    private struct OutboxSnapshot: Codable {
        var pending: [PersistedEntry]
        var uploadedIds: [String]
    }

    private let fileURL: URL
    private let lock = NSLock()
    private var snapshot: OutboxSnapshot

    public init(fileURL: URL) {
        self.fileURL = fileURL
        if let data = try? Data(contentsOf: fileURL),
           let decoded = try? JSONDecoder().decode(OutboxSnapshot.self, from: data) {
            self.snapshot = decoded
        } else {
            self.snapshot = OutboxSnapshot(pending: [], uploadedIds: [])
        }
        super.init()
    }

    public func enqueue(entry: OutboxEntry) async throws {
        lock.lock()
        defer { lock.unlock() }
        if snapshot.uploadedIds.contains(entry.rideId) || snapshot.pending.contains(where: { $0.rideId == entry.rideId }) {
            return
        }
        snapshot.pending.append(
            PersistedEntry(
                rideId: entry.rideId,
                enqueuedAtMillis: entry.enqueuedAtMillis,
                startedAtMillis: entry.startedAtMillis,
                durationSeconds: entry.durationSeconds,
                title: entry.title
            )
        )
        snapshot.pending.sort { $0.enqueuedAtMillis < $1.enqueuedAtMillis }
        persistLocked()
    }

    public func pending() async throws -> [OutboxEntry] {
        lock.lock()
        defer { lock.unlock() }
        return snapshot.pending.map {
            OutboxEntry(
                rideId: $0.rideId,
                enqueuedAtMillis: $0.enqueuedAtMillis,
                startedAtMillis: $0.startedAtMillis,
                durationSeconds: $0.durationSeconds,
                title: $0.title
            )
        }
    }

    public func markDone(rideId: String) async throws {
        lock.lock()
        defer { lock.unlock() }
        let before = snapshot.pending.count
        snapshot.pending.removeAll { $0.rideId == rideId }
        if !snapshot.uploadedIds.contains(rideId) {
            snapshot.uploadedIds.append(rideId)
        }
        if snapshot.pending.count != before {
            persistLocked()
        }
    }

    private func persistLocked() {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: fileURL, options: .atomic)
        }
    }
}

public final class LastUploadStore {
    private let fileURL: URL

    public init(fileURL: URL) {
        self.fileURL = fileURL
    }

    public func read() -> Int64? {
        guard let text = try? String(contentsOf: fileURL, encoding: .utf8) else { return nil }
        return Int64(text.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    public func write(_ millis: Int64) {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data("\(millis)\n".utf8).write(to: fileURL, options: .atomic)
    }
}

// MARK: - Crash-Safe Ride Store & FIT Export (spec §4.2, §4.8, ADR 0008)

public struct StoredRideMeta: Equatable, Sendable {
    public let id: String
    public let title: String
    public let startEpochMillis: Int64
    public let durationSeconds: Int32
    public let recordURL: URL
    public let fitURL: URL
    public let fitBytes: Int64
}

public struct InterruptedRideCandidate: Equatable, Sendable {
    public let id: String
    public let logURL: URL
    public let startEpochMillis: Int64
    public let durationSeconds: Int
}

public final class MacRideStore: NSObject, AppleRidePayloads {
    public let directory: URL
    private let fm = FileManager.default

    public init(directory: URL) {
        self.directory = directory
        super.init()
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public func logURL(for rideId: String) -> URL {
        directory.appendingPathComponent("\(rideId).ridelog")
    }

    public func recordURL(for rideId: String) -> URL {
        directory.appendingPathComponent("\(rideId).json")
    }

    public func fitURL(for rideId: String) -> URL {
        directory.appendingPathComponent("\(rideId).fit")
    }

    public func suggestedFitFilename(title: String, startEpochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(startEpochMillis) / 1000.0)
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd HH-mm"
        let cleanTitle = title
            .replacingOccurrences(of: "/", with: "-")
            .replacingOccurrences(of: ":", with: "-")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return "\(df.string(from: date)) \(cleanTitle.isEmpty ? "Ride" : cleanTitle).fit"
    }

    /// Saves the ride record JSON and FIT file atomically, removing the `.ridelog` only after both succeed.
    public func save(record: RideRecord, title: String, routes: FitRoutes = AppleCoreFactories.shared.noneFitRoutes()) throws -> StoredRideMeta {
        try fm.createDirectory(at: directory, withIntermediateDirectories: true)
        let rideId = record.ride.id
        let jsonText = AppleCoreFactories.shared.encodeRideRecord(record: record)
        let utcOffset = Int32(TimeZone.current.secondsFromGMT(for: Date(timeIntervalSince1970: TimeInterval(record.ride.startEpochMillis) / 1000.0)))
        let fitData = AppleCoreFactories.shared.exportFit(record: record, routes: routes, utcOffsetSeconds: utcOffset)

        let recFile = recordURL(for: rideId)
        let fitFile = fitURL(for: rideId)
        try Data(jsonText.utf8).write(to: recFile, options: .atomic)
        try fitData.write(to: fitFile, options: .atomic)

        let logFile = logURL(for: rideId)
        if fm.fileExists(atPath: logFile.path) {
            try? fm.removeItem(at: logFile)
        }

        return StoredRideMeta(
            id: rideId,
            title: title,
            startEpochMillis: record.ride.startEpochMillis,
            durationSeconds: record.summary.durationSeconds,
            recordURL: recFile,
            fitURL: fitFile,
            fitBytes: Int64(fitData.count)
        )
    }

    /// Scans the rides folder at launch: silently finalizes finished logs, deletes interrupted logs < 60 s,
    /// returns interrupted rides >= 60 s for recovery UI, and returns all stored rides (newest first).
    public func scanAtStartup() -> (stored: [StoredRideMeta], interrupted: [InterruptedRideCandidate]) {
        let urls = (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        var interrupted: [InterruptedRideCandidate] = []

        for url in urls where url.pathExtension == "ridelog" {
            guard let recovered = AppleCoreFactories.shared.recoverRideLog(path: url.path) else {
                try? fm.removeItem(at: url)
                continue
            }
            if recovered.isFinished {
                let rec = recovered.toRecord()
                _ = try? save(record: rec, title: rec.ride.routeName ?? "Ride")
            } else if recovered.samples.count < 60 {
                try? fm.removeItem(at: url)
            } else {
                interrupted.append(
                    InterruptedRideCandidate(
                        id: recovered.header.id,
                        logURL: url,
                        startEpochMillis: recovered.header.startEpochMillis,
                        durationSeconds: recovered.samples.count
                    )
                )
            }
        }

        let stored = listStoredRides()
        return (stored, interrupted.sorted { $0.startEpochMillis > $1.startEpochMillis })
    }

    public func discardInterrupted(_ candidate: InterruptedRideCandidate) {
        try? fm.removeItem(at: candidate.logURL)
    }

    public func recoverAndSave(_ candidate: InterruptedRideCandidate) throws -> StoredRideMeta {
        guard let recovered = AppleCoreFactories.shared.recoverRideLog(path: candidate.logURL.path) else {
            throw NSError(domain: "OverdriveRideStore", code: 2, userInfo: [NSLocalizedDescriptionKey: "Interrupted ride log is unreadable."])
        }
        let rec = recovered.toRecord()
        return try save(record: rec, title: rec.ride.routeName ?? "Recovered ride")
    }

    public func listStoredRides() -> [StoredRideMeta] {
        let urls = (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
        var list: [StoredRideMeta] = []
        for url in urls where url.pathExtension == "json" {
            let rideId = url.deletingPathExtension().lastPathComponent
            let fitFile = fitURL(for: rideId)
            guard fm.fileExists(atPath: fitFile.path),
                  let text = try? String(contentsOf: url, encoding: .utf8),
                  let record = try? AppleCoreFactories.shared.decodeRideRecord(json: text) else {
                continue
            }
            let size = Int64((try? fitFile.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
            list.append(
                StoredRideMeta(
                    id: rideId,
                    title: record.ride.routeName ?? "Ride",
                    startEpochMillis: record.ride.startEpochMillis,
                    durationSeconds: record.summary.durationSeconds,
                    recordURL: url,
                    fitURL: fitFile,
                    fitBytes: size
                )
            )
        }
        return list.sorted { $0.startEpochMillis > $1.startEpochMillis }
    }

    // MARK: - AppleRidePayloads

    public func payloadOrNull(entry: OutboxEntry) async throws -> RidePayload? {
        let recFile = recordURL(for: entry.rideId)
        let fitFile = fitURL(for: entry.rideId)
        guard let recData = try? Data(contentsOf: recFile), let fitData = try? Data(contentsOf: fitFile) else {
            return nil
        }
        return RidePayload(
            rideId: entry.rideId,
            record: ByteArrayBody(bytes: AppleBytes.shared.toByteArray(data: recData)),
            fit: ByteArrayBody(bytes: AppleBytes.shared.toByteArray(data: fitData))
        )
    }
}

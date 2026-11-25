import Foundation
import OverdriveCore

/// Persists redacted raw BLE session logs (2 MB per file, 60 files, 20 MB total) and builds one `.zip` archive on demand (ADR 0010, ADR 0044).
public final class BleLogStore {
    public static let maxFileBytes = 2 * 1024 * 1024
    public static let maxFiles = 60
    public static let maxTotalBytes = 20 * 1024 * 1024

    public let directory: URL
    private let fm = FileManager.default
    private let queue = DispatchQueue(label: "app.overdrive.ble-logs", qos: .utility)

    public init(directory: URL) {
        self.directory = directory
        try? fm.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    public func listFiles() -> [URL] {
        let urls = (try? fm.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
        return urls
            .filter { $0.pathExtension == "txt" }
            .sorted { $0.lastPathComponent > $1.lastPathComponent }
    }

    public func summary() -> (count: Int, totalBytes: Int64) {
        let files = listFiles()
        let bytes = files.reduce(Int64(0)) { acc, url in
            acc + Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        }
        return (files.count, bytes)
    }

    public func openSessionSink(role: String) -> LineSink {
        let fileURL = nextSessionFile(role: role)
        enforceCaps(headroom: Self.maxFileBytes)
        return BoundedFileLineSink(url: fileURL, maxBytes: Self.maxFileBytes, queue: queue)
    }

    /// Packs the newest BLE log files (up to 20 MB) into a single standard ZIP archive without external dependencies.
    public func exportZip(to destinationURL: URL) throws -> (included: Int, skipped: Int) {
        let files = listFiles()
        guard !files.isEmpty else {
            throw NSError(domain: "OverdriveBleLogs", code: 1, userInfo: [NSLocalizedDescriptionKey: "No Bluetooth logs yet."])
        }
        var entries: [(name: String, data: Data)] = []
        var total = 0
        var skipped = 0
        for file in files {
            guard let data = try? Data(contentsOf: file) else {
                skipped += 1
                continue
            }
            if total + data.count > Self.maxTotalBytes {
                skipped += 1
                continue
            }
            entries.append((name: file.lastPathComponent, data: data))
            total += data.count
        }
        let zipData = ZipArchiveBuilder.build(entries: entries)
        try zipData.write(to: destinationURL, options: .atomic)
        return (entries.count, skipped)
    }

    private func nextSessionFile(role: String) -> URL {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withYear, .withMonth, .withDay, .withTime]
        var date = Date()
        for _ in 0..<10 {
            let stamp = formatter.string(from: date).replacingOccurrences(of: ":", with: "")
            let url = directory.appendingPathComponent("ble-\(stamp)-\(role).txt")
            if !fm.fileExists(atPath: url.path) { return url }
            date = date.addingTimeInterval(1)
        }
        return directory.appendingPathComponent("ble-\(Int(Date().timeIntervalSince1970))-\(role).txt")
    }

    private func enforceCaps(headroom: Int) {
        var files = listFiles().reversed() // oldest first
        var total = files.reduce(0) { $0 + ((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
        while files.count >= Self.maxFiles || total + headroom > Self.maxTotalBytes {
            guard let oldest = files.first else { break }
            let size = (try? oldest.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
            try? fm.removeItem(at: oldest)
            total = max(0, total - size)
            files.removeFirst()
        }
    }
}

private final class BoundedFileLineSink: NSObject, LineSink {
    private let url: URL
    private let maxBytes: Int
    private let queue: DispatchQueue
    private var writtenBytes = 0
    private var handle: FileHandle?

    init(url: URL, maxBytes: Int, queue: DispatchQueue) {
        self.url = url
        self.maxBytes = maxBytes
        self.queue = queue
        super.init()
        FileManager.default.createFile(atPath: url.path, contents: nil)
        self.handle = try? FileHandle(forWritingTo: url)
    }

    deinit {
        try? handle?.close()
    }

    func writeLine(line: String) {
        guard writtenBytes < maxBytes else { return }
        guard let data = (line + "\n").data(using: .utf8) else { return }
        writtenBytes += data.count
        queue.sync {
            try? handle?.write(contentsOf: data)
        }
    }
}

/// Pure-Swift uncompressed (STORE, method 0) ZIP archive writer with IEEE CRC-32 so logs export cleanly with zero dependencies.
public enum ZipArchiveBuilder {
    public static func build(entries: [(name: String, data: Data)]) -> Data {
        var out = Data()
        var central = Data()

        for entry in entries {
            let nameData = Data(entry.name.utf8)
            let crc = crc32(entry.data)
            let size = UInt32(entry.data.count)
            let offset = UInt32(out.count)

            // Local file header
            out.appendLE(UInt32(0x04034b50))
            out.appendLE(UInt16(20)) // version needed
            out.appendLE(UInt16(0))  // flags
            out.appendLE(UInt16(0))  // compression = STORE
            out.appendLE(UInt16(0))  // mod time
            out.appendLE(UInt16(0))  // mod date
            out.appendLE(crc)
            out.appendLE(size)
            out.appendLE(size)
            out.appendLE(UInt16(nameData.count))
            out.appendLE(UInt16(0))  // extra len
            out.append(nameData)
            out.append(entry.data)

            // Central directory entry
            central.appendLE(UInt32(0x02014b50))
            central.appendLE(UInt16(20)) // version made by
            central.appendLE(UInt16(20)) // version needed
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(crc)
            central.appendLE(size)
            central.appendLE(size)
            central.appendLE(UInt16(nameData.count))
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(UInt16(0))
            central.appendLE(UInt32(0))
            central.appendLE(offset)
            central.append(nameData)
        }

        let centralOffset = UInt32(out.count)
        out.append(central)

        // End of central directory record
        out.appendLE(UInt32(0x06054b50))
        out.appendLE(UInt16(0))
        out.appendLE(UInt16(0))
        out.appendLE(UInt16(entries.count))
        out.appendLE(UInt16(entries.count))
        out.appendLE(UInt32(central.count))
        out.appendLE(centralOffset)
        out.appendLE(UInt16(0))
        return out
    }

    public static func crc32(_ data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFF_FFFF
        for byte in data {
            crc ^= UInt32(byte)
            for _ in 0..<8 {
                let mask = UInt32(bitPattern: -Int32(crc & 1))
                crc = (crc >> 1) ^ (0xEDB8_8320 & mask)
            }
        }
        return ~crc
    }
}

private extension Data {
    mutating func appendLE(_ value: UInt16) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    mutating func appendLE(_ value: UInt32) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}

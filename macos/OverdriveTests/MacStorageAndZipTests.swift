import XCTest
import OverdriveCore
@testable import Overdrive

final class MacStorageAndZipTests: XCTestCase {
    func testSettingsSanitizationAndUrlValidation() {
        var s = MacAppSettings()
        s.ftp = 5
        s.overlayOpacity = 0.5
        s.gearCount = 99
        let clean = s.sanitized()
        XCTAssertEqual(clean.ftp, 30)
        XCTAssertEqual(clean.overlayOpacity, 0.85, accuracy: 0.001)
        XCTAssertEqual(clean.gearCount, 30)

        let httpCheck = clean.validateSyncUrl("HTTP://192.168.1.5:8080/")
        XCTAssertEqual(httpCheck.normalized, "http://192.168.1.5:8080")
        XCTAssertNotNil(httpCheck.warning)
        XCTAssertNil(httpCheck.error)

        let credCheck = clean.validateSyncUrl("https://user:pass@example.com")
        XCTAssertNotNil(credCheck.error)
    }

    func testTokenSanitizationStripsControlChars() {
        XCTAssertEqual(KeychainStore.sanitizeToken("  abc\n\t123  "), "abc123")
    }

    func testFileOutboxIdempotencyAcrossRestarts() async throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("overdrive-outbox-\(UUID().uuidString)")
        let file = dir.appendingPathComponent("outbox.json")
        defer { try? FileManager.default.removeItem(at: dir) }

        let entry = OutboxEntry(rideId: "ride-1000", enqueuedAtMillis: 1000, startedAtMillis: 1000, durationSeconds: 600, title: "Test")
        let first = MacFileOutbox(fileURL: file)
        try await first.enqueue(entry: entry)
        try await first.enqueue(entry: entry)
        let firstPending = try await first.pending()
        XCTAssertEqual(firstPending.count, 1)

        // Simulate app restart
        let second = MacFileOutbox(fileURL: file)
        let pending = try await second.pending()
        XCTAssertEqual(pending.count, 1)
        XCTAssertEqual(pending.first?.rideId, "ride-1000")

        try await second.markDone(rideId: "ride-1000")
        let afterDone = try await second.pending()
        XCTAssertTrue(afterDone.isEmpty)

        // A ride uploaded once is never queued again
        try await second.enqueue(entry: entry)
        let afterReenqueue = try await second.pending()
        XCTAssertTrue(afterReenqueue.isEmpty)
    }

    func testZipArchiveBuilderProducesValidStoreZip() {
        let zip = ZipArchiveBuilder.build(entries: [
            (name: "ble-20261125-trainer.txt", data: Data("v1\nS 0 - connected\n".utf8)),
        ])
        // Local file header magic PK\x03\x04
        XCTAssertGreaterThan(zip.count, 60)
        XCTAssertEqual(zip[0], 0x50)
        XCTAssertEqual(zip[1], 0x4B)
        XCTAssertEqual(zip[2], 0x03)
        XCTAssertEqual(zip[3], 0x04)
    }
}

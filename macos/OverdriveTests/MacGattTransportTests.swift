import XCTest
import OverdriveCore
@testable import Overdrive

final class MacGattTransportTests: XCTestCase {
    func testParseZwiftManufacturerDataLittleEndian() {
        // Zwift company ID 0x094A in little-endian is [0x4A, 0x09], followed by right-puck payload byte 0x09.
        let raw = Data([0x4A, 0x09, 0x09, 0x01, 0x02])
        let parsed = parseManufacturerData(raw)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.companyId, ClickUuids.shared.MANUFACTURER_ID)
        XCTAssertEqual(parsed?.firstByte?.int32Value, 0x09)
    }

    func testParseManufacturerDataRejectsShortPayload() {
        XCTAssertNil(parseManufacturerData(Data([0x4A])))
    }

    func testConnectDiscoverMtuAndEnableNotificationTagging() async throws {
        let platform = ScriptedPlatform()
        let transport = MacGattTransport(platform: platform, mode: .active)

        let connectResult = try await transport.connect()
        XCTAssertTrue(connectResult is GattResultOk)

        let mtuResult = try await transport.requestMtu(mtu: 64)
        if let ok = mtuResult as? GattResultOk, let negotiated = ok.value as? KotlinInt {
            XCTAssertEqual(negotiated.intValue, 185 + 3)
        } else {
            XCTFail("Expected negotiated MTU")
        }

        let servicesResult = try await transport.discoverServices()
        guard let servicesOk = servicesResult as? GattResultOk,
              let services = servicesOk.value as? GattServices else {
            XCTFail("Expected discovered services")
            return
        }
        let cp = AppleCoreFactories.shared.findCharacteristic(
            services: services,
            service: AppleCoreFactories.shared.ftmsServiceUuid,
            characteristic: AppleCoreFactories.shared.ftmsControlPointUuid
        )
        XCTAssertNotNil(cp)

        let enableResult = try await transport.enable(characteristic: cp!, mode: .indication)
        XCTAssertTrue(enableResult is GattResultOk)

        transport.close()
        let afterClose = try await transport.discoverServices()
        XCTAssertTrue(afterClose is GattResultFailed)
    }
}

private final class ScriptedPlatform: CoreBluetoothPlatform {
    var onEvent: ((PlatformGattEvent) -> Void)?
    var maxWriteLengthWithoutResponse: Int = 185
    var canSendWriteWithoutResponse: Bool = true
    var disconnectCount = 0

    func connect() -> PlatformStartResult {
        DispatchQueue.global().async { [weak self] in
            self?.onEvent?(.connected)
        }
        return .started
    }

    func discoverServices() -> PlatformStartResult {
        let chars = [
            AppleCoreFactories.shared.createCharacteristicInfo(
                service: AppleCoreFactories.shared.ftmsServiceUuid,
                characteristic: AppleCoreFactories.shared.ftmsControlPointUuid,
                properties: [.write, .indicate]
            ),
            AppleCoreFactories.shared.createCharacteristicInfo(
                service: AppleCoreFactories.shared.ftmsServiceUuid,
                characteristic: AppleCoreFactories.shared.ftmsIndoorBikeDataUuid,
                properties: [.notify]
            ),
        ].compactMap { $0 }
        DispatchQueue.global().async { [weak self] in
            self?.onEvent?(.servicesDiscovered(characteristics: chars, status: GattStatus.shared.SUCCESS))
        }
        return .started
    }

    func read(service: String, characteristic: String) -> PlatformStartResult {
        DispatchQueue.global().async { [weak self] in
            self?.onEvent?(.readCompleted(characteristic: characteristic, value: Data([0x01, 0x02]), status: GattStatus.shared.SUCCESS))
        }
        return .started
    }

    func write(service: String, characteristic: String, value: Data, withResponse: Bool) -> PlatformStartResult {
        if withResponse {
            DispatchQueue.global().async { [weak self] in
                self?.onEvent?(.writeCompleted(characteristic: characteristic, status: GattStatus.shared.SUCCESS))
            }
        }
        return .started
    }

    func setNotify(service: String, characteristic: String, enabled: Bool) -> PlatformStartResult {
        DispatchQueue.global().async { [weak self] in
            self?.onEvent?(.notifyStateChanged(characteristic: characteristic, enabled: enabled, status: GattStatus.shared.SUCCESS))
        }
        return .started
    }

    func disconnect() {
        disconnectCount += 1
    }
}

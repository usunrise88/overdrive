import CoreBluetooth
import Foundation
import OverdriveCore

/// Canonical 16-bit/128-bit UUID bridge between CoreBluetooth and `OverdriveCore.GattUuid`.
public extension CBUUID {
    func toCoreGattUuid() -> GattUuid? {
        GattUuid.Companion.shared.parse(text: uuidString)
    }
}

public extension GattUuid {
    func toCBUUID() -> CBUUID {
        CBUUID(string: value)
    }
}

/// Splits CoreBluetooth's raw `CBAdvertisementDataManufacturerDataKey` into a 16-bit little-endian
/// Bluetooth Company ID (`0x094A` for Zwift Click v2) and the remaining payload bytes.
public func parseManufacturerData(_ raw: Data) -> ManufacturerData? {
    guard raw.count >= 2 else { return nil }
    let low = Int32(raw[raw.startIndex])
    let high = Int32(raw[raw.startIndex + 1])
    let companyId = low | (high << 8)
    let payloadData = raw.count > 2 ? raw.subdata(in: 2..<raw.count) : Data()
    return ManufacturerData(companyId: companyId, payload: AppleBytes.shared.toByteArray(data: payloadData))
}

/// Maps CoreBluetooth characteristic properties onto `OverdriveCore.GattProperty`.
public func mapCharacteristicProperties(_ props: CBCharacteristicProperties) -> Set<GattProperty> {
    var result: Set<GattProperty> = []
    if props.contains(.read) { result.insert(.read) }
    if props.contains(.write) { result.insert(.write) }
    if props.contains(.writeWithoutResponse) { result.insert(.writeWithoutResponse) }
    if props.contains(.notify) { result.insert(.notify) }
    if props.contains(.indicate) { result.insert(.indicate) }
    return result
}

/// Synchronous start result from the thin CoreBluetooth platform shim (mirrors Android's `PlatformGatt`).
public enum PlatformStartResult: Equatable {
    case started
    case busy
    case notFound
    case unavailable
    case error
}

/// Asynchronous callbacks delivered by `CoreBluetoothPlatform` to `MacGattTransport`.
public enum PlatformGattEvent {
    case connected
    case disconnected(status: Int32)
    case servicesDiscovered(characteristics: [GattCharacteristicInfo], status: Int32)
    case readCompleted(characteristic: GattUuid, value: Data?, status: Int32)
    case writeCompleted(characteristic: GattUuid, status: Int32)
    case writeWithoutResponseReady
    case notifyStateChanged(characteristic: GattUuid, enabled: Bool, status: Int32)
    case notificationReceived(characteristic: GattUuid, value: Data, timestampNanos: Int64)
}

/// Thin platform boundary over `CBCentralManager` + `CBPeripheral` so `MacGattTransport` is 100% unit-testable without a radio.
public protocol CoreBluetoothPlatform: AnyObject {
    var onEvent: ((PlatformGattEvent) -> Void)? { get set }
    var maxWriteLengthWithoutResponse: Int { get }
    var canSendWriteWithoutResponse: Bool { get }

    func connect() -> PlatformStartResult
    func discoverServices() -> PlatformStartResult
    func read(service: GattUuid, characteristic: GattUuid) -> PlatformStartResult
    func write(service: GattUuid, characteristic: GattUuid, value: Data, withResponse: Bool) -> PlatformStartResult
    func setNotify(service: GattUuid, characteristic: GattUuid, enabled: Bool) -> PlatformStartResult
    func disconnect()
}

import AppKit
import CoreBluetooth
import Foundation
import OverdriveCore

/// Complete CoreBluetooth stack implementing `core`'s four BLE boundaries (`BleEnvironment`, `GattScanner`,
/// `GattTransportFactory`, `GattTransport`) on a dedicated background `DispatchQueue` (spec §7.3, ADR 0044).
public final class MacBleStack: NSObject {
    public let bleQueue = DispatchQueue(label: "app.overdrive.ble", qos: .userInitiated)
    private var centralManager: CBCentralManager!
    private let availabilityBridge = AppleStateBridge<BleAvailability>(initial: .bluetoothOff)

    private var activeScanBridge: AppleEventBridge<ScanEvent>?
    private var activeScanFilter: ScanFilter?
    private var knownPeripherals: [UUID: CBPeripheral] = [:]
    private var activeShims: [UUID: CoreBluetoothShim] = [:]
    private var pendingResolveCallbacks: [UUID: [(CBPeripheral) -> Void]] = [:]

    public lazy var environment: BleEnvironment = MacBleEnvironmentAdapter(bridge: availabilityBridge)
    public lazy var scanner: GattScanner = MacGattScannerAdapter(stack: self)
    public lazy var transports: GattTransportFactory = MacGattTransportFactoryAdapter(stack: self)

    public override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: bleQueue)
        updateAvailability()
    }

    public func refreshAvailability() {
        bleQueue.async { [weak self] in
            self?.updateAvailability()
        }
    }

    public func openBluetoothSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") {
            NSWorkspace.shared.open(url)
        }
    }

    public func nanoClock() -> Int64 {
        Int64(DispatchTime.now().uptimeNanoseconds)
    }

    // MARK: - Internal Scan & Transport Coordination (bleQueue)

    fileprivate func startScan(filter: ScanFilter, bridge: AppleEventBridge<ScanEvent>) {
        bleQueue.async { [weak self] in
            guard let self else { return }
            self.updateAvailability()
            switch self.availabilityBridge.get() {
            case .permissionDenied:
                _ = bridge.emit(value: ScanEventFailed(reason: .permissionDenied))
                return
            case .bluetoothOff, .unsupported:
                _ = bridge.emit(value: ScanEventFailed(reason: .bluetoothOff))
                return
            case .ready:
                break
            default:
                break
            }
            self.activeScanBridge = bridge
            self.activeScanFilter = filter
            let services: [CBUUID]? = filter.serviceUuids.isEmpty
                ? nil
                : filter.serviceUuids.map { $0.toCBUUID() }
            self.centralManager.scanForPeripherals(
                withServices: services,
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: true]
            )
        }
    }

    fileprivate func stopScan() {
        bleQueue.async { [weak self] in
            guard let self else { return }
            self.activeScanBridge = nil
            self.activeScanFilter = nil
            if self.pendingResolveCallbacks.isEmpty {
                self.centralManager.stopScan()
            }
        }
    }

    fileprivate func openTransport(deviceId: String, mode: ConnectMode) -> GattTransport {
        let uuid = UUID(uuidString: deviceId) ?? UUID()
        let shim = CoreBluetoothShim(uuid: uuid, stack: self)
        return MacGattTransport(platform: shim, mode: mode)
    }

    fileprivate func requestConnect(uuid: UUID, shim: CoreBluetoothShim) -> PlatformStartResult {
        var result: PlatformStartResult = .started
        bleQueue.sync {
            self.updateAvailability()
            guard self.availabilityBridge.get() == .ready else {
                result = .unavailable
                return
            }
            self.activeShims[uuid] = shim
            if let peripheral = self.resolvePeripheralSync(uuid: uuid) {
                shim.attach(peripheral: peripheral)
                self.centralManager.connect(peripheral, options: nil)
            } else {
                // Fallback when bluetoothd cache lacks the remembered UUID: brief targeted scan to resolve it.
                self.pendingResolveCallbacks[uuid, default: []].append { [weak self, weak shim] found in
                    guard let self, let shim, self.activeShims[uuid] === shim else { return }
                    shim.attach(peripheral: found)
                    self.centralManager.connect(found, options: nil)
                }
                if self.activeScanBridge == nil {
                    self.centralManager.scanForPeripherals(withServices: nil, options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
                }
            }
        }
        return result
    }

    fileprivate func requestDisconnect(uuid: UUID, shim: CoreBluetoothShim) {
        bleQueue.async { [weak self] in
            guard let self else { return }
            if self.activeShims[uuid] === shim {
                self.activeShims.removeValue(forKey: uuid)
            }
            self.pendingResolveCallbacks.removeValue(forKey: uuid)
            if self.activeScanBridge == nil && self.pendingResolveCallbacks.isEmpty {
                self.centralManager.stopScan()
            }
            if let peripheral = shim.peripheral {
                peripheral.delegate = nil
                self.centralManager.cancelPeripheralConnection(peripheral)
            }
        }
    }

    private func resolvePeripheralSync(uuid: UUID) -> CBPeripheral? {
        if let cached = knownPeripherals[uuid] { return cached }
        let retrieved = centralManager.retrievePeripherals(withIdentifiers: [uuid])
        if let first = retrieved.first {
            knownPeripherals[uuid] = first
            return first
        }
        return nil
    }

    private func updateAvailability() {
        let auth = CBManager.authorization
        let availability: BleAvailability
        if centralManager.state == .unsupported {
            availability = .unsupported
        } else if auth == .denied || auth == .restricted || centralManager.state == .unauthorized {
            availability = .permissionDenied
        } else if centralManager.state == .poweredOn {
            availability = .ready
        } else {
            availability = .bluetoothOff
        }
        availabilityBridge.set(value: availability)
    }
}

extension MacBleStack: CBCentralManagerDelegate {
    public func centralManagerDidUpdateState(_ central: CBCentralManager) {
        updateAvailability()
        if central.state != .poweredOn {
            for (_, shim) in activeShims {
                shim.notifyDisconnected(status: GattStatus.shared.UNKNOWN)
            }
            activeShims.removeAll()
            if let scanBridge = activeScanBridge {
                _ = scanBridge.emit(value: ScanEventFailed(reason: availabilityBridge.get() == .permissionDenied ? .permissionDenied : .bluetoothOff))
                activeScanBridge = nil
            }
        }
    }

    public func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        let uuid = peripheral.identifier
        knownPeripherals[uuid] = peripheral

        if let waiters = pendingResolveCallbacks.removeValue(forKey: uuid) {
            for waiter in waiters { waiter(peripheral) }
            if activeScanBridge == nil && pendingResolveCallbacks.isEmpty {
                central.stopScan()
            }
        }

        guard let scanBridge = activeScanBridge else { return }
        let rawRssi = RSSI.intValue
        guard rawRssi < 127 else { return }

        let localName = (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? peripheral.name
        let cbServices = (advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID]) ?? []
        let serviceUuids = Set(cbServices.compactMap { $0.toCoreGattUuid() })
        var manufacturerList: [ManufacturerData] = []
        if let mfgData = advertisementData[CBAdvertisementDataManufacturerDataKey] as? Data,
           let parsed = parseManufacturerData(mfgData) {
            manufacturerList.append(parsed)
        }

        let adv = Advertisement(
            id: uuid.uuidString,
            name: localName,
            serviceUuids: serviceUuids,
            rssi: Int32(rawRssi),
            manufacturerData: manufacturerList
        )
        _ = scanBridge.emit(value: ScanEventFound(advertisement: adv))
    }

    public func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        activeShims[peripheral.identifier]?.notifyConnected()
    }

    public func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        let code = Int32((error as NSError?)?.code ?? Int(GattStatus.shared.UNKNOWN))
        let shim = activeShims.removeValue(forKey: peripheral.identifier)
        shim?.notifyDisconnected(status: code == 0 ? GattStatus.shared.UNKNOWN : code)
    }

    public func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let code = Int32((error as NSError?)?.code ?? 0)
        let shim = activeShims.removeValue(forKey: peripheral.identifier)
        shim?.notifyDisconnected(status: code)
    }
}

// MARK: - CoreBluetoothShim (Real CoreBluetoothPlatform)

private final class CoreBluetoothShim: NSObject, CoreBluetoothPlatform, CBPeripheralDelegate {
    let uuid: UUID
    weak var stack: MacBleStack?
    var peripheral: CBPeripheral?
    var onEvent: ((PlatformGattEvent) -> Void)?

    private var characteristicsByKey: [String: CBCharacteristic] = [:]
    private var pendingServiceDiscoveries = 0
    private var discoveredInfos: [GattCharacteristicInfo] = []

    var maxWriteLengthWithoutResponse: Int {
        peripheral?.maximumWriteValueLength(for: .withoutResponse) ?? 20
    }

    var canSendWriteWithoutResponse: Bool {
        peripheral?.canSendWriteWithoutResponse ?? false
    }

    init(uuid: UUID, stack: MacBleStack) {
        self.uuid = uuid
        self.stack = stack
    }

    func attach(peripheral: CBPeripheral) {
        self.peripheral = peripheral
        peripheral.delegate = self
    }

    func notifyConnected() {
        onEvent?(.connected)
    }

    func notifyDisconnected(status: Int32) {
        onEvent?(.disconnected(status: status))
    }

    func connect() -> PlatformStartResult {
        guard let stack else { return .unavailable }
        return stack.requestConnect(uuid: uuid, shim: self)
    }

    func discoverServices() -> PlatformStartResult {
        guard let peripheral else { return .unavailable }
        characteristicsByKey.removeAll()
        discoveredInfos.removeAll()
        pendingServiceDiscoveries = 0
        peripheral.discoverServices(nil)
        return .started
    }

    func read(service: String, characteristic: String) -> PlatformStartResult {
        guard let peripheral else { return .unavailable }
        guard let cbChar = characteristicsByKey[key(service, characteristic)] else { return .notFound }
        peripheral.readValue(for: cbChar)
        return .started
    }

    func write(service: String, characteristic: String, value: Data, withResponse: Bool) -> PlatformStartResult {
        guard let peripheral else { return .unavailable }
        guard let cbChar = characteristicsByKey[key(service, characteristic)] else { return .notFound }
        peripheral.writeValue(value, for: cbChar, type: withResponse ? .withResponse : .withoutResponse)
        return .started
    }

    func setNotify(service: String, characteristic: String, enabled: Bool) -> PlatformStartResult {
        guard let peripheral else { return .unavailable }
        guard let cbChar = characteristicsByKey[key(service, characteristic)] else { return .notFound }
        peripheral.setNotifyValue(enabled, for: cbChar)
        return .started
    }

    func disconnect() {
        stack?.requestDisconnect(uuid: uuid, shim: self)
    }

    // MARK: CBPeripheralDelegate

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        if let error {
            onEvent?(.servicesDiscovered(characteristics: [], status: Int32((error as NSError).code)))
            return
        }
        let services = peripheral.services ?? []
        if services.isEmpty {
            onEvent?(.servicesDiscovered(characteristics: [], status: GattStatus.shared.SUCCESS))
            return
        }
        pendingServiceDiscoveries = services.count
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        if let svcUuid = service.uuid.toCoreGattUuid() {
            for cbChar in service.characteristics ?? [] {
                if let charUuid = cbChar.uuid.toCoreGattUuid() {
                    characteristicsByKey[key(svcUuid, charUuid)] = cbChar
                    discoveredInfos.append(
                        GattCharacteristicInfo(
                            service: svcUuid,
                            characteristic: charUuid,
                            properties: mapCharacteristicProperties(cbChar.properties)
                        )
                    )
                }
            }
        }
        pendingServiceDiscoveries -= 1
        if pendingServiceDiscoveries <= 0 {
            let status = error.map { Int32(($0 as NSError).code) } ?? GattStatus.shared.SUCCESS
            onEvent?(.servicesDiscovered(characteristics: discoveredInfos, status: status))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let charUuid = characteristic.uuid.toCoreGattUuid() else { return }
        let nowNanos = Int64(DispatchTime.now().uptimeNanoseconds)
        let status = error.map { Int32(($0 as NSError).code) } ?? GattStatus.shared.SUCCESS
        if characteristic.isNotifying && status == GattStatus.shared.SUCCESS, let data = characteristic.value {
            onEvent?(.notificationReceived(characteristic: charUuid, value: data, timestampNanos: nowNanos))
        } else {
            onEvent?(.readCompleted(characteristic: charUuid, value: characteristic.value, status: status))
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let charUuid = characteristic.uuid.toCoreGattUuid() else { return }
        let status = error.map { Int32(($0 as NSError).code) } ?? GattStatus.shared.SUCCESS
        onEvent?(.writeCompleted(characteristic: charUuid, status: status))
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        onEvent?(.writeWithoutResponseReady)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard let charUuid = characteristic.uuid.toCoreGattUuid() else { return }
        let status = error.map { Int32(($0 as NSError).code) } ?? GattStatus.shared.SUCCESS
        onEvent?(.notifyStateChanged(characteristic: charUuid, enabled: characteristic.isNotifying, status: status))
    }

    private func key(_ service: String, _ characteristic: String) -> String {
        "\(service)/\(characteristic)"
    }
}

// MARK: - Adapters to OverdriveCore interfaces

private final class MacBleEnvironmentAdapter: NSObject, BleEnvironment {
    private let bridge: AppleStateBridge<BleAvailability>
    var availability: Kotlinx_coroutines_coreStateFlow { bridge.flow }
    init(bridge: AppleStateBridge<BleAvailability>) { self.bridge = bridge }
}

private final class MacGattScannerAdapter: NSObject, GattScanner {
    weak var stack: MacBleStack?
    init(stack: MacBleStack) { self.stack = stack }

    func scan(filter: ScanFilter) -> Kotlinx_coroutines_coreFlow {
        let bridge = AppleEventBridge<ScanEvent>(bufferCapacity: 256)
        stack?.startScan(filter: filter, bridge: bridge)
        return bridge.flow
    }
}

private final class MacGattTransportFactoryAdapter: NSObject, GattTransportFactory {
    weak var stack: MacBleStack?
    init(stack: MacBleStack) { self.stack = stack }

    func open(device: String) -> GattTransport {
        open(device: device, mode: .active)
    }

    func open(device: String, mode: ConnectMode) -> GattTransport {
        stack?.openTransport(deviceId: device, mode: mode)
            ?? MacGattTransport(platform: NullCoreBluetoothPlatform(), mode: mode)
    }
}

private final class NullCoreBluetoothPlatform: CoreBluetoothPlatform {
    var onEvent: ((PlatformGattEvent) -> Void)?
    var maxWriteLengthWithoutResponse: Int { 20 }
    var canSendWriteWithoutResponse: Bool { false }
    func connect() -> PlatformStartResult { .unavailable }
    func discoverServices() -> PlatformStartResult { .unavailable }
    func read(service: String, characteristic: String) -> PlatformStartResult { .unavailable }
    func write(service: String, characteristic: String, value: Data, withResponse: Bool) -> PlatformStartResult { .unavailable }
    func setNotify(service: String, characteristic: String, enabled: Bool) -> PlatformStartResult { .unavailable }
    func disconnect() {}
}

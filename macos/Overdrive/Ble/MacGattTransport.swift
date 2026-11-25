import Foundation
import OverdriveCore

/// Single-use GATT transport for macOS implementing `OverdriveCore.GattTransport` over any `CoreBluetoothPlatform` (ADR 0044).
///
/// Mirrors `AndroidGattTransport`'s strict invariants:
/// - Single-use: once `Disconnected`, the instance is dead and `LinkSupervisor` asks the factory for a fresh one.
/// - Serialised operations: at most one GATT operation in flight (`BUSY` if overlapped).
/// - Background connect (`ConnectMode.BACKGROUND`): no transport connect timeout so CoreBluetooth naturally wakes a sleeping Zwift Click.
/// - CoreBluetooth specifics: `requestMtu` returns negotiated ATT MTU immediately, `enable` uses `setNotifyValue(true)`,
///   and `writeWithoutResponse` respects `canSendWriteWithoutResponse` backpressure.
public final class MacGattTransport: NSObject, GattTransport {
    private enum PendingOp {
        case connect(CheckedContinuation<GattResult, Never>)
        case discover(CheckedContinuation<GattResult, Never>)
        case read(String, CheckedContinuation<GattResult, Never>)
        case write(String, CheckedContinuation<GattResult, Never>)
        case writeNoResponseReady(CheckedContinuation<Bool, Never>)
        case enable(String, CheckedContinuation<GattResult, Never>)
    }

    private let platform: CoreBluetoothPlatform
    private let mode: ConnectMode
    private let connectTimeoutSeconds: TimeInterval
    private let opTimeoutSeconds: TimeInterval
    private let stateBridge = AppleStateBridge<GattLinkState>(initial: GattLinkStateIdle.shared)
    private let notificationBridge = AppleEventBridge<GattNotification>(bufferCapacity: 256)
    private let lock = NSLock()

    private var closed = false
    private var pending: PendingOp?
    private var enabledModes: [String: ValueUpdate] = [:]

    public var state: Kotlinx_coroutines_coreStateFlow { stateBridge.flow }
    public var notifications: Kotlinx_coroutines_coreFlow { notificationBridge.flow }

    public init(
        platform: CoreBluetoothPlatform,
        mode: ConnectMode = .active,
        connectTimeoutSeconds: TimeInterval = 35.0,
        opTimeoutSeconds: TimeInterval = 10.0
    ) {
        self.platform = platform
        self.mode = mode
        self.connectTimeoutSeconds = connectTimeoutSeconds
        self.opTimeoutSeconds = opTimeoutSeconds
        super.init()
        self.platform.onEvent = { [weak self] event in
            self?.handleEvent(event)
        }
    }

    // MARK: - GattTransport

    public func connect() async throws -> GattResult {
        lock.lock()
        if closed || stateBridge.get() is GattLinkStateDisconnected {
            lock.unlock()
            return failed(.closed)
        }
        if pending != nil {
            lock.unlock()
            return failed(.busy)
        }
        stateBridge.set(value: GattLinkStateConnecting.shared)
        lock.unlock()

        let result: GattResult = await withCheckedContinuation { continuation in
            lock.lock()
            if closed {
                lock.unlock()
                continuation.resume(returning: failed(.closed))
                return
            }
            pending = .connect(continuation)
            let started = platform.connect()
            if started != .started {
                pending = nil
                stateBridge.set(value: GattLinkStateDisconnected(status: GattStatus.shared.UNKNOWN))
                lock.unlock()
                continuation.resume(returning: failed(mapStartFailure(started)))
                return
            }
            lock.unlock()

            if mode == .active {
                scheduleTimeout(seconds: connectTimeoutSeconds) { [weak self] in
                    self?.timeoutConnect()
                }
            }
        }
        return result
    }

    public func discoverServices() async throws -> GattResult {
        await runSerialized(
            start: { self.platform.discoverServices() },
            makePending: { .discover($0) }
        )
    }

    public func requestMtu(mtu: Int32) async throws -> GattResult {
        lock.lock()
        let dead = closed || !(stateBridge.get() is GattLinkStateConnected)
        lock.unlock()
        if dead { return failed(.disconnected) }
        // CoreBluetooth negotiates ATT MTU automatically; report effective ATT payload + 3-byte ATT header.
        let effective = Int32(max(23, platform.maxWriteLengthWithoutResponse + 3))
        return GattResultOk(value: KotlinInt(int: effective))
    }

    public func read(characteristic: GattCharacteristicInfo) async throws -> GattResult {
        await runSerialized(
            start: { self.platform.read(service: characteristic.service, characteristic: characteristic.characteristic) },
            makePending: { .read(characteristic.characteristic, $0) }
        )
    }

    public func write(characteristic: GattCharacteristicInfo, value: KotlinByteArray) async throws -> GattResult {
        let data = AppleBytes.shared.toNSData(bytes: value)
        return await runSerialized(
            start: {
                self.platform.write(
                    service: characteristic.service,
                    characteristic: characteristic.characteristic,
                    value: data,
                    withResponse: true
                )
            },
            makePending: { .write(characteristic.characteristic, $0) }
        )
    }

    public func writeWithoutResponse(characteristic: GattCharacteristicInfo, value: KotlinByteArray) async throws -> GattResult {
        lock.lock()
        if closed {
            lock.unlock()
            return failed(.closed)
        }
        guard stateBridge.get() is GattLinkStateConnected else {
            lock.unlock()
            return failed(.disconnected)
        }
        if pending != nil {
            lock.unlock()
            return failed(.busy)
        }
        let readyNow = platform.canSendWriteWithoutResponse
        lock.unlock()

        if !readyNow {
            let becameReady = await waitForWriteWithoutResponseReady()
            if !becameReady {
                return failed(.timeout)
            }
        }

        let data = AppleBytes.shared.toNSData(bytes: value)
        let started = platform.write(
            service: characteristic.service,
            characteristic: characteristic.characteristic,
            value: data,
            withResponse: false
        )
        return started == .started ? AppleCoreFactories.shared.gattResultOkUnit() : failed(mapStartFailure(started))
    }

    public func enable(characteristic: GattCharacteristicInfo, mode: ValueUpdate) async throws -> GattResult {
        lock.lock()
        enabledModes[characteristic.characteristic] = mode
        lock.unlock()
        return await runSerialized(
            start: {
                self.platform.setNotify(
                    service: characteristic.service,
                    characteristic: characteristic.characteristic,
                    enabled: true
                )
            },
            makePending: { .enable(characteristic.characteristic, $0) }
        )
    }

    public func close() {
        lock.lock()
        if closed {
            lock.unlock()
            return
        }
        closed = true
        let op = pending
        pending = nil
        if !(stateBridge.get() is GattLinkStateDisconnected) {
            stateBridge.set(value: GattLinkStateDisconnected(status: GattStatus.shared.SUCCESS))
        }
        lock.unlock()

        platform.disconnect()
        failPending(op, with: failed(.closed))
    }

    // MARK: - Internals

    private func runSerialized(
        start: @escaping () -> PlatformStartResult,
        makePending: (CheckedContinuation<GattResult, Never>) -> PendingOp
    ) async -> GattResult {
        await withCheckedContinuation { continuation in
            lock.lock()
            if closed {
                lock.unlock()
                continuation.resume(returning: failed(.closed))
                return
            }
            guard stateBridge.get() is GattLinkStateConnected else {
                lock.unlock()
                continuation.resume(returning: failed(.disconnected))
                return
            }
            if pending != nil {
                lock.unlock()
                continuation.resume(returning: failed(.busy))
                return
            }
            pending = makePending(continuation)
            let started = start()
            if started != .started {
                pending = nil
                lock.unlock()
                continuation.resume(returning: failed(mapStartFailure(started)))
                return
            }
            lock.unlock()

            scheduleTimeout(seconds: opTimeoutSeconds) { [weak self] in
                self?.timeoutCurrentOperation()
            }
        }
    }

    private func waitForWriteWithoutResponseReady() async -> Bool {
        await withCheckedContinuation { continuation in
            lock.lock()
            if closed || !(stateBridge.get() is GattLinkStateConnected) {
                lock.unlock()
                continuation.resume(returning: false)
                return
            }
            pending = .writeNoResponseReady(continuation)
            lock.unlock()

            scheduleTimeout(seconds: 3.0) { [weak self] in
                guard let self else { return }
                self.lock.lock()
                if case .writeNoResponseReady(let cont) = self.pending {
                    self.pending = nil
                    self.lock.unlock()
                    cont.resume(returning: false)
                } else {
                    self.lock.unlock()
                }
            }
        }
    }

    private func handleEvent(_ event: PlatformGattEvent) {
        switch event {
        case .connected:
            lock.lock()
            if closed {
                lock.unlock()
                platform.disconnect()
                return
            }
            stateBridge.set(value: GattLinkStateConnected.shared)
            let op = pending
            if case .connect = op { pending = nil }
            lock.unlock()
            if case .connect(let cont) = op {
                cont.resume(returning: AppleCoreFactories.shared.gattResultOkUnit())
            }

        case .disconnected(let status):
            lock.lock()
            let op = pending
            pending = nil
            if !(stateBridge.get() is GattLinkStateDisconnected) {
                stateBridge.set(value: GattLinkStateDisconnected(status: status))
            }
            lock.unlock()
            failPending(op, with: failed(.disconnected, status: status))

        case .servicesDiscovered(let characteristics, let status):
            lock.lock()
            let op = pending
            if case .discover = op { pending = nil }
            lock.unlock()
            if case .discover(let cont) = op {
                if status == GattStatus.shared.SUCCESS {
                    cont.resume(returning: GattResultOk(value: GattServices(characteristics: characteristics)))
                } else {
                    cont.resume(returning: failed(.status, status: status))
                }
            }

        case .readCompleted(let uuid, let value, let status):
            lock.lock()
            let op = pending
            if case .read(let expected, _) = op, expected == uuid {
                pending = nil
            }
            lock.unlock()
            if case .read(let expected, let cont) = op, expected == uuid {
                if status == GattStatus.shared.SUCCESS, let value {
                    cont.resume(returning: GattResultOk(value: AppleBytes.shared.toByteArray(data: value)))
                } else {
                    cont.resume(returning: failed(.status, status: status))
                }
            }

        case .writeCompleted(let uuid, let status):
            lock.lock()
            let op = pending
            if case .write(let expected, _) = op, expected == uuid {
                pending = nil
            }
            lock.unlock()
            if case .write(let expected, let cont) = op, expected == uuid {
                if status == GattStatus.shared.SUCCESS {
                    cont.resume(returning: AppleCoreFactories.shared.gattResultOkUnit())
                } else {
                    cont.resume(returning: failed(.status, status: status))
                }
            }

        case .writeWithoutResponseReady:
            lock.lock()
            let op = pending
            if case .writeNoResponseReady = op { pending = nil }
            lock.unlock()
            if case .writeNoResponseReady(let cont) = op {
                cont.resume(returning: true)
            }

        case .notifyStateChanged(let uuid, _, let status):
            lock.lock()
            let op = pending
            if case .enable(let expected, _) = op, expected == uuid {
                pending = nil
            }
            lock.unlock()
            if case .enable(let expected, let cont) = op, expected == uuid {
                if status == GattStatus.shared.SUCCESS {
                    cont.resume(returning: AppleCoreFactories.shared.gattResultOkUnit())
                } else {
                    cont.resume(returning: failed(.status, status: status))
                }
            }

        case .notificationReceived(let uuid, let value, let timestampNanos):
            lock.lock()
            let updateKind = enabledModes[uuid] ?? .notification
            lock.unlock()
            let notification = GattNotification(
                characteristic: uuid,
                value: AppleBytes.shared.toByteArray(data: value),
                timestampNanos: timestampNanos,
                kind: updateKind
            )
            _ = notificationBridge.emit(value: notification)
        }
    }

    private func timeoutConnect() {
        lock.lock()
        guard case .connect(let cont) = pending else {
            lock.unlock()
            return
        }
        pending = nil
        stateBridge.set(value: GattLinkStateDisconnected(status: GattStatus.shared.UNKNOWN))
        lock.unlock()
        platform.disconnect()
        cont.resume(returning: failed(.timeout))
    }

    private func timeoutCurrentOperation() {
        lock.lock()
        let op = pending
        switch op {
        case .discover, .read, .write, .enable:
            pending = nil
            lock.unlock()
            failPending(op, with: failed(.timeout))
        default:
            lock.unlock()
        }
    }

    private func scheduleTimeout(seconds: TimeInterval, action: @escaping () -> Void) {
        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + seconds, execute: action)
    }

    private func failPending(_ op: PendingOp?, with result: GattResult) {
        guard let op else { return }
        switch op {
        case .connect(let cont), .discover(let cont), .read(_, let cont), .write(_, let cont), .enable(_, let cont):
            cont.resume(returning: result)
        case .writeNoResponseReady(let cont):
            cont.resume(returning: false)
        }
    }

    private func failed(_ kind: GattFailureKind, status: Int32 = GattStatus.shared.UNKNOWN) -> GattResult {
        GattResultFailed(failure: GattFailure(kind: kind, status: status))
    }

    private func mapStartFailure(_ start: PlatformStartResult) -> GattFailureKind {
        switch start {
        case .started: return .status
        case .busy: return .busy
        case .notFound: return .notFound
        case .unavailable: return .unavailable
        case .error: return .status
        }
    }
}

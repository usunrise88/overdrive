import AppKit
import ApplicationServices
import AudioToolbox
import CoreAudio
import Foundation
import OverdriveCore

/// macOS implementation of `core`'s `MediaKeys` (spec §4.7, ADR 0044):
/// - Volume Up/Down via CoreAudio `kAudioHardwareServiceDeviceProperty_VirtualMainVolume` (±1/16 step, needs NO permissions),
///   falling back to HID system media keys for HDMI/fixed-volume outputs.
/// - Play/Pause via HID `NX_KEYTYPE_PLAY` posted through `CGEvent` (needs optional Accessibility permission).
/// Refusals are swallowed so a media key can never disrupt a ride.
public final class MacMediaController: NSObject, MediaKeys {
    private static let volumeStep: Float32 = 1.0 / 16.0
    private static let nxKeyPlay: Int32 = 16
    private static let nxKeySoundUp: Int32 = 0
    private static let nxKeySoundDown: Int32 = 1

    public override init() {
        super.init()
    }

    public static var isAccessibilityTrusted: Bool {
        AXIsProcessTrusted()
    }

    public static func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    public func playPause() {
        postSystemMediaKey(Self.nxKeyPlay)
    }

    public func volumeUp() {
        if !adjustCoreAudioVolume(delta: Self.volumeStep) {
            postSystemMediaKey(Self.nxKeySoundUp)
        }
    }

    public func volumeDown() {
        if !adjustCoreAudioVolume(delta: -Self.volumeStep) {
            postSystemMediaKey(Self.nxKeySoundDown)
        }
    }

    private func adjustCoreAudioVolume(delta: Float32) -> Bool {
        var deviceId = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var defaultAddress = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        let gotDevice = AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject),
            &defaultAddress,
            0,
            nil,
            &size,
            &deviceId
        )
        guard gotDevice == noErr, deviceId != kAudioObjectUnknown else { return false }

        var volAddress = AudioObjectPropertyAddress(
            mSelector: kAudioHardwareServiceDeviceProperty_VirtualMainVolume,
            mScope: kAudioDevicePropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectHasProperty(deviceId, &volAddress) else { return false }

        var isSettable = DarwinBoolean(false)
        let checkStatus = AudioObjectIsPropertySettable(deviceId, &volAddress, &isSettable)
        guard checkStatus == noErr, isSettable.boolValue else { return false }

        var current: Float32 = 0
        var volSize = UInt32(MemoryLayout<Float32>.size)
        guard AudioObjectGetPropertyData(deviceId, &volAddress, 0, nil, &volSize, &current) == noErr else {
            return false
        }

        var next = max(0.0, min(1.0, current + delta))
        return AudioObjectSetPropertyData(deviceId, &volAddress, 0, nil, volSize, &next) == noErr
    }

    private func postSystemMediaKey(_ keyCode: Int32) {
        let send: (Bool) -> Void = { down in
            let flags = NSEvent.ModifierFlags(rawValue: down ? 0xA00 : 0xB00)
            let data1 = Int((keyCode << 16) | (down ? 0xA00 : 0xB00))
            guard let event = NSEvent.otherEvent(
                with: .systemDefined,
                location: .zero,
                modifierFlags: flags,
                timestamp: 0,
                windowNumber: 0,
                context: nil,
                subtype: 8,
                data1: data1,
                data2: -1
            ) else { return }
            event.cgEvent?.post(tap: .cghidEventTap)
        }
        send(true)
        send(false)
    }
}

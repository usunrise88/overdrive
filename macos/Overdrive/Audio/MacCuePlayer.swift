import AVFoundation
import Foundation
import OverdriveCore

/// Speaks Live Coach audio cues directly from memory via `AVAudioPlayer` (spec §12.1, ADR 0039, ADR 0044).
/// - Zero temp files on disk.
/// - Never opens a microphone or voice-chat audio session so Bluetooth headphones (AirPods) remain in stereo A2DP.
/// - Respects the rider's configurable `coachVolume` (macOS has no cross-app transient ducking API).
public final class MacCuePlayer: NSObject, CuePlayer, AVAudioPlayerDelegate {
    private let volumeProvider: () -> Float
    private let maxDurationSeconds: TimeInterval
    private let lock = NSLock()
    private var activePlayer: AVAudioPlayer?
    private var activeContinuation: CheckedContinuation<CuePlayResult, Never>?

    public init(volume: @escaping () -> Float = { 1.0 }, maxDurationSeconds: TimeInterval = 30.0) {
        self.volumeProvider = volume
        self.maxDurationSeconds = maxDurationSeconds
        super.init()
    }

    public func play(
        audio: KotlinByteArray,
        format: LiveAudioFormat,
        onStarted: @escaping () -> Void
    ) async throws -> CuePlayResult {
        let data = AppleBytes.shared.toNSData(bytes: audio)
        guard !data.isEmpty else { return .failed }

        return await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                do {
                    let player = try AVAudioPlayer(data: data)
                    player.delegate = self
                    player.volume = max(0.0, min(1.0, volumeProvider()))
                    guard player.prepareToPlay() else {
                        continuation.resume(returning: .failed)
                        return
                    }
                    lock.lock()
                    activePlayer?.stop()
                    activePlayer = player
                    activeContinuation = continuation
                    lock.unlock()

                    if player.play() {
                        onStarted()
                        DispatchQueue.global(qos: .userInitiated).asyncAfter(deadline: .now() + maxDurationSeconds) { [weak self] in
                            self?.finish(with: .failed)
                        }
                    } else {
                        finish(with: .failed)
                    }
                } catch {
                    continuation.resume(returning: .failed)
                }
            }
        } onCancel: {
            self.finish(with: .failed)
        }
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        finish(with: flag ? .played : .failed)
    }

    public func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        finish(with: .failed)
    }

    private func finish(with result: CuePlayResult) {
        lock.lock()
        let player = activePlayer
        let cont = activeContinuation
        activePlayer = nil
        activeContinuation = nil
        lock.unlock()

        player?.stop()
        cont?.resume(returning: result)
    }
}

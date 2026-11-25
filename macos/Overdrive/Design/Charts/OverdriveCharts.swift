import SwiftUI

/// Flat workout profile step data for rendering both the full ride-screen profile and the 432 x 36 overlay mini-profile.
public struct ProfileStepData: Equatable, Sendable {
    public let durationSeconds: Int
    public let startPctFtp: Double
    public let endPctFtp: Double
    public let isFreeRide: Bool
    public let isHeartRate: Bool
    public let zone: Int

    public init(
        durationSeconds: Int,
        startPctFtp: Double,
        endPctFtp: Double,
        isFreeRide: Bool = false,
        isHeartRate: Bool = false,
        zone: Int = 2
    ) {
        self.durationSeconds = max(1, durationSeconds)
        self.startPctFtp = startPctFtp
        self.endPctFtp = endPctFtp
        self.isFreeRide = isFreeRide
        self.isHeartRate = isHeartRate
        self.zone = zone
    }
}

public struct ProfileTracePoint: Equatable, Sendable {
    public let elapsedSeconds: Int
    public let pctFtp: Double

    public init(elapsedSeconds: Int, pctFtp: Double) {
        self.elapsedSeconds = elapsedSeconds
        self.pctFtp = pctFtp
    }
}

public struct WorkoutProfileData: Equatable, Sendable {
    public let steps: [ProfileStepData]
    public let totalSeconds: Int

    public init(steps: [ProfileStepData]) {
        self.steps = steps
        self.totalSeconds = max(1, steps.reduce(0) { $0 + $1.durationSeconds })
    }
}

/// Workout profile chart (design/system.md §8.1 & §5.3).
/// Draws step polygons coloured by power zone, opaque pre-blended past bars (no alpha over video),
/// optional FTP reference line, 10-minute axis ticks, live power trace, and a 2 pt now-marker.
public struct WorkoutProfileView: View {
    public let data: WorkoutProfileData
    public let elapsedSeconds: Int
    public var trace: [ProfileTracePoint] = []
    public var showFtpLine: Bool = true
    public var showAxisTicks: Bool = true
    public var surfaceRGB: (Double, Double, Double) = (0x11 / 255.0, 0x15 / 255.0, 0x1A / 255.0)

    public init(
        data: WorkoutProfileData,
        elapsedSeconds: Int,
        trace: [ProfileTracePoint] = [],
        showFtpLine: Bool = true,
        showAxisTicks: Bool = true,
        surfaceRGB: (Double, Double, Double) = (0x11 / 255.0, 0x15 / 255.0, 0x1A / 255.0)
    ) {
        self.data = data
        self.elapsedSeconds = elapsedSeconds
        self.trace = trace
        self.showFtpLine = showFtpLine
        self.showAxisTicks = showAxisTicks
        self.surfaceRGB = surfaceRGB
    }

    public var body: some View {
        Canvas { context, size in
            let tickHeight: CGFloat = showAxisTicks ? 18 : 0
            let chartHeight = max(8, size.height - tickHeight)
            let total = CGFloat(data.totalSeconds)
            let yMax = CGFloat(OverdriveDimens.chartProfileYMaxPctFtp) / 100.0
            let stepGap = OverdriveDimens.chartProfileStepGap
            let nowX = min(size.width, max(0, (CGFloat(elapsedSeconds) / total) * size.width))

            var cursorSeconds: CGFloat = 0
            for step in data.steps {
                let x0 = (cursorSeconds / total) * size.width
                cursorSeconds += CGFloat(step.durationSeconds)
                let x1 = max(x0 + 1, (cursorSeconds / total) * size.width - stepGap)

                let h0 = step.isFreeRide || step.isHeartRate
                    ? chartHeight * 0.35
                    : min(chartHeight, max(4, (CGFloat(step.startPctFtp) / yMax) * chartHeight))
                let h1 = step.isFreeRide || step.isHeartRate
                    ? chartHeight * 0.35
                    : min(chartHeight, max(4, (CGFloat(step.endPctFtp) / yMax) * chartHeight))

                var poly = Path()
                poly.move(to: CGPoint(x: x0, y: chartHeight))
                poly.addLine(to: CGPoint(x: x0, y: chartHeight - h0))
                poly.addLine(to: CGPoint(x: x1, y: chartHeight - h1))
                poly.addLine(to: CGPoint(x: x1, y: chartHeight))
                poly.closeSubpath()

                let futureFill: Color = (step.isFreeRide || step.isHeartRate)
                    ? OverdriveColors.surface3
                    : OverdriveColors.zone(step.zone)
                let pastFill: Color = (step.isFreeRide || step.isHeartRate)
                    ? OverdriveColors.surface2
                    : OverdriveColors.blendedZone(step.zone, over: surfaceRGB)

                if elapsedSeconds <= 0 || nowX <= x0 {
                    context.fill(poly, with: .color(futureFill))
                } else if nowX >= x1 {
                    context.fill(poly, with: .color(pastFill))
                } else {
                    var pastCtx = context
                    pastCtx.clip(to: Path(CGRect(x: x0, y: 0, width: nowX - x0, height: chartHeight)))
                    pastCtx.fill(poly, with: .color(pastFill))

                    var futureCtx = context
                    futureCtx.clip(to: Path(CGRect(x: nowX, y: 0, width: x1 - nowX, height: chartHeight)))
                    futureCtx.fill(poly, with: .color(futureFill))
                }
            }

            if showFtpLine {
                let ftpY = chartHeight - min(chartHeight, (1.0 / yMax) * chartHeight)
                var line = Path()
                line.move(to: CGPoint(x: 0, y: ftpY))
                line.addLine(to: CGPoint(x: size.width, y: ftpY))
                context.stroke(
                    line,
                    with: .color(OverdriveColors.borderStrong),
                    style: StrokeStyle(lineWidth: 1, dash: [4, 4])
                )
            }

            if trace.count >= 2 {
                var tracePath = Path()
                for (index, pt) in trace.enumerated() {
                    let px = min(size.width, max(0, (CGFloat(pt.elapsedSeconds) / total) * size.width))
                    let py = chartHeight - min(chartHeight, max(0, (CGFloat(pt.pctFtp) / yMax) * chartHeight))
                    if index == 0 {
                        tracePath.move(to: CGPoint(x: px, y: py))
                    } else {
                        tracePath.addLine(to: CGPoint(x: px, y: py))
                    }
                }
                context.stroke(
                    tracePath,
                    with: .color(OverdriveColors.textPrimary),
                    style: StrokeStyle(lineWidth: OverdriveDimens.strokeChartLine, lineCap: .round, lineJoin: .round)
                )
            }

            if elapsedSeconds > 0 {
                let marker = CGRect(x: max(0, min(size.width - 2, nowX - 1)), y: 0, width: 2, height: chartHeight)
                context.fill(Path(marker), with: .color(OverdriveColors.textPrimary))
            }

            if showAxisTicks {
                let tickInterval = 600 // 10 minutes
                var sec = 0
                while sec <= data.totalSeconds {
                    let x = (CGFloat(sec) / total) * size.width
                    let label = Text("\(sec / 60)m")
                        .font(OverdriveTypography.caption.font)
                        .foregroundStyle(OverdriveColors.textTertiary)
                    context.draw(label, at: CGPoint(x: min(size.width - 14, max(14, x)), y: chartHeight + 10))
                    sec += tickInterval
                }
            }
        }
    }
}

/// Horizontal time-in-power-zones distribution bars for the Finished screen (design/system.md §8.3).
public struct ZoneTimeBarsView: View {
    public let zoneSeconds: [Int]

    public init(zoneSeconds: [Int]) {
        self.zoneSeconds = zoneSeconds
    }

    public var body: some View {
        let total = max(1, zoneSeconds.reduce(0, +))
        VStack(spacing: OverdriveDimens.spaceS2) {
            ForEach(0..<7, id: \.self) { index in
                let seconds = index < zoneSeconds.count ? zoneSeconds[index] : 0
                let fraction = Double(seconds) / Double(total)
                HStack(spacing: OverdriveDimens.spaceS3) {
                    OdZoneChip(zone: index + 1)
                    Text(OverdriveColors.zoneNames[index])
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        .frame(width: 96, alignment: .leading)
                    GeometryReader { proxy in
                        ZStack(alignment: .leading) {
                            RoundedRectangle(cornerRadius: OverdriveDimens.radiusSm)
                                .fill(OverdriveColors.surface2)
                            RoundedRectangle(cornerRadius: OverdriveDimens.radiusSm)
                                .fill(OverdriveColors.zone(index + 1))
                                .frame(width: max(seconds > 0 ? 4 : 0, proxy.size.width * fraction))
                        }
                    }
                    .frame(height: 12)
                    Text(UiFormat.elapsed(seconds))
                        .odStyle(OverdriveTypography.numXS, color: seconds > 0 ? OverdriveColors.textPrimary : OverdriveColors.textTertiary)
                        .frame(width: 56, alignment: .trailing)
                }
            }
        }
    }
}

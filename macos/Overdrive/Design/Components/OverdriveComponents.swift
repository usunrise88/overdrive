import SwiftUI

// MARK: - Formatting (design/system.md §3)

public enum UiFormat {
    public static let emptyDash = "\u{2014}"
    public static let trueMinus = "\u{2212}"

    public static func elapsed(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        let h = clamped / 3600
        let m = (clamped % 3600) / 60
        let s = clamped % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%d:%02d", m, s)
    }

    public static func minutesAndSeconds(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        let m = clamped / 60
        let s = clamped % 60
        return String(format: "%d:%02d", m, s)
    }

    public static func durationWords(_ seconds: Int) -> String {
        let clamped = max(0, seconds)
        let h = clamped / 3600
        let m = (clamped % 3600) / 60
        if h > 0 && m > 0 { return "\(h)h \(m)m" }
        if h > 0 { return "\(h)h" }
        if m > 0 { return "\(m) min" }
        return "\(clamped) s"
    }

    public static func speed(_ kmh: Double) -> String {
        String(format: "%.1f", max(0.0, kmh))
    }

    public static func distanceKm(_ meters: Double) -> String {
        let km = max(0.0, meters / 1000.0)
        return km < 100.0 ? String(format: "%.2f", km) : String(format: "%.1f", km)
    }

    public static func signedInt(_ value: Int) -> String {
        if value < 0 { return "\(trueMinus)\(abs(value))" }
        if value > 0 { return "+\(value)" }
        return "0"
    }

    public static func signalLabel(rssi: Int) -> String {
        let word: String
        if rssi >= -60 {
            word = "Strong"
        } else if rssi >= -75 {
            word = "Good"
        } else {
            word = "Weak"
        }
        return "\(word) \u{00B7} \(trueMinus)\(abs(rssi)) dBm"
    }

    public static func fileSize(_ bytes: Int64) -> String {
        if bytes >= 1_000_000 {
            return String(format: "%.1f MB", Double(bytes) / 1_000_000.0)
        }
        return "\(max(1, bytes / 1000)) kB"
    }
}

// MARK: - Text Modifier

public extension View {
    func odStyle(_ style: OverdriveTextStyle, color: Color = OverdriveColors.textPrimary) -> some View {
        self
            .font(style.font)
            .tracking(style.tracking)
            .foregroundStyle(color)
            .lineSpacing(max(0, style.lineHeight - style.size))
    }
}

// MARK: - Icon (design/system.md §4)

public struct OdIcon: View {
    public let icon: OverdriveIcon
    public var size: CGFloat = OverdriveDimens.sizeIcon
    public var color: Color = OverdriveColors.textPrimary

    public init(_ icon: OverdriveIcon, size: CGFloat = OverdriveDimens.sizeIcon, color: Color = OverdriveColors.textPrimary) {
        self.icon = icon
        self.size = size
        self.color = color
    }

    public var body: some View {
        OverdriveIconShape(icon: icon)
            .stroke(color, style: StrokeStyle(lineWidth: OverdriveDimens.strokeIcon, lineCap: .round, lineJoin: .round))
            .frame(width: size, height: size)
    }
}

// MARK: - Spinner (design/system.md §7.7: the only continuous loop)

public struct OdSpinner: View {
    public var size: CGFloat = 20
    @State private var angle: Double = 0

    public init(size: CGFloat = 20) {
        self.size = size
    }

    public var body: some View {
        ZStack {
            Circle()
                .stroke(OverdriveColors.surface3, lineWidth: 2)
            Circle()
                .trim(from: 0, to: 0.28)
                .stroke(OverdriveColors.textSecondary, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                .rotationEffect(.degrees(angle))
        }
        .frame(width: size, height: size)
        .onAppear {
            withAnimation(.linear(duration: 1.0).repeatForever(autoreverses: false)) {
                angle = 360
            }
        }
    }
}

// MARK: - Buttons (design/system.md §7.1)

public enum OdButtonVariant {
    case primary
    case secondary
    case ghost
}

public struct OdButton: View {
    public let title: String?
    public let icon: OverdriveIcon?
    public let variant: OdButtonVariant
    public let height: CGFloat
    public let minWidth: CGFloat?
    public let enabled: Bool
    public let action: () -> Void

    public init(
        _ title: String? = nil,
        icon: OverdriveIcon? = nil,
        variant: OdButtonVariant = .secondary,
        height: CGFloat = OverdriveDimens.sizeButtonHeight,
        minWidth: CGFloat? = nil,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) {
        self.title = title
        self.icon = icon
        self.variant = variant
        self.height = height
        self.minWidth = minWidth
        self.enabled = enabled
        self.action = action
    }

    public var body: some View {
        Button(action: action) {
            HStack(spacing: OverdriveDimens.spaceS2) {
                if let icon {
                    OdIcon(icon, size: OverdriveDimens.sizeIcon, color: foreground)
                }
                if let title {
                    Text(title)
                        .odStyle(OverdriveTypography.bodyStrong, color: foreground)
                        .lineLimit(1)
                }
            }
            .padding(.horizontal, title != nil ? OverdriveDimens.spaceS5 : OverdriveDimens.spaceS3)
            .frame(minWidth: minWidth, minHeight: height, maxHeight: height)
            .background(background)
            .overlay(
                RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd)
                    .stroke(borderColor, lineWidth: borderWidth)
            )
            .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
    }

    private var foreground: Color {
        guard enabled else { return OverdriveColors.textTertiary }
        switch variant {
        case .primary: return OverdriveColors.textOnPrimary
        case .secondary: return OverdriveColors.textPrimary
        case .ghost: return OverdriveColors.textSecondary
        }
    }

    private var background: Color {
        guard enabled else {
            return variant == .ghost ? .clear : OverdriveColors.surface1
        }
        switch variant {
        case .primary: return OverdriveColors.textPrimary
        case .secondary: return OverdriveColors.surface2
        case .ghost: return .clear
        }
    }

    private var borderColor: Color {
        if !enabled { return variant == .secondary ? OverdriveColors.border : .clear }
        return variant == .secondary ? OverdriveColors.border : .clear
    }

    private var borderWidth: CGFloat {
        variant == .secondary ? OverdriveDimens.strokeHairline : 0
    }
}

// MARK: - Chips (design/system.md §7.2)

public struct OdChip: View {
    public let text: String
    public var icon: OverdriveIcon? = nil
    public var inverted: Bool = false

    public init(_ text: String, icon: OverdriveIcon? = nil, inverted: Bool = false) {
        self.text = text
        self.icon = icon
        self.inverted = inverted
    }

    public var body: some View {
        HStack(spacing: 6) {
            if let icon {
                OdIcon(icon, size: 14, color: inverted ? OverdriveColors.textOnPrimary : OverdriveColors.textSecondary)
            }
            Text(text)
                .odStyle(OverdriveTypography.label, color: inverted ? OverdriveColors.textOnPrimary : OverdriveColors.textSecondary)
        }
        .padding(.horizontal, OverdriveDimens.spaceS3)
        .frame(height: OverdriveDimens.sizeChipHeight)
        .background(inverted ? OverdriveColors.textPrimary : OverdriveColors.surface2)
        .clipShape(Capsule())
    }
}

public struct OdZoneChip: View {
    public let zone: Int

    public init(zone: Int) {
        self.zone = zone
    }

    public var body: some View {
        Text("Z\(zone)")
            .font(OverdriveTypography.caption.font.weight(.semibold))
            .monospacedDigit()
            .foregroundStyle(OverdriveColors.textOnPrimary)
            .padding(.horizontal, 6)
            .frame(minWidth: 28, minHeight: 20, maxHeight: 20)
            .background(OverdriveColors.zone(zone))
            .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusSm))
    }
}

// MARK: - Card & Notice (design/system.md §7.4, §7.6)

public struct OdCard<Content: View>: View {
    public var padding: CGFloat = OverdriveDimens.spaceS5
    public let content: () -> Content

    public init(padding: CGFloat = OverdriveDimens.spaceS5, @ViewBuilder content: @escaping () -> Content) {
        self.padding = padding
        self.content = content
    }

    public var body: some View {
        content()
            .padding(padding)
            .background(OverdriveColors.surface1)
            .overlay(
                RoundedRectangle(cornerRadius: OverdriveDimens.radiusLg)
                    .stroke(OverdriveColors.border, lineWidth: OverdriveDimens.strokeHairline)
            )
            .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusLg))
    }
}

public struct OdNotice: View {
    public let icon: OverdriveIcon
    public let title: String
    public let message: String?
    public let primaryAction: (String, () -> Void)?
    public let secondaryAction: (String, () -> Void)?

    public init(
        icon: OverdriveIcon = .triangleAlert,
        title: String,
        message: String? = nil,
        primaryAction: (String, () -> Void)? = nil,
        secondaryAction: (String, () -> Void)? = nil
    ) {
        self.icon = icon
        self.title = title
        self.message = message
        self.primaryAction = primaryAction
        self.secondaryAction = secondaryAction
    }

    public var body: some View {
        HStack(alignment: .center, spacing: OverdriveDimens.spaceS3) {
            OdIcon(icon, size: OverdriveDimens.sizeIcon, color: OverdriveColors.textPrimary)
            VStack(alignment: .leading, spacing: 2) {
                Text(title).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                if let message, !message.isEmpty {
                    Text(message).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            }
            Spacer(minLength: OverdriveDimens.spaceS2)
            HStack(spacing: OverdriveDimens.spaceS2) {
                if let primaryAction {
                    OdButton(primaryAction.0, variant: .secondary, height: 40, action: primaryAction.1)
                }
                if let secondaryAction {
                    OdButton(secondaryAction.0, variant: .ghost, height: 40, action: secondaryAction.1)
                }
            }
        }
        .padding(.horizontal, OverdriveDimens.spaceS4)
        .padding(.vertical, OverdriveDimens.spaceS3)
        .background(OverdriveColors.surface2)
        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
    }
}

// MARK: - Stat Tile (design/system.md §7.3)

public struct OdStatTile: View {
    public let label: String
    public let value: String
    public var unit: String? = nil
    public var trailingLabel: String? = nil
    public var style: OverdriveTextStyle = OverdriveTypography.numL
    public var valueColor: Color = OverdriveColors.textPrimary
    public var isEmpty: Bool = false

    public init(
        label: String,
        value: String,
        unit: String? = nil,
        trailingLabel: String? = nil,
        style: OverdriveTextStyle = OverdriveTypography.numL,
        valueColor: Color = OverdriveColors.textPrimary,
        isEmpty: Bool = false
    ) {
        self.label = label
        self.value = value
        self.unit = unit
        self.trailingLabel = trailingLabel
        self.style = style
        self.valueColor = valueColor
        self.isEmpty = isEmpty
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
            HStack {
                Text(label).odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                Spacer()
                if let trailingLabel {
                    Text(trailingLabel).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            }
            HStack(alignment: .firstTextBaseline, spacing: OverdriveDimens.spaceS1) {
                Text(isEmpty ? UiFormat.emptyDash : value)
                    .odStyle(style, color: isEmpty ? OverdriveColors.textTertiary : valueColor)
                    .lineLimit(1)
                if let unit, !isEmpty {
                    Text(unit)
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        .lineLimit(1)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, OverdriveDimens.spaceS5)
        .padding(.vertical, OverdriveDimens.spaceS4)
    }
}

// MARK: - Stepper Row (design/system.md §6.6)

public struct OdStepper: View {
    public let valueText: String
    public let canDecrement: Bool
    public let canIncrement: Bool
    public let onDecrement: () -> Void
    public let onIncrement: () -> Void

    public init(
        valueText: String,
        canDecrement: Bool = true,
        canIncrement: Bool = true,
        onDecrement: @escaping () -> Void,
        onIncrement: @escaping () -> Void
    ) {
        self.valueText = valueText
        self.canDecrement = canDecrement
        self.canIncrement = canIncrement
        self.onDecrement = onDecrement
        self.onIncrement = onIncrement
    }

    public var body: some View {
        HStack(spacing: OverdriveDimens.spaceS2) {
            OdButton(icon: .minus, variant: .secondary, height: 36, enabled: canDecrement, action: onDecrement)
            Text(valueText)
                .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary)
                .frame(minWidth: 72, alignment: .center)
            OdButton(icon: .plus, variant: .secondary, height: 36, enabled: canIncrement, action: onIncrement)
        }
    }
}

public struct OdDivider: View {
    public init() {}
    public var body: some View {
        Rectangle()
            .fill(OverdriveColors.border)
            .frame(height: OverdriveDimens.strokeHairline)
    }
}

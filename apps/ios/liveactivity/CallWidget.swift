import ActivityKit
import SwiftUI
import WidgetKit

/// A live call on the Lock Screen and in the Dynamic Island: the Agent's orb, what it's doing, the last line said, and
/// Mute and End. Tapping it opens the call's Session.
@main
struct CallWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: CallAttributes.self) { context in
            LockScreenCall(context: context)
                .activityBackgroundTint(Color.black.opacity(0.78))
                .activitySystemActionForegroundColor(.white)
                .widgetURL(sessionURL(context.attributes))
        } dynamicIsland: { context in
            let ink = Color(argb: context.attributes.ink)
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    HStack(spacing: 8) {
                        Orb(ink: ink, size: 34)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(context.attributes.agentName).font(.headline).lineLimit(1)
                            Text(status(context.state)).font(.caption).foregroundStyle(.secondary)
                        }
                    }.padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Elapsed(since: context.state.startedAt).font(.callout.monospacedDigit()).foregroundStyle(.secondary).padding(.trailing, 4)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(spacing: 10) {
                        if !context.state.caption.isEmpty {
                            Text(context.state.caption).font(.subheadline).lineLimit(2).frame(maxWidth: .infinity, alignment: .leading)
                        }
                        Controls(muted: context.state.muted)
                    }.padding(.top, 4)
                }
            } compactLeading: {
                Orb(ink: ink, size: 20)
            } compactTrailing: {
                if context.state.muted {
                    Image(systemName: "mic.slash.fill").foregroundStyle(.orange)
                } else {
                    Image(systemName: context.state.phase == "Speaking" ? "waveform" : "mic.fill").foregroundStyle(ink)
                }
            } minimal: {
                Orb(ink: ink, size: 20)
            }
            .keylineTint(ink)
            .widgetURL(sessionURL(context.attributes))
        }
    }
}

private struct LockScreenCall: View {
    let context: ActivityViewContext<CallAttributes>
    var body: some View {
        let ink = Color(argb: context.attributes.ink)
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                Orb(ink: ink, size: 38)
                VStack(alignment: .leading, spacing: 1) {
                    Text(context.attributes.agentName).font(.headline).foregroundStyle(.white).lineLimit(1)
                    Text(status(context.state)).font(.caption).foregroundStyle(.white.opacity(0.65))
                }
                Spacer()
                Elapsed(since: context.state.startedAt).font(.callout.monospacedDigit()).foregroundStyle(.white.opacity(0.65))
            }
            if !context.state.caption.isEmpty {
                Text("“\(context.state.caption)”").font(.subheadline).foregroundStyle(.white.opacity(0.85)).lineLimit(2)
            }
            Controls(muted: context.state.muted)
        }
        .padding(16)
    }
}

private struct Controls: View {
    let muted: Bool
    var body: some View {
        HStack(spacing: 10) {
            Button(intent: MuteCallIntent()) {
                Label(muted ? "Unmute" : "Mute", systemImage: muted ? "mic.slash.fill" : "mic.fill").frame(maxWidth: .infinity)
            }
            .tint(muted ? .orange : .white.opacity(0.25))
            Button(intent: EndCallIntent()) {
                Label("End", systemImage: "xmark").frame(maxWidth: .infinity)
            }
            .tint(.red)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.capsule)
        .font(.subheadline.weight(.semibold))
        .foregroundStyle(.white)
    }
}

/// The Agent's orb, still: Live Activities can't run the app's animation, so it keeps the light and the glow.
private struct Orb: View {
    let ink: Color
    let size: CGFloat
    var body: some View {
        Circle()
            .fill(RadialGradient(colors: [ink.mix(with: .white, by: 0.55), ink, ink.mix(with: .black, by: 0.35)],
                                 center: UnitPoint(x: 0.35, y: 0.3), startRadius: 0, endRadius: size * 0.75))
            .overlay(Circle().strokeBorder(.white.opacity(0.25), lineWidth: 0.5))
            .shadow(color: ink.opacity(0.6), radius: size * 0.18)
            .frame(width: size, height: size)
    }
}

/// A clock counting up from the call's start, updated by the system without the app.
private struct Elapsed: View {
    let since: Date
    var body: some View {
        Text(timerInterval: since...Date.distantFuture, countsDown: false).multilineTextAlignment(.trailing).frame(maxWidth: 64, alignment: .trailing)
    }
}

private func status(_ state: CallAttributes.ContentState) -> String {
    if state.muted { return "Muted" }
    switch state.phase {
    case "Listening": return "Listening"
    case "Thinking": return "Working on it"
    case "Speaking": return "Speaking"
    default: return "Connecting…"
    }
}

private func sessionURL(_ attributes: CallAttributes) -> URL? {
    URL(string: "kinuvo://chat/\(attributes.agentId)/\(attributes.sessionId)")
}

private extension Color {
    init(argb: Int64) {
        self.init(.sRGB, red: Double((argb >> 16) & 0xFF) / 255, green: Double((argb >> 8) & 0xFF) / 255, blue: Double(argb & 0xFF) / 255)
    }
}

import ARKit
import RealityKit
import SwiftUI

/// The camera screen. One instruction, one gesture.
struct CaptureView: View {

    @Environment(CaptureViewModel.self) private var model
    @Environment(AppSettings.self) private var settings
    @State private var showReports = false
    @State private var showSettings = false
    @State private var flashPoint: CGPoint?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                ARPreview(session: model.arSession.session)
                    .ignoresSafeArea()
                    .contentShape(Rectangle())
                    .onTapGesture { location in
                        guard model.canCapture else { return }
                        flashPoint = location
                        model.captureTapped(
                            at: location,
                            viewportSize: geometry.size,
                            interfaceOrientation: Self.interfaceOrientation
                        )
                        withAnimation(.easeOut(duration: 0.45)) { flashPoint = nil }
                    }

                if let flashPoint {
                    TargetMarker()
                        .position(flashPoint)
                        .allowsHitTesting(false)
                        .transition(.opacity)
                }

                overlay
            }
        }
        .statusBarHidden()
        .onAppear { model.onAppear() }
        .onDisappear { model.onDisappear() }
        .fullScreenCover(isPresented: reviewBinding) { ReviewView() }
        .sheet(isPresented: $showReports) { ReportsView() }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .alert("Could not take the photo", isPresented: errorBinding) {
            Button("OK") { model.dismissError() }
        } message: {
            if case let .failed(message) = model.phase { Text(message) }
        }
    }

    // MARK: - Overlay

    private var overlay: some View {
        VStack {
            HStack(alignment: .top) {
                CalibrationBadge(trackingState: model.arSession.trackingState)
                Spacer()
                Button { showReports = true } label: {
                    Label("\(model.pendingCount)", systemImage: "tray.full")
                        .labelStyle(.titleAndIcon)
                }
                .buttonStyle(.glassCircle)
                .accessibilityLabel("My reports, \(model.pendingCount) waiting to send")
            }
            .padding(.horizontal, 20)
            .padding(.top, 12)

            Spacer()

            if !settings.isConfigured {
                SetupPrompt { showSettings = true }
            } else if let advice = model.arSession.trackingAdvice {
                InstructionCard(text: advice, tone: .warning)
            } else if case .processing = model.phase {
                InstructionCard(text: "Preparing the photo...", tone: .neutral)
            } else {
                InstructionCard(text: "Tap the damaged part", tone: .primary)
            }

            if let message = model.lastSentMessage {
                Toast(text: message)
                    .task {
                        try? await Task.sleep(for: .seconds(3))
                        model.lastSentMessage = nil
                    }
            }
        }
        .padding(.bottom, 36)
        .animation(.easeInOut(duration: 0.2), value: model.lastSentMessage)
    }

    // MARK: - Bindings

    private var reviewBinding: Binding<Bool> {
        Binding(
            get: { if case .reviewing = model.phase { true } else { false } },
            set: { if !$0 { model.discard() } }
        )
    }

    private var errorBinding: Binding<Bool> {
        Binding(
            get: { if case .failed = model.phase { true } else { false } },
            set: { if !$0 { model.dismissError() } }
        )
    }

    private static var interfaceOrientation: UIInterfaceOrientation {
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.interfaceOrientation ?? .portrait
    }
}

// MARK: - AR preview

/// Hosts the `ARSession` in a RealityKit view.
///
/// RealityKit renders the camera feed for us, which keeps this file free of Metal. Nothing is
/// added to the scene: the app needs ARKit for `camera.intrinsics`, not for rendering content.
private struct ARPreview: UIViewRepresentable {
    let session: ARSession

    func makeUIView(context: Context) -> ARView {
        let view = ARView(frame: .zero, cameraMode: .ar, automaticallyConfigureSession: false)
        view.session = session
        view.environment.background = .cameraFeed()
        view.renderOptions = [
            .disableMotionBlur, .disableDepthOfField, .disableHDR,
            .disableGroundingShadows, .disableAREnvironmentLighting
        ]
        return view
    }

    func updateUIView(_ uiView: ARView, context: Context) {}
}

// MARK: - Overlay pieces

private struct TargetMarker: View {
    var body: some View {
        ZStack {
            Circle().stroke(.white, lineWidth: 3).frame(width: 64, height: 64)
            Circle().fill(.white).frame(width: 8, height: 8)
        }
        .shadow(radius: 4)
    }
}

private struct CalibrationBadge: View {
    let trackingState: TrackingState

    var body: some View {
        Label(text, systemImage: icon)
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(.ultraThinMaterial, in: Capsule())
            .foregroundStyle(trackingState == .normal ? .primary : .secondary)
            .accessibilityLabel("Camera calibration status: \(text)")
    }

    private var text: String {
        switch trackingState {
        case .normal: "Calibrated"
        case .limited: "Steadying"
        case .notAvailable: "Starting"
        }
    }

    private var icon: String {
        trackingState == .normal ? "checkmark.seal.fill" : "circle.dotted"
    }
}

private struct InstructionCard: View {
    enum Tone { case primary, warning, neutral }
    let text: String
    var tone: Tone = .primary

    var body: some View {
        Text(text)
            .font(.title3.weight(.semibold))
            .multilineTextAlignment(.center)
            .foregroundStyle(tone == .warning ? Color.orange : Color.primary)
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
            .padding(.horizontal, 32)
    }
}

private struct SetupPrompt: View {
    let action: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Text("This phone is not set up yet")
                .font(.headline)
            Button("Open Settings", action: action)
                .buttonStyle(.borderedProminent)
        }
        .padding(24)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
        .padding(.horizontal, 32)
    }
}

private struct Toast: View {
    let text: String

    var body: some View {
        Label(text, systemImage: "checkmark.circle.fill")
            .font(.subheadline)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial, in: Capsule())
            .padding(.top, 12)
            .transition(.move(edge: .bottom).combined(with: .opacity))
    }
}

private extension ButtonStyle where Self == GlassCircleButtonStyle {
    static var glassCircle: GlassCircleButtonStyle { GlassCircleButtonStyle() }
}

private struct GlassCircleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial, in: Capsule())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

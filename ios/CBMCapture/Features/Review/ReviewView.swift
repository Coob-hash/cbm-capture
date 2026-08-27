import SwiftUI

/// Confirm and describe the capture before it joins the queue.
///
/// The marker drawn here is positioned from `metadata.target.pixel` over the transmitted JPEG -
/// not from the raw screen tap. That makes this screen a live end-to-end check of the transform
/// chain: if the rotation applied to the pixels ever disagreed with the rotation applied to K
/// and the tap, the marker would visibly sit somewhere other than the damage the worker touched.
struct ReviewView: View {

    @Environment(CaptureViewModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @FocusState private var descriptionFocused: Bool

    var body: some View {
        NavigationStack {
            if case let .reviewing(state) = model.phase {
                content(state)
                    .navigationTitle("Check the photo")
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Retake") { model.discard() }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Send") {
                                descriptionFocused = false
                                model.send()
                            }
                            .fontWeight(.semibold)
                        }
                    }
            } else {
                ProgressView().task { dismiss() }
            }
        }
    }

    private func content(_ state: CaptureViewModel.ReviewState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                MarkedPhoto(preview: state.package)

                if state.isOffCentre {
                    GuidanceBanner(
                        icon: "exclamationmark.triangle.fill",
                        tone: .orange,
                        title: "Move closer to centre",
                        message: "The damage is near the edge of the photo, where the lens bends "
                               + "straight lines. Retake it with the damage nearer the middle for a "
                               + "more reliable location."
                    )
                }

                if !state.package.intrinsicsTrusted {
                    GuidanceBanner(
                        icon: "questionmark.circle.fill",
                        tone: .orange,
                        title: "Location will be checked by hand",
                        message: "This phone did not report a usable camera calibration, so the "
                               + "office will place this report on the building model manually. "
                               + "You can still send it."
                    )
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("What is wrong?").font(.headline)
                    TextField(
                        "For example: door handle detached, will not latch",
                        text: Binding(
                            get: { state.description },
                            set: { model.updateDescription($0) }
                        ),
                        axis: .vertical
                    )
                    .lineLimit(3...6)
                    .textFieldStyle(.roundedBorder)
                    .focused($descriptionFocused)
                    .submitLabel(.done)
                }

                DisclosureGroup("Technical details") {
                    TechnicalDetails(preview: state.package)
                }
                .font(.subheadline)
                .tint(.secondary)
            }
            .padding(20)
        }
        .scrollDismissesKeyboard(.interactively)
    }
}

// MARK: - Photo with the marker

private struct MarkedPhoto: View {
    let preview: CaptureViewModel.PackagePreview

    var body: some View {
        GeometryReader { geometry in
            let displayed = fittedSize(in: geometry.size)
            let origin = CGPoint(
                x: (geometry.size.width - displayed.width) / 2,
                y: (geometry.size.height - displayed.height) / 2
            )
            let marker = CGPoint(
                x: origin.x + preview.targetPixel.x / preview.imageSize.width * displayed.width,
                y: origin.y + preview.targetPixel.y / preview.imageSize.height * displayed.height
            )

            ZStack(alignment: .topLeading) {
                Image(uiImage: preview.image)
                    .resizable()
                    .scaledToFit()
                    .clipShape(RoundedRectangle(cornerRadius: 12))

                Circle()
                    .stroke(Color.white, lineWidth: 3)
                    .background(Circle().fill(Color.white.opacity(0.15)))
                    .frame(width: 40, height: 40)
                    .position(marker)
                    .shadow(radius: 3)
            }
        }
        .aspectRatio(preview.imageSize.width / preview.imageSize.height, contentMode: .fit)
        .accessibilityLabel("Photo of the damage, with a marker on the part you tapped")
    }

    private func fittedSize(in bounds: CGSize) -> CGSize {
        let scale = min(bounds.width / preview.imageSize.width,
                        bounds.height / preview.imageSize.height)
        return CGSize(width: preview.imageSize.width * scale,
                      height: preview.imageSize.height * scale)
    }
}

// MARK: - Details

private struct TechnicalDetails: View {
    let preview: CaptureViewModel.PackagePreview

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            row("Calibration", preview.intrinsicsSource.displayName)
            row("Trusted", preview.intrinsicsTrusted ? "Yes" : "No")
            row("Image", "\(Int(preview.imageSize.width)) x \(Int(preview.imageSize.height)) px")
            row("Focal length", String(format: "%.1f px", preview.focalLength))
            row("Target pixel", String(format: "%.0f, %.0f", preview.targetPixel.x, preview.targetPixel.y))
            row("Distance from centre", String(format: "%.0f%%", preview.centrality * 100))
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
        .padding(.top, 8)
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).foregroundStyle(.primary)
        }
    }
}

struct GuidanceBanner: View {
    let icon: String
    let tone: Color
    let title: String
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .foregroundStyle(tone)
                .font(.title3)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(message).font(.footnote).foregroundStyle(.secondary)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(tone.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
    }
}

import SwiftUI

/// Accessibility identifiers shared with the XCUITest acceptance suite (same names as the Android
/// test tags in apps/android/.../ui/Common.kt).
enum A11y {
    static let loading = "loading"
    static let errorState = "error_state"
    static let emptyState = "empty_state"
    static let retry = "retry_button"
    static let userList = "user_list"
    static let sortButton = "sort_button"
    static func userRow(_ id: Int64) -> String { "user_row_\(id)" }
    static func followButton(_ id: Int64) -> String { "follow_button_\(id)" }
    static func followedIndicator(_ id: Int64) -> String { "followed_indicator_\(id)" }

    static let detailName = "detail_name"
    static let detailReputation = "detail_reputation"
    static let detailLocation = "detail_location"
    static let detailWebsite = "detail_website"
    static let detailFollow = "detail_follow_button"
    static let detailFollowedIndicator = "detail_followed_indicator"
    static let detailNotFound = "detail_not_found"

    static func sortField(_ field: SortField) -> String { "sort_field_\(field.rawValue)" }
    static func sortDirection(_ direction: SortDirection) -> String { "sort_direction_\(direction.rawValue)" }
    static let sortApply = "sort_apply"
    static let sortCancel = "sort_cancel"
}

struct Avatar: View {
    let url: String?
    let size: CGFloat

    var body: some View {
        AsyncImage(url: url.flatMap(URL.init(string:))) { phase in
            if let image = phase.image {
                image.resizable().scaledToFill()
            } else {
                Image(systemName: "person.crop.circle.fill")
                    .resizable()
                    .foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .accessibilityHidden(true)
    }
}

struct FollowButton: View {
    let followed: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        Group {
            if followed {
                Button("Unfollow", action: action).buttonStyle(.bordered)
            } else {
                Button("Follow", action: action).buttonStyle(.borderedProminent)
            }
        }
        .disabled(!enabled)
    }
}

struct FollowedIndicator: View {
    var body: some View {
        Image(systemName: "star.fill")
            .foregroundStyle(.tint)
            .accessibilityLabel("Followed")
    }
}

/// Error / empty state with a retry action.
struct MessageView: View {
    let identifier: String
    let title: String
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
            Text(title).font(.headline)
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
            Button("Retry", action: onRetry)
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier(A11y.retry)
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(identifier)
    }
}

extension View {
    /// Presents a follow error once; dismissing it sends `onDismiss` (the store's dismiss intent).
    func followErrorAlert(_ error: CoreError?, onDismiss: @escaping () -> Void) -> some View {
        modifier(FollowErrorAlert(error: error, onDismiss: onDismiss))
    }
}

private struct FollowErrorAlert: ViewModifier {
    let error: CoreError?
    let onDismiss: () -> Void
    @State private var isPresented = false

    func body(content: Content) -> some View {
        content
            .onChange(of: error, initial: true) { _, newValue in
                isPresented = newValue != nil
            }
            .alert("Follow", isPresented: $isPresented) {
                Button("OK", role: .cancel, action: onDismiss)
            } message: {
                Text(error?.userMessage ?? "")
            }
    }
}

func formatReputation(_ reputation: Int64) -> String {
    reputation.formatted(.number)
}

extension CoreError {
    var userMessage: String {
        switch self {
        case .network: "Can't reach the server. Check your connection and try again."
        case let .http(code): "The server returned an error (\(code)). Please try again."
        case .decoding: "The server sent an unexpected response."
        case .storage: "Couldn't access saved follows. Follow state may have been reset."
        }
    }
}

import SwiftUI

struct UserListScreen: View {
    @ObservedObject var store: UserListStore
    let onUserTap: (Int64) -> Void
    let onSortTap: () -> Void

    var body: some View {
        content
            .navigationTitle("Top users")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Sort", action: onSortTap)
                        .accessibilityIdentifier(A11y.sortButton)
                }
            }
            .followErrorAlert(store.state.followError) { store.send(.dismissFollowError) }
    }

    @ViewBuilder
    private var content: some View {
        switch store.state.content {
        case .loading:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .accessibilityIdentifier(A11y.loading)
        case let .failed(error):
            MessageView(
                identifier: A11y.errorState,
                title: "Couldn't load users",
                message: error.userMessage,
                onRetry: { store.send(.retry) }
            )
        case .empty:
            MessageView(
                identifier: A11y.emptyState,
                title: "No users",
                message: "The server returned no users.",
                onRetry: { store.send(.retry) }
            )
        case let .loaded(users):
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(users) { user in
                        UserRow(
                            user: user,
                            followed: store.state.followed.contains(user.id),
                            pending: store.state.pendingFollows.contains(user.id),
                            onTap: { onUserTap(user.id) },
                            onToggle: { store.send(.toggleFollow(userId: user.id)) }
                        )
                        Divider()
                    }
                }
            }
            .accessibilityIdentifier(A11y.userList)
        }
    }
}

private struct UserRow: View {
    let user: User
    let followed: Bool
    let pending: Bool
    let onTap: () -> Void
    let onToggle: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            // Separate accessibility elements: the row (navigates), the indicator, the toggle.
            Button(action: onTap) {
                HStack(spacing: 12) {
                    Avatar(url: user.avatarUrl, size: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(user.displayName).font(.headline)
                        Text("Reputation \(formatReputation(user.reputation))")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(A11y.userRow(user.id))

            if followed {
                FollowedIndicator()
                    .accessibilityIdentifier(A11y.followedIndicator(user.id))
            }

            FollowButton(followed: followed, enabled: !pending, action: onToggle)
                .accessibilityLabel("\(followed ? "Unfollow" : "Follow") \(user.displayName)")
                .accessibilityIdentifier(A11y.followButton(user.id))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }
}

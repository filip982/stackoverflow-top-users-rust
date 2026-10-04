import SwiftUI

struct UserDetailScreen: View {
    @StateObject private var store: UserDetailStore

    init(store: @autoclosure @escaping () -> UserDetailStore) {
        _store = StateObject(wrappedValue: store())
    }

    var body: some View {
        Group {
            if let user = store.state.user {
                details(user)
            } else {
                ContentUnavailableView("User not found", systemImage: "person.slash")
                    .accessibilityIdentifier(A11y.detailNotFound)
            }
        }
        .navigationTitle(store.state.user?.displayName ?? "User")
        .navigationBarTitleDisplayMode(.inline)
        .followErrorAlert(store.state.followError) { store.send(.dismissFollowError) }
    }

    private func details(_ user: User) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                Avatar(url: user.avatarUrl, size: 120)

                HStack(spacing: 8) {
                    Text(user.displayName)
                        .font(.title2.bold())
                        .accessibilityIdentifier(A11y.detailName)
                    if store.state.isFollowed {
                        FollowedIndicator()
                            .accessibilityIdentifier(A11y.detailFollowedIndicator)
                    }
                }

                Text("Reputation \(formatReputation(user.reputation))")
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier(A11y.detailReputation)

                if let location = user.location, !location.isEmpty {
                    Label("Location: \(location)", systemImage: "mappin.and.ellipse")
                        .accessibilityIdentifier(A11y.detailLocation)
                }

                if let website = user.websiteUrl, !website.isEmpty, let url = URL(string: website) {
                    Link(destination: url) {
                        Label(website, systemImage: "link")
                    }
                    .accessibilityIdentifier(A11y.detailWebsite)
                }

                FollowButton(
                    followed: store.state.isFollowed,
                    enabled: !store.state.togglePending,
                    action: { store.send(.toggleFollow) }
                )
                .accessibilityIdentifier(A11y.detailFollow)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
    }
}

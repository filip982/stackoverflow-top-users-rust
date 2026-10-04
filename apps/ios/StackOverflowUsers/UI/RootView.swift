import SwiftUI

/// Navigation shell: list -> detail via `NavigationStack`, sort options as a sheet. The list store
/// (and its applied sort) outlives the detail/sort destinations.
struct RootView: View {
    private let gateway: any CoreGateway
    @StateObject private var listStore: UserListStore
    @State private var path: [Int64] = []
    @State private var isSortPresented = false

    init(gateway: any CoreGateway) {
        self.gateway = gateway
        _listStore = StateObject(wrappedValue: UserListStore(gateway: gateway))
    }

    var body: some View {
        NavigationStack(path: $path) {
            UserListScreen(
                store: listStore,
                onUserTap: { path.append($0) },
                onSortTap: { isSortPresented = true }
            )
            .navigationDestination(for: Int64.self) { userId in
                UserDetailScreen(store: UserDetailStore(
                    user: listStore.state.users.first { $0.id == userId },
                    gateway: gateway
                ))
            }
        }
        .sheet(isPresented: $isSortPresented) {
            SortOptionsScreen(applied: listStore.state.sort) { sort in
                if let sort {
                    listStore.send(.applySort(sort))
                }
                isSortPresented = false
            }
        }
    }
}

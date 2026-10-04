import SwiftUI

/// Presented as a sheet. Edits only change the store's draft; Apply hands the draft back through
/// `onFinish(sort)`, Cancel through `onFinish(nil)`. Sorting itself is done by the core.
struct SortOptionsScreen: View {
    @StateObject private var store: SortOptionsStore
    private let onFinish: (SortSpec?) -> Void

    init(applied: SortSpec, onFinish: @escaping (SortSpec?) -> Void) {
        _store = StateObject(wrappedValue: SortOptionsStore(applied: applied))
        self.onFinish = onFinish
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Sort by") {
                    ForEach(SortField.allCases, id: \.self) { field in
                        RadioRow(title: field.title, isSelected: store.state.draft.field == field) {
                            store.send(.selectField(field))
                        }
                        .accessibilityIdentifier(A11y.sortField(field))
                    }
                }
                Section("Order") {
                    ForEach(SortDirection.allCases, id: \.self) { direction in
                        RadioRow(title: direction.title, isSelected: store.state.draft.direction == direction) {
                            store.send(.selectDirection(direction))
                        }
                        .accessibilityIdentifier(A11y.sortDirection(direction))
                    }
                }
            }
            .navigationTitle("Sort users")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { store.send(.cancel) }
                        .accessibilityIdentifier(A11y.sortCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") { store.send(.apply) }
                        .accessibilityIdentifier(A11y.sortApply)
                }
            }
        }
        .onChange(of: store.state.outcome) { _, outcome in
            switch outcome {
            case let .applied(sort): onFinish(sort)
            case .cancelled: onFinish(nil)
            case nil: break
            }
        }
    }
}

private struct RadioRow: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                Text(title).foregroundStyle(.primary)
                Spacer()
                Image(systemName: isSelected ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(isSelected ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
            }
            .contentShape(Rectangle())
        }
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

private extension SortField {
    var title: String {
        switch self {
        case .reputation: "Reputation"
        case .name: "Name"
        case .creationDate: "Date created"
        case .modifiedDate: "Date updated"
        }
    }
}

private extension SortDirection {
    var title: String {
        switch self {
        case .ascending: "Ascending"
        case .descending: "Descending"
        }
    }
}

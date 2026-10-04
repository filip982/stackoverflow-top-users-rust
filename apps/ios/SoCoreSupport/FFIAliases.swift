// Hand-written companion to the generated SoCore.swift, compiled into the same `SoCore` module.
//
// Why: the generated module is named `SoCore` and also declares a class `SoCore`, so client code
// cannot disambiguate with `SoCore.User` (that resolves to the class). The app also declares its
// own `User`/`SortField`/`SortDirection`/`CoreError` models, which shadow the imported ones. These
// aliases give the gateway unambiguous names for the generated API. Keep this file free of logic.

public typealias FFICore = SoCore
public typealias FFIUser = User
public typealias FFISortField = SortField
public typealias FFISortDirection = SortDirection
public typealias FFICoreError = CoreError
public typealias FFIFollowObserver = FollowObserver
public typealias FFIFollowObservation = FollowObservation

/// `newCore(baseUrl:storagePath:)` from the generated API.
public func ffiNewCore(baseUrl: String, storagePath: String) -> FFICore {
    newCore(baseUrl: baseUrl, storagePath: storagePath)
}

/// `sortUsers(users:field:direction:)` from the generated API (a gateway method of the same name
/// would otherwise shadow the free function).
public func ffiSortUsers(_ users: [FFIUser], field: FFISortField, direction: FFISortDirection) -> [FFIUser] {
    sortUsers(users: users, field: field, direction: direction)
}

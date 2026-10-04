import Foundation
import Network

/// Switches the standalone mock-server's per-instance scenario (`POST /__scenario`).
///
/// Uses a raw TCP connection (Network.framework) rather than URLSession: the UI-test runner app has
/// an Xcode-generated Info.plist we don't control, so App Transport Security could block plain
/// HTTP from URLSession. Lower-level networking APIs are not subject to ATS.
enum MockServerControl {
    struct Failure: Error, CustomStringConvertible {
        let description: String
    }

    static func setScenario(_ scenario: String, baseURL: String, timeout: TimeInterval = 10) throws {
        guard let url = URL(string: baseURL), let host = url.host,
              let port = NWEndpoint.Port(rawValue: UInt16(url.port ?? 80)) else {
            throw Failure(description: "invalid mock base URL \(baseURL)")
        }
        let body = #"{"scenario":"\#(scenario)"}"#
        let request = [
            "POST /__scenario HTTP/1.1",
            "Host: \(host):\(port.rawValue)",
            "Content-Type: application/json",
            "Content-Length: \(body.utf8.count)",
            "Connection: close",
            "",
            body,
        ].joined(separator: "\r\n")

        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        let outcome = Outcome()
        connection.stateUpdateHandler = { state in
            if case let .failed(error) = state {
                outcome.finish(.failure(error))
            }
        }
        connection.start(queue: DispatchQueue(label: "mock-server-control"))
        connection.send(content: Data(request.utf8), completion: .contentProcessed { error in
            if let error {
                outcome.finish(.failure(error))
                return
            }
            connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, _, error in
                if let data, !data.isEmpty {
                    outcome.finish(.success(String(decoding: data, as: UTF8.self)))
                } else {
                    outcome.finish(.failure(error ?? Failure(description: "empty response")))
                }
            }
        })
        defer { connection.cancel() }

        guard let result = outcome.wait(timeout: timeout) else {
            throw Failure(description: "mock-server at \(baseURL) did not answer within \(timeout)s")
        }
        let response = try result.get()
        guard response.hasPrefix("HTTP/1.1 200") else {
            throw Failure(description: "set scenario \(scenario) -> \(response.prefix(80))")
        }
    }

    /// First completion wins; thread-safe.
    private final class Outcome: @unchecked Sendable {
        private let lock = NSLock()
        private let done = DispatchSemaphore(value: 0)
        private var result: Result<String, Error>?

        func finish(_ value: Result<String, Error>) {
            lock.lock()
            defer { lock.unlock() }
            guard result == nil else { return }
            result = value
            done.signal()
        }

        func wait(timeout: TimeInterval) -> Result<String, Error>? {
            guard done.wait(timeout: .now() + timeout) == .success else { return nil }
            lock.lock()
            defer { lock.unlock() }
            return result
        }
    }
}

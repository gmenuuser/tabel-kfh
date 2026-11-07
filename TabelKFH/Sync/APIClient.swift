import Foundation

/// A failed request to the farm server.
enum APIError: Error, LocalizedError, Equatable {
    /// 401: the device is not signed in or its access was revoked.
    case unauthorized
    /// No answer: no network, no server, timeout (`APIClient.offlineCodes`).
    case offline
    /// Any other non-2xx answer; `message` is the server's `{error}` (server/src/app.ts `fail()`).
    case server(status: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .unauthorized: return "Нужно войти заново"
        case .offline: return "Нет связи с сервером"
        case .server(_, let message): return message
        }
    }
}

/// JSON requests to the farm server with the device token (`Authorization: Bearer`).
struct APIClient {
    let base: URL
    let session: URLSession
    let token: () -> String?

    init(base: URL = URL(string: "https://kfh.cubely.ru")!, session: URLSession = .shared,
         token: @escaping () -> String?) {
        self.base = base
        self.session = session
        self.token = token
    }

    /// `path` starts with «/» and may carry a query («/sync/pull?since=5»).
    func send<T: Decodable>(_ method: String, _ path: String, body: Encodable?) async throws -> T {
        guard let url = URL(string: path, relativeTo: base)?.absoluteURL else {
            throw APIError.server(status: 0, message: "Неверный адрес запроса")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = token() { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            if error.code == .cancelled { throw CancellationError() }
            if Self.offlineCodes.contains(error.code) { throw APIError.offline }
            // TLS, certificate, bad address…: the network is there, the connection is wrong.
            throw APIError.server(status: 0, message: "Ошибка соединения с сервером")
        }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 401 { throw APIError.unauthorized }
        guard (200..<300).contains(status) else {
            let message = (try? JSONDecoder().decode(ServerError.self, from: data))?.error ?? "Сервер ответил \(status)"
            throw APIError.server(status: status, message: message)
        }
        do {
            return try JSONDecoder().decode(T.self, from: data)
        } catch {
            throw APIError.server(status: status, message: "Непонятный ответ сервера")
        }
    }

    /// The `URLError`s that mean «no connection now»: the queue waits and goes later.
    static let offlineCodes: Set<URLError.Code> = [
        .notConnectedToInternet, .networkConnectionLost, .timedOut, .cannotFindHost, .cannotConnectToHost,
        .dnsLookupFailed, .internationalRoamingOff, .dataNotAllowed,
    ]

    private struct ServerError: Decodable { let error: String }
}

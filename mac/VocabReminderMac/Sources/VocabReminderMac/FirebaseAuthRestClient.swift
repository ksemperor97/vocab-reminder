import Foundation

struct FirebasePasswordAuthConfig {
    var webApiKey: String
    var email: String
    var password: String
}

struct FirebaseAuthSession {
    var idToken: String
    var refreshToken: String
    var expiresInSeconds: Int
    var email: String
    var localId: String
}

enum FirebaseAuthError: Error, LocalizedError {
    case invalidConfig(String)
    case invalidResponse(String)
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case .invalidConfig(let message):
            return message
        case .invalidResponse(let message):
            return message
        case .http(let status, let body):
            return "Firebase Auth HTTP \(status): \(body)"
        }
    }
}

enum FirebaseAuthRequestFactory {
    static func signInURL(webApiKey: String) throws -> URL {
        try authURL(
            base: "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword",
            webApiKey: webApiKey
        )
    }

    static func refreshURL(webApiKey: String) throws -> URL {
        try authURL(base: "https://securetoken.googleapis.com/v1/token", webApiKey: webApiKey)
    }

    static func signInBody(email: String, password: String) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "email": email,
            "password": password,
            "returnSecureToken": true
        ])
    }

    static func refreshBody(refreshToken: String) -> Data {
        Data("grant_type=refresh_token&refresh_token=\(formEncode(refreshToken))".utf8)
    }

    static func parseSignIn(_ payload: [String: Any], fallbackEmail: String) throws -> FirebaseAuthSession {
        guard let idToken = payload["idToken"] as? String, idToken.isEmpty == false else {
            throw FirebaseAuthError.invalidResponse("Firebase Auth sign-in response missing idToken.")
        }
        return FirebaseAuthSession(
            idToken: idToken,
            refreshToken: payload["refreshToken"] as? String ?? "",
            expiresInSeconds: Int(payload["expiresIn"] as? String ?? "0") ?? 0,
            email: payload["email"] as? String ?? fallbackEmail,
            localId: payload["localId"] as? String ?? ""
        )
    }

    static func parseRefresh(_ payload: [String: Any], existingRefreshToken: String) throws -> FirebaseAuthSession {
        guard let idToken = payload["id_token"] as? String, idToken.isEmpty == false else {
            throw FirebaseAuthError.invalidResponse("Firebase Auth refresh response missing id_token.")
        }
        return FirebaseAuthSession(
            idToken: idToken,
            refreshToken: payload["refresh_token"] as? String ?? existingRefreshToken,
            expiresInSeconds: Int(payload["expires_in"] as? String ?? "0") ?? 0,
            email: "",
            localId: payload["user_id"] as? String ?? ""
        )
    }

    private static func authURL(base: String, webApiKey: String) throws -> URL {
        guard webApiKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            throw FirebaseAuthError.invalidConfig("Firebase Web API key is required.")
        }
        var components = URLComponents(string: base)
        components?.queryItems = [URLQueryItem(name: "key", value: webApiKey)]
        guard let url = components?.url else {
            throw FirebaseAuthError.invalidConfig("Firebase Auth URL is invalid.")
        }
        return url
    }

    private static func formEncode(_ value: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._*")
        return value
            .addingPercentEncoding(withAllowedCharacters: allowed)?
            .replacingOccurrences(of: "%20", with: "+") ?? ""
    }
}

final class FirebaseAuthRestClient {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func signInWithPassword(_ config: FirebasePasswordAuthConfig) async throws -> FirebaseAuthSession {
        let email = config.email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard email.isEmpty == false else { throw FirebaseAuthError.invalidConfig("Firebase email is required.") }
        guard config.password.isEmpty == false else { throw FirebaseAuthError.invalidConfig("Firebase password is required.") }
        let payload = try await post(
            url: FirebaseAuthRequestFactory.signInURL(webApiKey: config.webApiKey),
            body: FirebaseAuthRequestFactory.signInBody(email: email, password: config.password),
            contentType: "application/json; charset=utf-8"
        )
        return try FirebaseAuthRequestFactory.parseSignIn(payload, fallbackEmail: email)
    }

    func refreshIdToken(webApiKey: String, refreshToken: String) async throws -> FirebaseAuthSession {
        let token = refreshToken.trimmingCharacters(in: .whitespacesAndNewlines)
        guard token.isEmpty == false else { throw FirebaseAuthError.invalidConfig("Firebase refresh token is required.") }
        let payload = try await post(
            url: FirebaseAuthRequestFactory.refreshURL(webApiKey: webApiKey),
            body: FirebaseAuthRequestFactory.refreshBody(refreshToken: token),
            contentType: "application/x-www-form-urlencoded"
        )
        return try FirebaseAuthRequestFactory.parseRefresh(payload, existingRefreshToken: token)
    }

    private func post(url: URL, body: Data, contentType: String) async throws -> [String: Any] {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")
        request.httpBody = body

        let (responseData, response) = try await session.data(for: request)
        let responseText = String(data: responseData, encoding: .utf8) ?? ""
        guard let http = response as? HTTPURLResponse else {
            throw FirebaseAuthError.invalidResponse("Firebase Auth response was not HTTP.")
        }
        guard let payload = try JSONSerialization.jsonObject(with: responseData) as? [String: Any] else {
            throw FirebaseAuthError.invalidResponse("Firebase Auth response was not valid JSON.")
        }
        if !(200...299).contains(http.statusCode) {
            let message = (payload["error"] as? [String: Any])?["message"] as? String ?? responseText
            throw FirebaseAuthError.http(http.statusCode, message)
        }
        if let error = payload["error"] as? [String: Any] {
            throw FirebaseAuthError.invalidResponse(error["message"] as? String ?? "Firebase Auth failed.")
        }
        return payload
    }
}

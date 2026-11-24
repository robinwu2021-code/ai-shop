import CryptoKit
import Foundation
import Security

private let keychainService = "ai.neargo.shop.ops-api"

private struct ClientError: LocalizedError {
    let message: String
    var errorDescription: String? { message }

    init(_ message: String) {
        self.message = message
    }
}

private struct KeyLocation {
    let origin: String
    let staffNo: String
    let keyNo: UUID

    var keyId: String { keyNo.uuidString.lowercased() }

    var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: keychainService,
            kSecAttrAccount as String: "\(origin)/\(staffNo)/\(keyId)",
            kSecAttrSynchronizable as String: false,
        ]
    }

    func save(_ secret: String) throws {
        var item = query
        item[kSecValueData as String] = Data(secret.utf8)
        try check(SecItemAdd(item as CFDictionary, nil), "Save")
    }

    func read() throws -> String {
        var lookup = query
        lookup[kSecReturnData as String] = true
        lookup[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        try check(SecItemCopyMatching(lookup as CFDictionary, &result), "Read")
        guard let data = result as? Data, let secret = String(data: data, encoding: .utf8) else {
            throw ClientError("Keychain entry is unreadable")
        }
        return secret
    }

    func forget() throws {
        let status = SecItemDelete(query as CFDictionary)
        if status != errSecItemNotFound { try check(status, "Remove") }
    }

    private func check(_ status: OSStatus, _ action: String) throws {
        guard status == errSecSuccess else {
            throw ClientError("\(action) Keychain entry failed (\(status))")
        }
    }
}

private struct Enrollment: Encodable {
    let origin: String
    let staffNo: String
    let keyNo: String
    let secretHash: String

    init(_ location: KeyLocation, secret: String) {
        origin = location.origin
        staffNo = location.staffNo
        keyNo = location.keyId
        secretHash = SHA256.hash(data: Data(secret.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

private final class NoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) {
        completionHandler(nil)
    }
}

@main private struct OpsApiKeyClient {
    static let usage = """
    Usage:
      ops-api-key prepare <api-origin> <staff-no>
      ops-api-key describe <api-origin> <staff-no> <key-no>
      ops-api-key verify <api-origin> <staff-no> <key-no>
      ops-api-key request <api-origin> <staff-no> <key-no> <GET|POST|PUT|PATCH|DELETE> <path> [body.json|-]
      ops-api-key forget <api-origin> <staff-no> <key-no>

    Prepare stores a generated key in the macOS Keychain and prints the enrollment details.
    Paste the details into the operations console. Forget removes only the local entry.
    """

    static func main() async {
        do {
            try await run(Array(CommandLine.arguments.dropFirst()))
        } catch {
            FileHandle.standardError.write(Data("\(error.localizedDescription)\n".utf8))
            exit(EXIT_FAILURE)
        }
    }

    private static func run(_ args: [String]) async throws {
        if args == ["--help"] {
            print(usage)
            return
        }

        guard args.count >= 3,
              args[2].range(of: #"^[a-zA-Z0-9_.-]{1,64}$"#, options: .regularExpression) != nil else {
            throw ClientError(usage)
        }
        let origin = try apiOrigin(args[1])
        let staffNo = args[2]
        if args[0] == "prepare", args.count == 3 {
            let location = KeyLocation(origin: origin, staffNo: staffNo, keyNo: UUID())
            let secret = try generate()
            try location.save(secret)
            try printJSON(Enrollment(location, secret: secret))
            return
        }

        guard args.count >= 4, let keyNo = UUID(uuidString: args[3]) else { throw ClientError(usage) }
        let location = KeyLocation(origin: origin, staffNo: staffNo, keyNo: keyNo)
        switch (args[0], args.count) {
        case ("describe", 4):
            try printJSON(Enrollment(location, secret: location.read()))
        case ("forget", 4):
            try location.forget()
        case ("verify", 4):
            let (status, data) = try await send(location, method: "GET", path: "/ops/auth/me", body: nil)
            guard status == 200, let payload = try? JSONDecoder().decode(IdentityResult.self, from: data),
                  payload.code == 0, payload.data?.staffNo == staffNo else {
                throw ClientError("Key verification failed (HTTP \(status)). Check registration and staff number.")
            }
            print("Verified \(staffNo) / \(location.keyId)")
        case ("request", 6...7):
            let method = args[4].uppercased()
            guard ["GET", "POST", "PUT", "PATCH", "DELETE"].contains(method),
                  method != "GET" || args.count == 6 else { throw ClientError(usage) }
            let body = args.count == 7 ? try requestBody(args[6]) : nil
            let (status, data) = try await send(location, method: method, path: args[5], body: body)
            FileHandle.standardOutput.write(data)
            FileHandle.standardOutput.write(Data("\n".utf8))
            if !(200..<300).contains(status)
                || (try? JSONDecoder().decode(BusinessResult.self, from: data).code) != 0 {
                exit(EXIT_FAILURE)
            }
        default:
            throw ClientError(usage)
        }
    }

    private struct BusinessResult: Decodable { let code: Int }
    private struct IdentityResult: Decodable {
        struct Staff: Decodable { let staffNo: String }
        let code: Int
        let data: Staff?
    }

    private static func apiOrigin(_ value: String) throws -> String {
        guard var parts = URLComponents(string: value), let scheme = parts.scheme?.lowercased(),
              let host = parts.host?.lowercased(), !host.isEmpty, parts.user == nil, parts.password == nil,
              parts.path.isEmpty || parts.path == "/", parts.query == nil, parts.fragment == nil,
              scheme == "https" || (scheme == "http" && ["localhost", "127.0.0.1"].contains(host)) else {
            throw ClientError("Use an HTTPS API origin, or a loopback HTTP origin for local development")
        }
        parts.scheme = scheme
        parts.host = host
        parts.path = ""
        if (scheme == "https" && parts.port == 443) || (scheme == "http" && parts.port == 80) {
            parts.port = nil
        }
        guard let url = parts.url else { throw ClientError("Invalid API origin") }
        return url.absoluteString
    }

    private static func generate() throws -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, buffer.count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw ClientError("Could not generate an API key") }
        let encoded = Data(bytes).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return "oak_" + encoded
    }

    private static func requestBody(_ file: String) throws -> Data {
        if file == "-" { return FileHandle.standardInput.readDataToEndOfFile() }
        return try Data(contentsOf: URL(fileURLWithPath: file))
    }

    private static func send(_ key: KeyLocation, method: String, path: String, body: Data?) async throws
        -> (Int, Data) {
        guard path.hasPrefix("/ops/"), let parts = URLComponents(string: key.origin + path),
              parts.fragment == nil, let url = parts.url, parts.path.hasPrefix("/ops/"),
              !parts.path.contains("\\"), !parts.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) else {
            throw ClientError("Use a relative /ops/ endpoint path")
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 30
        configuration.httpShouldSetCookies = false
        let session = URLSession(configuration: configuration, delegate: NoRedirects(), delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("Bearer " + (try key.read()), forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ClientError("Invalid HTTP response") }
        guard !(300..<400).contains(http.statusCode) else { throw ClientError("API redirects are disabled") }
        return (http.statusCode, data)
    }

    private static func printJSON<T: Encodable>(_ value: T) throws {
        let data = try JSONEncoder().encode(value)
        print(String(decoding: data, as: UTF8.self))
    }
}

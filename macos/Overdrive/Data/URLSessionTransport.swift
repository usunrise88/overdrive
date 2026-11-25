import Foundation
import OverdriveCore

/// Ephemeral `URLSession`-backed implementation of every `OverdriveCore` HTTP verb (`PUT`, `GET`, `PATCH`, `POST`,
/// and `text/event-stream` SSE) with strict redirect blocking, no disk cache, no cookies, and secret redaction (ADR 0044).
public final class URLSessionTransport: NSObject,
    HttpTransport,
    HttpGetTransport,
    HttpPatchTransport,
    HttpPostTransport,
    HttpStreamTransport
{
    private let session: URLSession
    private let streamSession: URLSession
    private let redirectBlocker = RedirectBlocker()

    public init(requestTimeout: TimeInterval = 20.0, streamIdleTimeout: TimeInterval = 45.0) {
        let config = URLSessionConfiguration.ephemeral
        config.httpCookieStorage = nil
        config.httpShouldSetCookies = false
        config.urlCache = nil
        config.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        config.timeoutIntervalForRequest = requestTimeout
        config.timeoutIntervalForResource = max(60.0, requestTimeout * 3)

        let streamConfig = URLSessionConfiguration.ephemeral
        streamConfig.httpCookieStorage = nil
        streamConfig.httpShouldSetCookies = false
        streamConfig.urlCache = nil
        streamConfig.requestCachePolicy = .reloadIgnoringLocalAndRemoteCacheData
        streamConfig.timeoutIntervalForRequest = streamIdleTimeout
        streamConfig.timeoutIntervalForResource = 60 * 60 * 12

        self.session = URLSession(configuration: config, delegate: redirectBlocker, delegateQueue: nil)
        self.streamSession = URLSession(configuration: streamConfig, delegate: redirectBlocker, delegateQueue: nil)
        super.init()
    }

    deinit {
        session.invalidateAndCancel()
        streamSession.invalidateAndCancel()
    }

    // MARK: - HttpTransport (PUT)

    public func put(request: HttpRequest) async throws -> HttpAnswer {
        guard let url = URL(string: request.url) else {
            throw TransportException(failure: .other, cause: nil, hint: "InvalidURL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "PUT"
        urlRequest.setValue("Bearer \(request.bearerToken)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue(request.contentType, forHTTPHeaderField: "Content-Type")

        let sink = DataChunkSink()
        request.body.writeTo(sink: sink)
        urlRequest.httpBody = sink.data
        return try await execute(urlRequest, maxBytes: 16_384)
    }

    // MARK: - HttpGetTransport (GET)

    public func get(request: HttpGetRequest) async throws -> HttpAnswer {
        guard let url = URL(string: request.url) else {
            throw TransportException(failure: .other, cause: nil, hint: "InvalidURL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "GET"
        urlRequest.setValue("Bearer \(request.bearerToken)", forHTTPHeaderField: "Authorization")
        return try await execute(urlRequest, maxBytes: Int(request.maxBytes))
    }

    // MARK: - HttpPatchTransport (PATCH)

    public func patch(request: HttpPatchRequest) async throws -> HttpAnswer {
        guard let url = URL(string: request.url) else {
            throw TransportException(failure: .other, cause: nil, hint: "InvalidURL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "PATCH"
        urlRequest.setValue("Bearer \(request.bearerToken)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = Data(request.body.utf8)
        return try await execute(urlRequest, maxBytes: Int(request.maxBytes))
    }

    // MARK: - HttpPostTransport (POST)

    public func post(request: HttpPostRequest) async throws -> HttpAnswer {
        guard let url = URL(string: request.url) else {
            throw TransportException(failure: .other, cause: nil, hint: "InvalidURL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "POST"
        urlRequest.setValue("Bearer \(request.bearerToken)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("application/json", forHTTPHeaderField: "Content-Type")
        urlRequest.httpBody = Data(request.body.utf8)
        return try await execute(urlRequest, maxBytes: Int(request.maxBytes))
    }

    // MARK: - HttpStreamTransport (SSE GET)

    public func stream(
        request: HttpStreamRequest,
        onChunk: @escaping (String) async throws -> KotlinUnit
    ) async throws -> HttpAnswer {
        guard let url = URL(string: request.url) else {
            throw TransportException(failure: .other, cause: nil, hint: "InvalidURL")
        }
        var urlRequest = URLRequest(url: url)
        urlRequest.httpMethod = "GET"
        urlRequest.setValue("Bearer \(request.bearerToken)", forHTTPHeaderField: "Authorization")
        urlRequest.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        if let lastId = request.lastEventId, !lastId.isEmpty {
            urlRequest.setValue(lastId, forHTTPHeaderField: "Last-Event-ID")
        }

        do {
            let (bytes, response) = try await streamSession.bytes(for: urlRequest)
            guard let http = response as? HTTPURLResponse else {
                throw TransportException(failure: .other, cause: nil, hint: "NonHTTPResponse")
            }
            if http.statusCode != 200 {
                var errData = Data()
                for try await b in bytes {
                    errData.append(b)
                    if errData.count >= 4_096 { break }
                }
                return HttpAnswer(status: Int32(http.statusCode), body: String(data: errData, encoding: .utf8) ?? "")
            }

            var lineBuffer = Data()
            for try await b in bytes {
                try Task.checkCancellation()
                lineBuffer.append(b)
                if b == 0x0A { // '\n'
                    if let chunk = String(data: lineBuffer, encoding: .utf8) {
                        _ = try await onChunk(chunk)
                    }
                    lineBuffer.removeAll(keepingCapacity: true)
                }
            }
            if !lineBuffer.isEmpty, let tail = String(data: lineBuffer, encoding: .utf8) {
                _ = try await onChunk(tail)
            }
            return HttpAnswer(status: 200, body: "")
        } catch is CancellationError {
            throw CancellationError()
        } catch let tx as TransportException {
            throw tx
        } catch {
            throw mapError(error)
        }
    }

    // MARK: - Helpers

    private func execute(_ request: URLRequest, maxBytes: Int) async throws -> HttpAnswer {
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                throw TransportException(failure: .other, cause: nil, hint: "NonHTTPResponse")
            }
            if maxBytes > 0 && data.count > maxBytes {
                throw TransportException(failure: .other, cause: nil, hint: "BodyTooLarge")
            }
            let body = String(data: data, encoding: .utf8) ?? ""
            return HttpAnswer(status: Int32(http.statusCode), body: body)
        } catch is CancellationError {
            throw CancellationError()
        } catch let tx as TransportException {
            throw tx
        } catch {
            throw mapError(error)
        }
    }

    private func mapError(_ error: Error) -> TransportException {
        let ns = error as NSError
        guard ns.domain == NSURLErrorDomain else {
            return TransportException(failure: .other, cause: nil, hint: ns.domain)
        }
        switch ns.code {
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed:
            return TransportException(failure: .nameNotFound, cause: nil, hint: "NSURLError.DNS")
        case NSURLErrorNotConnectedToInternet, NSURLErrorDataNotAllowed:
            return TransportException(failure: .noNetwork, cause: nil, hint: "NSURLError.Offline")
        case NSURLErrorCannotConnectToHost:
            return TransportException(failure: .refused, cause: nil, hint: "NSURLError.CannotConnect")
        case NSURLErrorNetworkConnectionLost:
            return TransportException(failure: .dropped, cause: nil, hint: "NSURLError.ConnectionLost")
        case NSURLErrorTimedOut:
            return TransportException(failure: .timedOut, cause: nil, hint: "NSURLError.TimedOut")
        case NSURLErrorSecureConnectionFailed,
             NSURLErrorServerCertificateHasBadDate,
             NSURLErrorServerCertificateUntrusted,
             NSURLErrorServerCertificateHasUnknownRoot,
             NSURLErrorServerCertificateNotYetValid:
            return TransportException(failure: .secureConnection, cause: nil, hint: "NSURLError.TLS")
        case NSURLErrorAppTransportSecurityRequiresSecureConnection:
            return TransportException(failure: .networkBlocked, cause: nil, hint: "NSURLError.ATS")
        default:
            return TransportException(failure: .unreachable, cause: nil, hint: "NSURLError.\(ns.code)")
        }
    }
}

private final class DataChunkSink: NSObject, ChunkSink {
    var data = Data()
    func write(bytes: KotlinByteArray, offset: Int32, length: Int32) {
        let all = AppleBytes.shared.toNSData(bytes: bytes)
        let start = Int(offset)
        let end = min(all.count, start + Int(length))
        if start < end {
            data.append(all.subdata(in: start..<end))
        }
    }
}

private final class RedirectBlocker: NSObject, URLSessionTaskDelegate {
    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
    }
}

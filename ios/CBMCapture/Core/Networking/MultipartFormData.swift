import Foundation

/// Streams a `multipart/form-data` body to a temporary file.
///
/// Building the body in memory would mean holding the JPEG twice over, which on an older
/// handset with a queue of pending captures is a plausible way to be terminated for memory
/// pressure. `URLSession.upload(for:fromFile:)` streams from disk instead.
struct MultipartFormData {

    let boundary: String
    private var parts: [Part] = []

    private enum Part {
        case text(name: String, value: Data, contentType: String)
        case file(name: String, filename: String, url: URL, contentType: String)
    }

    init(boundary: String = "cbm-\(UUID().uuidString)") {
        self.boundary = boundary
    }

    var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    mutating func addField(name: String, json: Data) {
        parts.append(.text(name: name, value: json, contentType: "application/json"))
    }

    mutating func addField(name: String, text: String) {
        parts.append(.text(name: name, value: Data(text.utf8), contentType: "text/plain; charset=utf-8"))
    }

    mutating func addFile(name: String, filename: String, url: URL, contentType: String) {
        parts.append(.file(name: name, filename: filename, url: url, contentType: contentType))
    }

    /// Write the encoded body. The caller owns the returned file and must delete it.
    func writeBody(to url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let handle = try FileHandle(forWritingTo: url)
        defer { try? handle.close() }

        for part in parts {
            try handle.write(contentsOf: Data("--\(boundary)\r\n".utf8))
            switch part {
            case let .text(name, value, contentType):
                try handle.write(contentsOf: Data(
                    "Content-Disposition: form-data; name=\"\(name)\"\r\n".utf8))
                try handle.write(contentsOf: Data("Content-Type: \(contentType)\r\n\r\n".utf8))
                try handle.write(contentsOf: value)
                try handle.write(contentsOf: Data("\r\n".utf8))

            case let .file(name, filename, fileURL, contentType):
                try handle.write(contentsOf: Data(
                    "Content-Disposition: form-data; name=\"\(name)\"; filename=\"\(filename)\"\r\n".utf8))
                try handle.write(contentsOf: Data("Content-Type: \(contentType)\r\n\r\n".utf8))
                try Self.stream(fileURL, into: handle)
                try handle.write(contentsOf: Data("\r\n".utf8))
            }
        }
        try handle.write(contentsOf: Data("--\(boundary)--\r\n".utf8))
    }

    /// Copy in 256 KB chunks so peak memory stays flat regardless of image size.
    private static func stream(_ url: URL, into handle: FileHandle) throws {
        let reader = try FileHandle(forReadingFrom: url)
        defer { try? reader.close() }
        while let chunk = try reader.read(upToCount: 256 * 1024), !chunk.isEmpty {
            try handle.write(contentsOf: chunk)
        }
    }
}

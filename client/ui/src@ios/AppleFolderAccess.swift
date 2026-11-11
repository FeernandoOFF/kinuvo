import UIKit
import UniformTypeIdentifiers
import KotlinModules

/// Folders picked outside the sandbox, e.g. an Obsidian vault in iCloud Drive, kept as security-scoped bookmarks.
final class AppleFolderAccess: NSObject, FolderAccess, UIDocumentPickerDelegate {
    private let key = "kinuvo.folders"
    private let lock = NSLock()
    private var open = [String: URL]()
    private var completion: ((String?) -> Void)?

    private var presenter: UIViewController? {
        var controller = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }

    private var bookmarks: [String: Data] {
        get { UserDefaults.standard.dictionary(forKey: key) as? [String: Data] ?? [:] }
        set { UserDefaults.standard.set(newValue, forKey: key) }
    }

    func pick(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard self.completion == nil, let presenter = self.presenter else { completion(nil); return }
            self.completion = completion
            let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
            picker.delegate = self
            presenter.present(picker, animated: true)
        }
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first, url.startAccessingSecurityScopedResource() else { finish(nil); return }
        lock.lock()
        if let data = try? url.bookmarkData() { bookmarks[url.path] = data }
        open[url.path] = url
        lock.unlock()
        download(url)
        finish(url.path)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(nil) }

    /// Access lasts until the app quits, so the Server can read the folders whenever it runs. A folder that was moved
    /// or renamed is kept under its new path, which is returned so its settings can follow it.
    func restore() -> [String: String] {
        lock.lock()
        defer { lock.unlock() }
        var saved = bookmarks
        var moved = [String: String]()
        for (path, data) in saved where open[path] == nil {
            var stale = false
            guard let url = try? URL(resolvingBookmarkData: data, bookmarkDataIsStale: &stale),
                  url.startAccessingSecurityScopedResource() else { continue }
            if url.path != path {
                saved[path] = nil
                moved[path] = url.path
            }
            saved[url.path] = stale ? (try? url.bookmarkData()) ?? data : data
            open[url.path] = url
            download(url)
        }
        bookmarks = saved
        return moved
    }

    /// iCloud may keep notes only in the cloud; fetch them so Markdown Knowledge can read them.
    private func download(_ url: URL) {
        DispatchQueue.global(qos: .utility).async {
            let keys: Set<URLResourceKey> = [.isUbiquitousItemKey, .ubiquitousItemDownloadingStatusKey]
            guard let files = FileManager.default.enumerator(at: url, includingPropertiesForKeys: Array(keys)) else { return }
            for case let file as URL in files {
                guard let values = try? file.resourceValues(forKeys: keys), values.isUbiquitousItem == true,
                      values.ubiquitousItemDownloadingStatus != .current else { continue }
                // Older iOS keeps a hidden `.name.icloud` placeholder in place of a file that isn't downloaded.
                let name = file.lastPathComponent
                let item = name.hasPrefix(".") && name.hasSuffix(".icloud")
                    ? file.deletingLastPathComponent().appendingPathComponent(String(name.dropFirst().dropLast(7))) : file
                try? FileManager.default.startDownloadingUbiquitousItem(at: item)
            }
        }
    }

    private func finish(_ path: String?) {
        guard let completion else { return }
        self.completion = nil
        completion(path)
    }
}

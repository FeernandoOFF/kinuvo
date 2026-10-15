import UIKit
import PhotosUI
import UniformTypeIdentifiers
import KotlinModules

final class AppleAttachmentPicker: NSObject, AttachmentPicker, PHPickerViewControllerDelegate, UIDocumentPickerDelegate,
    UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    private var completion: ((String) -> Void)?
    private let maxBytes = 10 * 1024 * 1024

    private var presenter: UIViewController? {
        var controller = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }

    func pick(source: String, completion: @escaping (String) -> Void) {
        DispatchQueue.main.async {
            guard self.completion == nil, let presenter = self.presenter else {
                self.respond([], error: "Another picker is open", completion: completion); return
            }
            if source == "camera" && !UIImagePickerController.isSourceTypeAvailable(.camera) {
                self.respond([], error: "No camera on this device", completion: completion); return
            }
            self.completion = completion
            if source == "camera" {
                let picker = UIImagePickerController()
                picker.sourceType = .camera
                picker.delegate = self
                presenter.present(picker, animated: true)
            } else if source == "photos" {
                var config = PHPickerConfiguration()
                config.filter = .images
                config.selectionLimit = 4
                let picker = PHPickerViewController(configuration: config)
                picker.delegate = self
                presenter.present(picker, animated: true)
            } else {
                let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.item], asCopy: true)
                picker.allowsMultipleSelection = true
                picker.delegate = self
                presenter.present(picker, animated: true)
            }
        }
    }

    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)
        guard !results.isEmpty else { finish([]); return }
        let group = DispatchGroup()
        let lock = NSLock()
        var files = [Int: [String: String]]()
        var error: String?
        for (index, result) in results.enumerated() {
            group.enter()
            result.itemProvider.loadObject(ofClass: UIImage.self) { object, _ in
                defer { group.leave() }
                guard let image = object as? UIImage else {
                    lock.lock(); error = "Couldn't read photo"; lock.unlock(); return
                }
                guard let data = self.jpeg(image) else {
                    lock.lock(); error = "Photo exceeds 10 MB"; lock.unlock(); return
                }
                let name = (result.itemProvider.suggestedName ?? "Photo-\(index + 1)" as String) as NSString
                let file = ["name": name.deletingPathExtension + ".jpg", "base64": data.base64EncodedString()]
                lock.lock(); files[index] = file; lock.unlock()
            }
        }
        group.notify(queue: .main) { self.finish(files.keys.sorted().compactMap { files[$0] }, error: error) }
    }

    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage, let data = jpeg(image) else { finish([], error: "Couldn't read photo"); return }
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        finish([["name": "Photo-\(stamp).jpg", "base64": data.base64EncodedString()]])
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
        finish([])
    }

    /// At most 2048 px on the long side, upright, under the size limit.
    private func jpeg(_ image: UIImage) -> Data? {
        let scale = min(1, 2048 / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        guard let data = resized.jpegData(compressionQuality: 0.9), data.count <= maxBytes else { return nil }
        return data
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard urls.count <= 4 else { finish([], error: "Choose up to 4 files"); return }
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let files = try urls.map { url -> [String: String] in
                    let access = url.startAccessingSecurityScopedResource()
                    defer { if access { url.stopAccessingSecurityScopedResource() } }
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0, size <= self.maxBytes else { throw PickerError.size }
                    let data = try Data(contentsOf: url)
                    guard data.count <= self.maxBytes else { throw PickerError.size }
                    return ["name": url.lastPathComponent, "base64": data.base64EncodedString()]
                }
                DispatchQueue.main.async { self.finish(files) }
            } catch { DispatchQueue.main.async { self.finish([], error: error.localizedDescription) } }
        }
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish([]) }

    private func finish(_ files: [[String: String]], error: String? = nil) {
        guard let completion else { return }
        self.completion = nil
        respond(files, error: error, completion: completion)
    }

    private func respond(_ files: [[String: String]], error: String?, completion: (String) -> Void) {
        var response: [String: Any] = ["files": error == nil ? files : []]
        if let error { response["error"] = error }
        let data = try! JSONSerialization.data(withJSONObject: response)
        completion(String(data: data, encoding: .utf8)!)
    }

    func save(name: String, base64: String, completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard let data = Data(base64Encoded: base64), let presenter = self.presenter else {
                completion("Couldn't save attachment"); return
            }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            let file = dir.appendingPathComponent((name as NSString).lastPathComponent)
            do {
                try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try data.write(to: file)
                let sheet = UIActivityViewController(activityItems: [file], applicationActivities: nil)
                sheet.popoverPresentationController?.sourceView = presenter.view
                sheet.popoverPresentationController?.sourceRect = CGRect(x: presenter.view.bounds.midX, y: presenter.view.bounds.midY, width: 1, height: 1)
                sheet.completionWithItemsHandler = { _, _, _, error in
                    try? FileManager.default.removeItem(at: dir)
                    completion(error?.localizedDescription)
                }
                presenter.present(sheet, animated: true)
            } catch { try? FileManager.default.removeItem(at: dir); completion(error.localizedDescription) }
        }
    }

    private enum PickerError: LocalizedError {
        case size
        var errorDescription: String? { "Files must be between 1 byte and 10 MB" }
    }
}

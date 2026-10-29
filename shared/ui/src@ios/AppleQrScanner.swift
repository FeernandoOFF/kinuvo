import UIKit
import VisionKit
import KotlinModules

/** Scans a pairing QR code with VisionKit's live camera scanner, shown full screen with a Cancel button. */
final class AppleQrScanner: NSObject, QrScanner {
    private var session: AnyObject?

    /** Asked from Compose, which runs on the main thread. */
    var available: Bool {
        guard #available(iOS 16.0, *) else { return false }
        return MainActor.assumeIsolated { DataScannerViewController.isSupported }
    }

    private var presenter: UIViewController? {
        var controller = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first(where: \.isKeyWindow)?.rootViewController
        while let presented = controller?.presentedViewController { controller = presented }
        return controller
    }

    func scan(completion: @escaping (String?) -> Void) {
        DispatchQueue.main.async {
            guard #available(iOS 16.0, *), DataScannerViewController.isSupported, self.session == nil, let presenter = self.presenter else {
                completion(nil); return
            }
            let scan = ScanSession { [weak self] text in
                self?.session = nil
                completion(text)
            }
            self.session = scan
            presenter.present(scan.controller, animated: true)
        }
    }
}

@available(iOS 16.0, *)
@MainActor
private final class ScanSession: NSObject, DataScannerViewControllerDelegate {
    let controller: UINavigationController
    private let scanner = DataScannerViewController(recognizedDataTypes: [.barcode(symbologies: [.qr])], qualityLevel: .balanced,
        isHighlightingEnabled: true)
    private var finish: ((String?) -> Void)?

    init(finish: @escaping (String?) -> Void) {
        self.finish = finish
        controller = UINavigationController(rootViewController: scanner)
        super.init()
        scanner.delegate = self
        scanner.title = "Scan the pairing code"
        scanner.navigationItem.leftBarButtonItem = UIBarButtonItem(systemItem: .cancel, primaryAction: UIAction { [weak self] _ in self?.done(nil) })
        // Full screen, so it can't be swiped away without Cancel reporting it.
        controller.modalPresentationStyle = .fullScreen
        try? scanner.startScanning()
    }

    func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        for item in addedItems {
            if case let .barcode(code) = item, let text = code.payloadStringValue { done(text); return }
        }
    }

    private func done(_ text: String?) {
        guard let finish else { return }
        self.finish = nil
        scanner.stopScanning()
        controller.dismiss(animated: true) { finish(text) }
    }
}

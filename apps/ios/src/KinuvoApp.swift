import SwiftUI
import KotlinModules

@main
struct KinuvoApp: App {
    init() {
        #if DEBUG && targetEnvironment(simulator)
        MobileTestMode_iosKt.enableMobileTests()
        #endif
        Keychain_iosKt.installKeychain(native: AppleKeychain())
        LocalServer_iosKt.installAppleLanguageModel(model: AppleSystemLanguageModel())
        DeviceReader_iosKt.installDeviceReader(reader: AppleDeviceReader())
        Attachments_iosKt.installAttachmentPicker(picker: AppleAttachmentPicker())
        FolderPicker_iosKt.installFolderAccess(access: AppleFolderAccess())
        LocalServer_iosKt.installBackgroundRuns(keeper: AppleBackgroundRuns())
        Speech_iosKt.installSpeechEngine(engine: AppleSpeech())
    }
    var body: some Scene {
        WindowGroup {
            Group {
                if ProcessInfo.processInfo.arguments.contains("--native-shell") {
                    NativeShell()
                } else { KinuvoView().ignoresSafeArea() }
            }.onOpenURL { DeepLinksKt.openLink(url: $0.absoluteString) }
        }
    }
}

private struct KinuvoView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

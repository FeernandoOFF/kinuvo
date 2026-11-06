import SwiftUI
import KotlinModules

@main
struct KinuvoApp: App {
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var delegate

    init() {
        #if DEBUG && targetEnvironment(simulator)
        MobileTestMode_iosKt.enableMobileTests()
        #endif
        Keychain_iosKt.installKeychain(native: AppleKeychain())
        LocalServer_iosKt.installAppleLanguageModel(model: AppleSystemLanguageModel())
        DeviceReader_iosKt.installDeviceReader(reader: AppleDeviceReader())
        Attachments_iosKt.installAttachmentPicker(picker: AppleAttachmentPicker())
        FolderPicker_iosKt.installFolderAccess(access: AppleFolderAccess())
        QrScanner_iosKt.installQrScanner(scanner: AppleQrScanner())
        LocalServer_iosKt.installBackgroundRuns(keeper: AppleBackgroundRuns())
        Speech_iosKt.installSpeechEngine(engine: AppleSpeech())
        Push_iosKt.installPush(native: ApplePush.shared)
    }
    var body: some Scene {
        WindowGroup {
            Group {
                if ProcessInfo.processInfo.arguments.contains("--native-shell") {
                    NativeShell()
                } else { KinuvoView().ignoresSafeArea() }
            }.task { CallActivityController.shared.start() }.onOpenURL { DeepLinksKt.openLink(url: $0.absoluteString) }
        }
    }
}

private struct KinuvoView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

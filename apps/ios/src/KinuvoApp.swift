import SwiftUI
import KotlinModules

@main
struct KinuvoApp: App {
    @UIApplicationDelegateAdaptor(PushAppDelegate.self) private var delegate

    init() {
        #if DEBUG && targetEnvironment(simulator)
        MobileTestMode_iosKt.enableMobileTests()
        #endif
        let keychain = AppleKeychain()
        Keychain_iosKt.installKeychain(native: keychain)
        DeviceReader_iosKt.installDeviceReader(reader: AppleDeviceReader())
        Attachments_iosKt.installAttachmentPicker(picker: AppleAttachmentPicker())
        let folders = AppleFolderAccess()
        FolderPicker_iosKt.installFolderAccess(access: folders)
        QrScanner_iosKt.installQrScanner(scanner: AppleQrScanner())
        Speech_iosKt.installSpeechEngine(engine: AppleSpeech())
        Push_iosKt.installPush(native: ApplePush.shared)
        AppleTailcat.install()
        IosEmbeddedKt.startKinuvoWithEmbeddedServer(model: AppleSystemLanguageModel(), keychain: keychain, folders: folders, runs: AppleBackgroundRuns(), apple: AppleEventKitSystem())
    }
    var body: some Scene {
        WindowGroup {
            Group {
                if ProcessInfo.processInfo.arguments.contains("--native-shell") {
                    NativeShell()
                } else { ComposeApp() }
            }.task { CallActivityController.shared.start() }.onOpenURL { DeepLinksKt.openLink(url: $0.absoluteString) }
        }
    }
}

private struct ComposeApp: View {
    @State private var barsHidden = false
    @State private var observation: StateObservation?

    var body: some View {
        KinuvoView().ignoresSafeArea()
            .statusBarHidden(barsHidden)
            .persistentSystemOverlays(barsHidden ? .hidden : .automatic)
            .onAppear { observation = SystemBars_iosKt.observeSystemBarsHidden { barsHidden = $0.boolValue } }
            .onDisappear { observation?.cancel(); observation = nil }
    }
}

private struct KinuvoView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

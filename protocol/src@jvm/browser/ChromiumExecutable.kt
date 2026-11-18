package kinuvo.protocol.browser

import java.io.File
import kinuvo.protocol.device.Platform
import kinuvo.protocol.device.currentPlatform

fun findChromium(
    env: Map<String, String> = System.getenv(),
    os: Platform = currentPlatform,
    pathDirs: List<String> = env["PATH"].orEmpty().split(if (os == Platform.Windows) ';' else ':').filter { it.isNotBlank() },
    executable: (String) -> Boolean = { File(it).let { file -> file.isFile && file.canExecute() } },
    fallback: () -> String? = { null },
): String? {
    env["KINUVO_BROWSER_EXECUTABLE"]?.let {
        require(executable(it)) { "KINUVO_BROWSER_EXECUTABLE is not an executable file: $it" }
        return it
    }
    fallback()?.takeIf(executable)?.let { return it }
    val separator = if (os == Platform.Windows) "\\" else "/"
    fun join(root: String, name: String) = root.trimEnd('/', '\\') + separator + name
    val names = when (os) {
        Platform.Windows -> listOf("chrome.exe", "msedge.exe")
        Platform.Linux -> listOf("google-chrome", "google-chrome-stable", "chromium", "chromium-browser",
            "microsoft-edge", "microsoft-edge-stable", "brave-browser")
        else -> emptyList()
    }
    val installed = when (os) {
        Platform.Windows -> listOf("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA").flatMap { key ->
            env.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.let { root ->
                listOf("Google\\Chrome\\Application\\chrome.exe", "Microsoft\\Edge\\Application\\msedge.exe",
                    "BraveSoftware\\Brave-Browser\\Application\\brave.exe", "Chromium\\Application\\chrome.exe").map { join(root, it) }
            }.orEmpty()
        }
        Platform.Linux -> listOf("/opt/google/chrome/chrome")
        Platform.MacOS -> listOf("Google Chrome" to "Google Chrome", "Chromium" to "Chromium",
            "Microsoft Edge" to "Microsoft Edge", "Brave Browser" to "Brave Browser")
            .map { (app, binary) -> "/Applications/$app.app/Contents/MacOS/$binary" }
        else -> emptyList()
    }
    val paths = names.flatMap { name -> pathDirs.map { join(it, name) } }
    val extra = if (os == Platform.Linux) listOf("/snap/bin/chromium") else emptyList()
    return (installed + paths + extra).firstOrNull(executable)
}

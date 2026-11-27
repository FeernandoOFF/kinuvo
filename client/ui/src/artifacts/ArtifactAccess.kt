package kinuvo.ui.artifacts

import kinuvo.protocol.artifact.ArtifactCapabilities
import kinuvo.protocol.artifact.ArtifactNetwork

/** What the owner's grants let a page do beyond its own files: the [network] origins it may connect to, and whether [popups] may open. */
internal data class ArtifactAccess(val network: List<String> = emptyList(), val popups: Boolean = false) {
    val https get() = network.filter { it.startsWith("https://") }

    companion object {
        val None = ArtifactAccess()

        /** [effective] as enforced here: origins are checked again before they reach a policy, and pop-ups only count where the web view can open them. */
        fun of(effective: ArtifactCapabilities?) = effective?.let {
            ArtifactAccess(it.network.mapNotNull(ArtifactNetwork::origin).distinct(), it.popups && popupsSupported)
        } ?: None
    }
}

/** Whether this platform's web view opens the windows a page asks for; where it can't, pop-ups stay blocked even when granted. */
internal expect val popupsSupported: Boolean

internal enum class PopupAction {
    /** A window of the app, which keeps the page that opened it as its opener. */
    Window,

    /** The system browser. */
    Browser,

    /** Nothing opens. */
    Block,
}

/** What a granted `window.open` of [url] does: the Artifact's own pages, or a blank window the page fills itself, open in the app; web and mail links go to the browser; anything else is dropped. */
internal fun popupAction(url: String?, origin: ArtifactOrigin?): PopupAction {
    val text = url?.trim().orEmpty()
    return when {
        text.isEmpty() || text.equals("about:blank", ignoreCase = true) -> PopupAction.Window
        origin?.owns(text) == true -> PopupAction.Window
        text.substringBefore(':').lowercase() in BROWSER_SCHEMES && ':' in text -> PopupAction.Browser
        else -> PopupAction.Block
    }
}

private val BROWSER_SCHEMES = setOf("http", "https", "mailto")

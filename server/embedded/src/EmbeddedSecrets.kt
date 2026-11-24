package kinuvo.server.embedded

import kinuvo.server.plugin.createDirectories
import kinuvo.server.plugin.deleteTree
import kotlinx.io.files.Path

/** Wipes this on-device Server's entire data directory — Agents, Sessions, Memory, User Notes, Skills, attachments, settings,
 * trust rules and caches, including every secret store — and recreates it empty, so the next [EmbeddedServerHost.create] there starts a fresh install. */
fun eraseEmbeddedHome(home: Path) {
    deleteTree(home)
    home.createDirectories()
}

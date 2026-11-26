package kinuvo.ui.diagnostics

import kinuvo.app.diagnostics.LogSharing
import kinuvo.diagnostics.Diagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path

/** Shares this Device's [diagnostics]: the ZIP is written into [exports]. */
internal class DiagnosticsLogSharing(private val diagnostics: Diagnostics, private val exports: Path) : LogSharing {
    override fun crashedLastTime() = diagnostics.pendingCrash() != null

    override fun dismissCrash() = diagnostics.acknowledgeCrash()

    override suspend fun bundle(): String = withContext(Dispatchers.Default) { diagnostics.export(exports).toString() }
}

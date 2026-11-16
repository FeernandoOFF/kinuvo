package kinuvo.ui

import kinuvo.app.diagnostics.DiagnosticFilePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class DesktopDiagnosticFilePicker : DiagnosticFilePicker {
    override suspend fun pick(): String? = withContext(Dispatchers.Main) {
        FileDialog(null as Frame?, "Save diagnostics — review before sharing", FileDialog.SAVE).let { dialog ->
            try {
                dialog.file = "kinuvo-diagnostics-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.zip"
                dialog.isVisible = true
                dialog.file?.let { File(dialog.directory, it).absolutePath }
            } finally { dialog.dispose() }
        }
    }
}

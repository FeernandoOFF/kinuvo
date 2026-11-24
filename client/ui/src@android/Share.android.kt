package kinuvo.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import java.io.File

@Composable
internal actual fun rememberShare(): ((String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) { { text ->
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } }
}

@Composable
internal actual fun rememberShareFile(): ((String) -> Unit)? {
    val context = LocalContext.current
    return remember(context) { { path ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.attachments", File(path))
        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } }
}

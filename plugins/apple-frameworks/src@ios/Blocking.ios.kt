package kinuvo.plugins.apple

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual val blocking: CoroutineDispatcher = Dispatchers.Default

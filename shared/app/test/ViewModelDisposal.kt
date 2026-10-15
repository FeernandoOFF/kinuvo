package kinuvo.app

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.job

internal suspend fun ViewModelStore.clearAndJoin() {
    val jobs = keys().mapNotNull { get(it)?.viewModelScope?.coroutineContext?.job }
    clear()
    jobs.forEach { it.join() }
}

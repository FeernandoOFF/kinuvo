package kinuvo.app.home

import kinuvo.app.board.*
import kinuvo.app.connection.*

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kinuvo.protocol.ServerFeature
import kinuvo.protocol.supports
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/** [today] across the Server's Agents; [tasks] whether the Server keeps Tasks, [error] the task provider's failure. */
data class HomeState(val today: Today, val tasks: Boolean = false, val error: String? = null)

/** Home's Today, from the shared Session, Routine and Board caches; [tick] moves it to the current time. */
class HomeViewModel(
    app: StateFlow<AppState>,
    board: BoardRepository,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val zone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {
    private val now = MutableStateFlow(clock())
    val state: StateFlow<HomeState>
        field = MutableStateFlow(HomeState(today(app.value, BoardData(), now.value)))

    init {
        board.connection.bind(viewModelScope)
        viewModelScope.launch {
            app.map { it.capabilities.supports(ServerFeature.Tasks) }.distinctUntilChanged().filter { it }.first()
            board.load()
        }
        viewModelScope.launch {
            combine(app, board.state, now) { a, b, t -> HomeState(today(a, b, t), a.capabilities.supports(ServerFeature.Tasks), b.error) }
                .collect { state.value = it }
        }
    }

    fun tick() { now.value = clock() }

    private fun today(app: AppState, board: BoardData, now: Long) = Today.of(
        now, zone, app.sessions.lists.mapValues { it.value.sessions }, app.routines,
        board.tasks.takeIf { app.capabilities.supports(ServerFeature.Tasks) }, app.sessions.finished,
    )
}

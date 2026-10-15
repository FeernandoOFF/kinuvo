package kinuvo.app.board

import kinuvo.app.native.DestinationLifetime
import kinuvo.app.native.StateObservation

class BoardDestination(repository: BoardRepository) {
    private val lifetime = DestinationLifetime()
    val viewModel = lifetime.own(BoardViewModel(repository))
    val state get() = viewModel.state.value
    fun observe(onState: (BoardState) -> Unit): StateObservation = lifetime.observe(viewModel.state, onState)
    fun close() = lifetime.close()
}

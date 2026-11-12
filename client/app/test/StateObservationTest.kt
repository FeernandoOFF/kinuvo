package kinuvo.app

import androidx.lifecycle.ViewModel
import kinuvo.app.native.DestinationLifetime
import kinuvo.app.native.observe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class StateObservationTest {
    @Test fun cancellationStopsCallbacksAndARecreatedObserverGetsTheCurrentState() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val state = MutableStateFlow(1)
            val seen = mutableListOf<Int>()
            val observation = state.observe(seen::add)
            runCurrent()
            state.value = 2
            runCurrent()
            observation.cancel()
            state.value = 3
            runCurrent()
            assertEquals(listOf(1, 2), seen)
            val next = state.observe(seen::add)
            runCurrent()
            assertEquals(listOf(1, 2, 3), seen)
            next.cancel()
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }

    @Test fun destinationStopsObservingBeforeClearingViewModelsAndCloseIsIdempotent() = runTest {
        kotlinx.coroutines.Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val state = MutableStateFlow(1)
            val seen = mutableListOf<Int>()
            var cleared = 0
            val destination = DestinationLifetime()
            destination.own(object : ViewModel() {
                override fun onCleared() { cleared++; state.value = 2 }
            })
            destination.observe(state, seen::add)
            runCurrent()
            destination.close()
            destination.close()
            runCurrent()
            assertEquals(1, cleared)
            assertEquals(listOf(1), seen)
            assertFailsWith<IllegalStateException> { destination.observe(state, seen::add) }
        } finally { kotlinx.coroutines.Dispatchers.resetMain() }
    }
}

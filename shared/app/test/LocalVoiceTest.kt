package kinuvo.app

import kinuvo.app.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LocalVoiceTest {
    private class Repository : LocalVoiceRepository {
        override val models = listOf(VoiceModel("tiny", "Tiny", VoiceModelKind.Transcription, "MIT", "source", "languages", emptyList()))
        val ready = mutableSetOf<String>()
        var chosen: String? = null
        var fail = false
        var wait = false
        var canceled = false
        override fun installed(): Set<String> = ready.toSet()
        override fun selected(kind: VoiceModelKind) = chosen
        override fun select(kind: VoiceModelKind, id: String?) { chosen = id }
        override var language = "en"
        override suspend fun download(model: VoiceModel, progress: (Long) -> Unit) {
            try {
                progress(23)
                if (fail) error("Hash mismatch")
                if (wait) awaitCancellation()
                ready += model.id
            } finally { canceled = true }
        }
        override suspend fun remove(model: VoiceModel) { ready -= model.id }
    }
    @Test fun downloadDoesNotSelectAndFailuresCanRetryAndRemovalClearsSelection() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository()
            val vm = LocalVoiceViewModel(repo)
            vm.select(VoiceModelKind.Transcription, "tiny")
            assertNull(repo.chosen)
            repo.fail = true
            vm.download("tiny"); runCurrent()
            assertEquals("Hash mismatch", vm.state.value.error)
            assertNull(vm.state.value.downloading)
            repo.fail = false
            vm.download("tiny"); runCurrent()
            assertEquals(setOf("tiny"), vm.state.value.installed)
            assertNull(repo.chosen)
            vm.select(VoiceModelKind.Synthesis, "tiny")
            assertNull(repo.chosen)
            vm.select(VoiceModelKind.Transcription, "tiny")
            assertEquals("tiny", repo.chosen)
            vm.remove("tiny"); runCurrent()
            assertNull(vm.state.value.transcription)
            assertTrue(vm.state.value.installed.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
    @Test fun cancelReleasesDownloadAndAllowsRetryWithoutSelectingAnIncompleteModel() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = Repository().apply { wait = true }
            val vm = LocalVoiceViewModel(repo)
            vm.download("tiny"); runCurrent()
            assertEquals(23, vm.state.value.downloaded)
            vm.cancel(); runCurrent()
            assertTrue(repo.canceled)
            assertNull(vm.state.value.downloading)
            assertNull(vm.state.value.error)
            assertTrue(vm.state.value.installed.isEmpty())
            repo.wait = false
            vm.download("tiny"); runCurrent()
            assertEquals(setOf("tiny"), vm.state.value.installed)
        } finally { Dispatchers.resetMain() }
    }
}

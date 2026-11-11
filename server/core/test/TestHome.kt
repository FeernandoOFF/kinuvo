package kinuvo.server

import kinuvo.harness.Agent
import kinuvo.server.store.AgentFiles
import kinuvo.server.store.FileAgentDocs
import kinuvo.server.store.FileRoutineRepository
import kinuvo.server.store.FileSkillRepository
import kinuvo.server.store.FileStore
import kinuvo.server.store.FileTrustRules
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.time.Clock

/** A fresh Kinuvo home in a temp folder with file-backed repositories. */
class TestHome(clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }) {
    val files = AgentFiles(Path(SystemTemporaryDirectory, "kinuvo-test-${Random.nextLong().toULong().toString(16)}"))
    val store = FileStore(files, clock)
    val docs = FileAgentDocs(files)
    val trustRules = FileTrustRules(files)
    val routineRepository = FileRoutineRepository(files)
    val skills = FileSkillRepository(files, userHome = null)
    val agent: Agent = runBlocking { store.agents().single() }
}

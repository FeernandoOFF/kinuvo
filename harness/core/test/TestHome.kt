package kinuvo.harness.core

import kinuvo.harness.core.store.AgentFiles
import kinuvo.harness.core.store.FileAgentDocs
import kinuvo.harness.core.store.FileRoutineRepository
import kinuvo.harness.core.store.FileSkillRepository
import kinuvo.harness.core.store.FileStore
import kinuvo.harness.core.store.FileTrustRules
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random

/** A fresh Kinuvo home in a temp folder with file-backed repositories. */
class TestHome {
    val files = AgentFiles(Path(SystemTemporaryDirectory, "kinuvo-test-${Random.nextLong().toULong().toString(16)}"))
    val store = FileStore(files)
    val docs = FileAgentDocs(files)
    val trustRules = FileTrustRules(files)
    val routineRepository = FileRoutineRepository(files)
    val skills = FileSkillRepository(files, userHome = null)
    val agent: Agent = runBlocking { store.agents().single() }
}

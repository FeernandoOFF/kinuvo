package kinuvo.harness.server.testing

import kinuvo.harness.core.plugin.BrowserService
import kinuvo.harness.core.plugin.HarnessPlugin
import kinuvo.harness.core.plugin.ScreenGate
import kinuvo.harness.core.plugin.ScreenSource
import kinuvo.protocol.BrowserCommand
import kinuvo.protocol.BrowserFrame
import kinuvo.protocol.BrowserOwner
import kinuvo.protocol.BrowserTab
import kinuvo.protocol.Integration
import kinuvo.protocol.PluginDefinition
import kinuvo.protocol.ScreenCapabilities
import kinuvo.protocol.ScreenDelivery
import kinuvo.protocol.ScreenRef
import kinuvo.protocol.ScreenSample
import kinuvo.protocol.ServerFeature
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A 128x80 test card, as the image of every [FakeBrowser] and [FakeComputer] frame. */
const val TEST_CARD_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAIAAAABQCAIAAABeYuqzAAAAqElEQVR4nO3RsQkAMAzAsPz/bIdC556RwQIfYNCc+7TYrB/EAwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2AAC0AwCgHQAA7QAAaAcAQDsAANoBANAOAIB2H2laxqC4EjqVAAAAAElFTkSuQmCC"

/** A Browser holding [tabs], each showing the [TEST_CARD_PNG]; control is the real [ScreenGate], input is accepted and ignored. */
class FakeBrowser(private val tabs: List<BrowserTab>) : BrowserService {
    private val gates = ConcurrentHashMap<String, ScreenGate>()
    private val listeners = CopyOnWriteArrayList<(BrowserOwner) -> Unit>()
    val commands = CopyOnWriteArrayList<BrowserCommand>()

    private fun gateFor(tabId: String) = gates.getOrPut(tabId) { ScreenGate() }

    override fun openingGate(owner: BrowserOwner) = gateFor("opening-${owner.agentId}")

    override suspend fun gate(owner: BrowserOwner, tabId: String) = gateFor(tabId)

    override suspend fun controlChanged(owner: BrowserOwner, tabId: String) { listeners.forEach { it(owner) } }

    override fun onChange(listener: (BrowserOwner) -> Unit) { listeners += listener }

    override suspend fun owners() = tabs.map { BrowserOwner(it.agentId) }.distinct()

    override suspend fun tabs(owner: BrowserOwner) = tabs.filter { it.agentId == owner.agentId }

    override fun screen(owner: BrowserOwner, tabId: String) = object : ScreenSource {
        override val ref = ScreenRef("browser", tabId, owner)
        override val capabilities = ScreenCapabilities(ScreenDelivery.Continuous, setOf("tap", "type", "key", "scroll"), navigation = true, tabs = true)
        override fun frames(thumbnail: Boolean): Flow<ScreenSample> = flow {
            emit(ScreenSample(TEST_CARD_PNG, 128, 80, "fixture"))
            awaitCancellation()
        }
    }

    override suspend fun userCommand(command: BrowserCommand) = command(command, user = true)

    override suspend fun command(command: BrowserCommand, user: Boolean): BrowserFrame {
        commands += command
        val tab = tabs.firstOrNull { it.id == command.tabId } ?: tabs.first()
        return BrowserFrame(tab, "fake")
    }
}

/** Gives a Server a [FakeBrowser], so Agents have the browser tool group and clients see its tabs. */
class FakeBrowserPlugin(override val browser: FakeBrowser) : HarnessPlugin {
    override val definition = PluginDefinition("test.browser", "Test browser")
    override val features = setOf(ServerFeature.Browser)
    override val integrations = mapOf(Integration.Tools to setOf("browser"))
}

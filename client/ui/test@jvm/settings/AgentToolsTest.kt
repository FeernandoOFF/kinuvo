package kinuvo.ui.settings

import kinuvo.protocol.tool.ToolNames
import kotlin.test.*

class AgentToolsTest {
    @Test fun everyOptionalToolGroupHasASwitch() =
        assertEquals(ToolNames.OPTIONAL.toSet() - ToolNames.AGENT_MANAGE, TOOLS.map { it.first }.toSet())
}

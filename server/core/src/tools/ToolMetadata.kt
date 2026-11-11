package kinuvo.server.tools

import kinuvo.harness.ToolSpec
import kinuvo.server.plugin.Tool
import kinuvo.server.plugin.modelAlias

/** [descriptor] with [this] tool's identity and flags. */
fun Tool.spec(descriptor: ToolSpec = this.descriptor) = descriptor.copy(
    identity = identity,
    aliases = listOfNotNull(identity?.modelAlias()),
    repeatable = repeatable,
    internal = internal,
    search = coreMetadata?.search == true,
    paging = coreMetadata?.paging == true,
)

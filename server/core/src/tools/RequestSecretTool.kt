package kinuvo.server.tools

import kinuvo.server.plugin.CoreTool
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.server.plugin.requireString
import kinuvo.server.plugin.toolSpec
import kinuvo.protocol.tool.ToolNames
import kotlinx.serialization.json.JsonObject

/** Asks the user privately for a credential; the runner does the asking, and the Agent gets only a Secret reference. */
class RequestSecretTool : CoreTool {
    override val descriptor = toolSpec(
        ToolNames.REQUEST_SECRET,
        "Ask the user privately for a credential, such as a password or API token. They pick it in their password manager " +
            "or type it on a private card; you get a Secret reference like \${pass://Vault/Item/field} or \${secret://id}, never " +
            "the value. Pass it in shell's secrets or as browser_type's text. Never ask for credentials in chat, and respect a decline.",
        listOf(
            ToolParameter("label", "What the credential is, e.g. GitHub token", ParameterType.String),
            ToolParameter("reason", "Why you need it, shown to the user", ParameterType.String),
        ),
    )

    override suspend fun bind(args: JsonObject): suspend () -> Unit {
        label(args); reason(args)
        return {}
    }

    override suspend fun execute(args: JsonObject): String = error("request_secret runs through the runner")

    companion object {
        fun label(args: JsonObject) = args.requireString("label").trim().also { require(it.isNotEmpty() && it.length <= 200) { "Give a label of at most 200 characters" } }
        fun reason(args: JsonObject) = args.requireString("reason").trim().also { require(it.isNotEmpty() && it.length <= 500) { "Give a reason of at most 500 characters" } }
    }
}

package kinuvo.harness.core.tools

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import kinuvo.protocol.Risk
import kinuvo.protocol.SESSION_SECRET_SCHEME
import kotlinx.serialization.json.JsonObject

/**
 * Asks the user for a credential and hands the Agent a Secret reference to it, never the value. The runner does the
 * asking in a run; [ask] does it for callers outside one, such as External Agents over MCP.
 */
class RequestSecretTool(private val ask: (suspend (label: String, reason: String?) -> String?)? = null) : Tool {
    override val kind = ToolKind.RequestSecret
    override val risk = Risk.Low
    override val descriptor = ToolDescriptor(
        name = ToolNames.REQUEST_SECRET,
        description = "Ask the user for a credential (token, password, API key) without seeing it: they pick it from their " +
            "password manager or type it, and you get a Secret reference to use, never the value. Use it instead of asking " +
            "for credentials in chat. Returns the reference, or says the user declined.",
        requiredParameters = listOf(ToolParameterDescriptor("label", "What it is, e.g. \"GitHub token\"", ToolParameterType.String)),
        optionalParameters = listOf(ToolParameterDescriptor("reason", "Why you need it", ToolParameterType.String)),
    )

    override suspend fun execute(args: JsonObject): String {
        val ask = checkNotNull(ask) { "request_secret runs through the runner" }
        val (label, reason) = request(args)
        return result(label, ask(label, reason))
    }

    companion object {
        private const val MAX_LABEL = 100

        fun request(args: JsonObject): Pair<String, String?> {
            val label = args.requireString("label").trim()
            require(label.length <= MAX_LABEL) { "Keep the label under $MAX_LABEL characters" }
            return label to args.string("reason")?.trim()?.ifBlank { null }
        }

        /** What the model sees: the [reference] the user shared [label] as, or that they declined. */
        fun result(label: String, reference: String?): String {
            if (reference == null) return "The user declined to share it. Don't ask for it in chat."
            val uses = "pass it in the shell tool's `secrets` (ENV_NAME to reference) or as the exact text to type in a Browser field" +
                if (reference.startsWith("\${$SESSION_SECRET_SCHEME://")) ". It works only in this Session and can't go in an MCP server's settings."
                else ", or in an MCP server's env or headers"
            return "The user shared $label as $reference. Use the reference, never ask for the value: $uses."
        }
    }
}

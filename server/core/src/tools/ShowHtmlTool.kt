package kinuvo.server.tools

import kinuvo.server.plugin.toolSpec
import kinuvo.server.plugin.ParameterType
import kinuvo.server.plugin.ToolParameter
import kinuvo.protocol.tool.ToolNames
import kinuvo.server.plugin.CoreTool
import kotlinx.serialization.json.*

class ShowHtmlTool : CoreTool {
    override val descriptor = toolSpec(
        name = ToolNames.SHOW_HTML,
        description = "Show an interactive HTML Surface in the chat. No network access. " +
            "It sits inline in the chat without a frame: don't set a page background (the host paints the chat's " +
            "var(--color-background-primary)) and style it with the host's light/dark CSS variables, e.g. var(--color-text-primary), var(--color-text-secondary), var(--color-background-secondary) " +
            "for cards, var(--color-border-primary), var(--color-ring-primary) for the accent, var(--font-sans), var(--border-radius-md). " +
            "Use MCP Apps JSON-RPC over parent.postMessage: ui/initialize, then ui/notifications/initialized; " +
            "receive data in ui/notifications/tool-input params.arguments. Send a user message with ui/message " +
            "params {role:'user',content:{type:'text',text:'Hello'}}. The host automatically reports height. Example:\n" +
            "const send=(method,params,id)=>parent.postMessage({jsonrpc:'2.0',method,params,...(id?{id}:{})},'*');" +
            "addEventListener('message',e=>{if(e.data.id===1&&e.data.result)send('ui/notifications/initialized',{});" +
            "if(e.data.method==='ui/notifications/tool-input')document.body.textContent=JSON.stringify(e.data.params.arguments);});" +
            "send('ui/initialize',{protocolVersion:'2026-01-26',appInfo:{name:'Surface',version:'1'},appCapabilities:{}},1);",
        requiredParameters = listOf(ToolParameter("html", "HTML document or fragment", ParameterType.String)),
        optionalParameters = listOf(
            ToolParameter("title", "Surface title", ParameterType.String),
            ToolParameter("data", "JSON object delivered as tool input", ParameterType.Object(emptyList(), additionalProperties = true)),
        ),
    )

    override suspend fun execute(args: JsonObject): String {
        val html = args["html"] as? JsonPrimitive
        require(html?.isString == true && html.content.isNotBlank()) { "Give non-empty HTML" }
        require(args.toString().encodeToByteArray().size <= 512 * 1024) { "HTML Surface exceeds 512 KiB" }
        require(args["data"] == null || args["data"] is JsonObject) { "data must be an object" }
        require(args["title"] == null || (args["title"] as? JsonPrimitive)?.isString == true) { "title must be a string" }
        return "Shown to the user."
    }
}

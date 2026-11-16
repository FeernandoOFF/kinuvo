package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.mcpRoutes() {
    handle(Api.McpServers) { server.mcp.mcpServers(server.admin.agent(it.agentId)) }
    handle(Api.SaveMcp) { server.mcp.saveMcp(it) }
    handle(Api.RemoveMcp) { server.mcp.removeMcp(it) }
    handle(Api.TestMcp) { server.mcp.testMcp(it) }
    handle(Api.LoginMcp) { server.mcp.loginMcp(it) }
    handle(Api.CompleteMcpLogin) { server.mcp.completeMcpLogin(it) }
    handle(Api.LogoutMcp) { server.mcp.logoutMcp(it) }
}

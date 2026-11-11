package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.mcpRoutes() {
    handle(Api.McpServers) { server.harness.mcp.mcpServers(server.admin.agent(it.agentId)) }
    handle(Api.SaveMcp) { server.harness.mcp.saveMcp(it) }
    handle(Api.RemoveMcp) { server.harness.mcp.removeMcp(it) }
    handle(Api.TestMcp) { server.harness.mcp.testMcp(it) }
    handle(Api.LoginMcp) { server.harness.mcp.loginMcp(it) }
    handle(Api.CompleteMcpLogin) { server.harness.mcp.completeMcpLogin(it) }
    handle(Api.LogoutMcp) { server.harness.mcp.logoutMcp(it) }
}

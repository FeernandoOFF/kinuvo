# One place for tools; a catalog once there are many

Tools are set up once on the Server and then given to Agents. A shared MCP server lists the Agents that use it (`agents` in the shared `mcp.json`; absent means every Agent), and new servers are shared by default; an Agent's own `mcp.json` still adds servers only it uses. Built-in tool groups stay in each Agent's `tools:`, edited together from Settings → Tools & MCP.

The Server has its own Trust and Deny rules (`approvals.yaml` in the home folder, now `config.yaml`'s `rules`) that every Agent follows. They are merged with the Agent's own, and Deny rules are checked before Trust rules, so a Server block wins over any Agent's Always allow; an Agent can only add rules, not loosen the Server's.

Past 40 usable tools, tools from plugins that opt in (MCP) are not each sent to the model: it gets `find_tools`, `describe_tool` and `call_tool`, and Scripts reach every tool of the run. A catalog call runs the real tool, under that tool's own Risk and Approval. Built-in tools are always sent. Several accounts per service and offering the catalog to other agents over MCP are later steps.

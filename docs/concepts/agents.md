---
title: "Agents"
description: "Identity, Soul and the state each named Agent keeps."
---

An Agent is a named persona with its own Identity, Soul, Memory, Skills and model. The Server owns its data and runs it; Devices are how you talk to it.

- **Identity** gives the Agent a name, personality and avatar. Its display name comes from the first heading in `IDENTITY.md`.
- **Soul** holds its principles, tone and rules in `SOUL.md`.
- **Memory** holds what the Agent chooses to remember long-term in `MEMORY.md`.
- **User Notes** hold what it knows about you in `USER.md`; you can edit them.

Each Agent also has its own Sessions, Routines, tool settings, Risk tolerance, Trust rules and Deny rules. Knowledge is shared across Agents in your chosen note system.

Create an Agent in the app or with `kinuvo agents create ID --name NAME`. Copying another Agent copies its settings, Identity, Soul, Skills and MCP configuration; saved Trust and Deny rules are not copied.

[First Agent setup](../start/first-agent.md) walks through onboarding. [Agent configuration](../reference/agent.md) lists the file fields. [Memory and Skills](memory-and-skills.md) explains what carries between Sessions.

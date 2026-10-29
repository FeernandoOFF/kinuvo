# Kinuvo

A self-hosted personal AI assistant: the user talks to one or more named Agents that remember them across conversations.

The Server manages Agents and their Sessions; a harness executes an Agent's work. Apps are the clients.

## Language

**Agent**:
A named participant the user talks to through Sessions. Its harness owns its instructions, Memory, Skills, tools and model configuration.
_Avoid_: Profile, bot, assistant

**Harness connection**:
A reusable connection through which a Server runs Agents: Kinuvo's built-in harness, an installed external harness, or another Kinuvo Server.
_Avoid_: Model provider, exec backend

**External Agent**:
An Agent registered in Kinuvo whose execution and configuration belong to another harness or Server; its local display identity can differ from its source identity.

**Execution grant**:
Revocable access for another Server to selected Agents and the Sessions that grant creates. It gives no access to existing conversations or Server management.

**Identity**:
Who an Agent is: name, personality, avatar.

**Soul**:
The principles, tone and rules an Agent follows.

**Memory**:
What an Agent has chosen to remember long-term, curated by the Agent itself.
_Avoid_: Notes, knowledge base

**User Notes**:
What an Agent knows about the user; written by the Agent, editable by the user.
_Avoid_: User profile

**Knowledge**:
The user's own record of their world (people, projects, places), shared by every Agent and kept in the user's note system, e.g. their Obsidian vault or Anytype space, in its own types and properties. Agents can change it during requested work; background Reflection proposes changes for the user to approve. Every applied change is listed and can be undone.
_Avoid_: Memory, knowledge graph

**Note**:
One item in Knowledge, in the user's system's own terms: its type (e.g. Anytype's Human), properties and Markdown body, where links to other Notes read `[[ref|label]]`.
_Avoid_: Entity, node, record

**Conventions**:
How the user organizes their Knowledge, in their words (which types hold people, where facts go, how they link), kept per knowledge provider for Agents to follow.
_Avoid_: Ontology, mapping, rules

**Reflection**:
An Agent going over a finished conversation to curate Memory and User Notes, propose durable facts for Knowledge, and draft reusable Skills. Knowledge proposals and Skill drafts take effect only after the user's Approval.

**Reflection proposal**:
A Knowledge change or Skill draft offered by Reflection for the user to review, approve or reject. It includes the proposed content, its reason and the conversation evidence supporting it.

**Task**:
Something the user wants done, kept in their task app (e.g. Apple Reminders) and shown on the Board; an Agent can be assigned to work on it.
_Avoid_: Todo, ticket, job

**Board**:
The Server's one view of the user's Tasks with what Kinuvo adds: each Task's status, its assigned Agent and the Sessions of its runs.
_Avoid_: Kanban, backlog

**Skill**:
A named set of instructions and reference files an Agent can activate for a specific kind of task, written by the user or the Agent.
_Avoid_: Plugin, prompt, command

**Activation**:
Loading a Skill's full instructions into a Session, either by the Agent or by the user.

**Secret reference**:
A pointer into the user's password manager, written in its own URI syntax inside `${…}` in config, e.g. `${pass://Vault/Item/field}`; the Server reads the value when it's needed and never stores or shows it.
_Avoid_: Secret, credential, vault key

**Approval**:
The user's decision (allow once, always allow, or deny) on a single tool call or Reflection proposal the Agent wants to apply.
_Avoid_: Permission, consent

**Risk**:
How much a tool call could change or break: Low (reads), Medium (changes things), High (destructive or sensitive). Shell commands are classified per command by parsing them; other tools declare it.
_Avoid_: Danger level, severity

**Risk tolerance**:
An Agent's setting for which Risk runs without an Approval: strict (nothing), default (Low), relaxed (Low and Medium) or full (everything). Routines run relaxed, unless set to Auto or the Agent's is full; then they run full.
_Avoid_: Approval mode, YOLO

**Trust rule**:
A saved "always allow" for one Agent, or for every Agent when set on the Server: a tool, or a shell command prefix such as `git push` (High-risk commands only exactly), so matching calls no longer need an Approval.
_Avoid_: Allowlist, permission

**Deny rule**:
A tool or shell command pattern one Agent, or every Agent when set on the Server, may never run, whatever its Risk tolerance or Trust rules; built-in ones block e.g. deleting the home folder.
_Avoid_: Blocklist, denylist

**Tool catalog**:
The tools an Agent can find, describe and call on demand instead of each being sent to the model, used once it has many MCP tools.
_Avoid_: Tool registry, toolbox

**Script**:
A short JavaScript program an Agent runs in a sandbox with `run_code`, calling its other tools as functions under the same Approvals; only what it returns goes back to the model.
_Avoid_: Code mode, executor

**Exec backend**:
Where an Agent's shell and file tools run: the host machine, a container, or a remote machine over SSH.
_Avoid_: Sandbox, runtime, environment

**Artifact**:
A page, chart or document (HTML, SVG, Markdown, CSV, JSON or text) an Agent makes for the user, kept in `~/.kinuvo/artifacts` and shared by all Agents; only its latest content is kept. Opened from the chat or the Artifacts screen.
_Avoid_: Attachment, canvas

**Screen**:
A surface an Agent can see and control, such as a Computer display or a Browser tab.

**Browser**:
A Server-owned browser whose tabs belong to an Agent and Session. Each tab is a Screen with its own Takeover.

**Computer**:
A screen an Agent can see and control with computer use: a paired phone or computer that allowed it under This Device's tools. Its numbered elements and screenshot come from the platform's accessibility interface.
_Avoid_: Host, sandbox, VM

**Live view**:
Watching a Screen while an Agent uses it, from any Device with management access.
_Avoid_: Screen sharing, mirror, stream

**Takeover**:
The user controlling a Screen themselves, from Live view or the phone's own overlay, while Agents wait; an Agent can ask for one for sign-ins, codes, payments or permission dialogs. It ends when the user explicitly hands the Screen back; a disconnected Takeover remains suspended.
_Avoid_: Handoff, manual mode

**Workspace**:
The folders an Agent's file tools may touch and where its commands start.
_Avoid_: Project, working directory

**Server**:
The process that owns the Agents, their data and their runs; every client talks to it.
_Avoid_: Gateway, backend

**Device**:
A client (desktop app, phone, browser) paired with a Server through a pairing code.
_Avoid_: Node, client app

**Pairing link**:
A link that opens a Device ready to pair with a Server, with its address and pairing code filled in; the user still chooses to pair.

**Alias**:
This Device's own name for a remote Server, independent of the names other Devices give it.

**Revoke**:
Unpair a Device from a Server, ending its access until it pairs again.

**Room**:
Planned: a lasting conversation space defined by its members (the user and one or more Agents). Not implemented yet; Sessions belong to an Agent.
_Avoid_: Chat, channel, thread

**Session**:
One bounded conversation with an Agent (later, inside a Room); its transcript does not carry into the next Session, only Memory does.
_Avoid_: Conversation, context

**Session settings**:
A Session's own model, reasoning effort and Risk tolerance, chosen by the user for that conversation; unset ones follow the Agent's.
_Avoid_: Per-chat config, overrides

**Notification**:
What the Server sends to the user's phone through a Notifier (ntfy now) when no client in the foreground watches the Session: an Approval or form waiting, a reply, an error, a Routine's result.
_Avoid_: Push, alert

**Routine**:
A prompt an Agent runs on its own on a schedule (once, every so often, or a cron expression), each run in a new Session or continuing one Session. Each run starts from the last run's outcome, ends with the Agent reporting something new (a Notification) or nothing new (nothing is sent), and skips any tool call whose Approval goes unanswered for 30 minutes.
_Avoid_: Cron job, scheduled task, automation

**Heartbeat**:
The Agent's built-in Routine that periodically works through its checklist (HEARTBEAT.md) and stays silent when nothing needs the user.
_Avoid_: Ping, keepalive

**Voice chat**:
Talking with an Agent in a Session by voice: what the user says is sent as messages and the replies are read aloud; the transcript stays in the Session.
_Avoid_: Voice mode, call

**Dictation**:
Speaking into the composer instead of typing; nothing is sent until the user sends it.

**Compaction**:
Replacing the older part of a Session with a summary so the conversation can continue; the full transcript is kept.
_Avoid_: Truncation, pruning

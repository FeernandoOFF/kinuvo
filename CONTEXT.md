# Kinuvo

A self-hosted personal AI assistant: the user talks to one or more named Agents that remember them across conversations.

The Server manages Agents and their Sessions; a harness executes an Agent's work. Apps are the clients.

## Language

**Agent**:
A named participant the user talks to through Sessions, defined by its Identity, Soul and model configuration. The Server owns its tools, Memory and Skills and builds its instructions; a harness runs its turns.
_Avoid_: Profile, bot, assistant

**Harness connection**:
A reusable connection through which a Server runs Agents: Kinuvo's built-in harness, an installed external harness, or another Kinuvo Server.
_Avoid_: Model provider, exec backend

**Model provider**:
Where an Agent's models come from: a built-in one (`codex`, `openai`, `apple`, `local`) or a Custom provider; chosen with the model in Agent and Session settings.
_Avoid_: backend, LLM

**Custom provider**:
An OpenAI-compatible endpoint the owner lists under `providers:` in config.yaml; its ID is `custom/<key>`, so it never replaces a built-in Model provider.
_Avoid_: compatible provider, custom model

**Model tier**:
How capable a model is as an Agent: NoTools, Micro, Small, Mid or Frontier. Set by config or its provider, otherwise inferred; it picks the Model profile.

**Model profile**:
What a Model tier changes in a run: prompt style, which tool groups and how many tools, history and Compaction limits, step limit and Memory budget.

**Tool-call budget**:
The maximum number of tools an Agent executes in one turn before answering from the results it has. Internal bookkeeping does not use this budget.

**Tool scope**:
The tools one run may use, by name, group or Toolset, narrowing the Agent's before the Model profile applies. Tools it names skip the profile's group filter.

**Toolset**:
A named set of tools defined in code, optionally including other Toolsets. Agent configuration enables Toolsets by name; Approvals remain per tool.

**Core tool registry**:
The shared built-in tool metadata: name, group, default Risk, kind, repeatability and capability summary. Separate from the on-demand Tool catalog for External tools.

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
The user's decision (allow once, allow for this Session, always allow, or deny) on a single tool call or Reflection proposal the Agent wants to apply.
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

**KinuvoMCP**:
The Server's tools offered over HTTP MCP to external harnesses running Kinuvo Agents, with per-run credentials and the same Tool scope and Approvals.
_Avoid_: Kinuvo plugin, tool bridge

**Script**:
A short JavaScript program an Agent runs in a sandbox with `run_code`, calling its other tools as functions under the same Approvals; only what it returns goes back to the model.
_Avoid_: Code mode, executor

**Exec backend**:
Where an Agent's shell and file tools run: the host machine, a container, or a remote machine over SSH.
_Avoid_: Sandbox, runtime, environment

**Artifact**:
A page, chart or document (HTML, SVG, Markdown, CSV, JSON, text or a Design) an Agent makes for the user, kept in `~/.kinuvo/artifacts` and shared by all Agents; only its latest content is kept. Opened from the chat or the Artifacts screen.
_Avoid_: Attachment, canvas

**Design**:
An Artifact of type `design`: a row of Artboards on a pan and zoom canvas, kept as JSON. Artboards can be opened full screen or presented one by one.

**Artboard**:
One page of a Design: a self-contained HTML document shown at a fixed width and height.

**Surface**:
An interactive layout or form an Agent shows in a Session, with controls the user can fill in and actions they can send back to the Agent.

**HTML Surface**:
A Surface made of the Agent's own HTML, shown in a sandboxed web view with no network access.

**MCP App**:
An MCP server's own UI for one of its tools, shown in the chat like an HTML Surface after the tool runs; it can call that server's tools.

**Screen**:
A surface an Agent can see and control, such as a Computer display or a Browser tab.

**Browser**:
A Server-owned browser with a unique profile and shared tabs for each Agent, accessible across its Sessions. Each tab is a Screen with its own Takeover.

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

**Team**:
A lasting conversation space with the user and one or more Agents as members; each member has its own Session and the Team keeps the shared log.
_Avoid_: Room, Chat, channel, group

**Sub-agent**:
An Agent run in a Hidden Session started by another Agent's Session, which waits for or is notified of its result.
_Avoid_: Worker, child agent

**Hidden Session**:
A private Session used by a Sub-agent, kept on-device and out of normal Session listings. Sharing its result with a parent Agent requires the user's Approval.

**Activation budget**:
The shared limit on tool executions by one root run and all its Sub-agents. Exhausting it stops the whole tree.

**Handoff**:
An Agent passing the floor of a Team to another member with a brief. Not a Takeover, which is the user controlling a Screen.
_Avoid_: Takeover, transfer

**Session**:
One bounded conversation with an Agent, including one per member of a Team; its transcript does not carry into the next Session, only Memory does.
_Avoid_: Conversation, context

**Session settings**:
A Session's own model, reasoning effort and Risk tolerance, chosen by the user for that conversation; unset ones follow the Agent's.
_Avoid_: Per-chat config, overrides

**Notification**:
What the Server sends to the user's phone, through a Notifier such as ntfy or as a push to paired Devices, when no client in the foreground watches the Session: an Approval or form waiting, a reply, an error, a Routine's result.
_Avoid_: Push, alert

**Push relay**:
The hosted service that forwards encrypted Notifications from any Server to Apple and Google push services; it can't read them.
_Avoid_: Push server, proxy

**Routine**:
A prompt an Agent runs on its own on a schedule (once, every so often, or a cron expression), each run in a new Session or continuing one Session. Each run starts from the last run's outcome, ends with the Agent reporting something new (a Notification) or nothing new (nothing is sent), and skips any tool call whose Approval goes unanswered for 30 minutes. Planned: Routines become scheduled Workflows.
_Avoid_: Cron job, scheduled task, automation

**Workflow**:
A graph of steps the Server runs durably, such as Agent turns and Approvals, started by a trigger such as a schedule. A Routine is a scheduled Workflow.
_Avoid_: Pipeline, automation, flow

**Heartbeat**:
The Agent's built-in Routine that periodically works through its checklist (HEARTBEAT.md) and stays silent when nothing needs the user.
_Avoid_: Ping, keepalive

**Voice chat**:
Talking with an Agent in a Session by voice, through Live voice or transcription and synthesis; the transcript stays in the Session.
_Avoid_: Voice mode, call (use Voice call for one Live voice conversation)

**Live voice**:
Voice chat through a speech-to-speech model that hands tasks to the Session's Agent.
_Avoid_: Realtime mode

**Voice call**:
One Live voice conversation, recorded in the Session as a single collapsible transcript block when it ends; delegated tasks remain separate messages.

**Dictation**:
Speaking into the composer instead of typing; nothing is sent until the user sends it.

**Compaction**:
Replacing the older part of a Session with a summary so the conversation can continue; the full transcript is kept.
_Avoid_: Truncation, pruning

**Result limits**:
The Model profile's item counts and per-tool-result token budget. Earlier turns receive a smaller share; only the model prompt is shortened, while the saved Session keeps the full result.

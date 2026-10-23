# Agent Rooms

This experiment keeps group members as peers. Direct Sessions, Routines and
Reflection keep their existing strategies. There is no coordinator or mandatory
synthesis. Direct Sessions keep their IDs and histories; each group Room Session
has an independent public timeline and member settings. Deleting an Agent leaves
its public messages intact.

A user message without structured recipients offers each available member one
turn, sequentially in membership order. Structured recipients scope the initial
participants. Prose `@mentions` never schedule work. Explicit peer requests may
bring another available member into the root.

## Turns and requests

A Room-specific Koog strategy uses the shared ToolLoop, ToolExecutor, Approvals,
secret redaction, Session history and compaction. Members choose a structured
outcome through Low-risk Room tools, still subject to strict Risk tolerance and
Deny rules:

- `public_reply(text)` publishes one intentional answer and finishes.
- `pass_turn()` finishes silently and declines incoming requests.
- `address_member(agentId, text, requestId?, waitForResult?)` publishes one work
  request and finishes. Its text may include the sender's own contribution.
- `return_result(requestIds, text)` publishes one result fulfilling the named
  open requests together and finishes. The IDs must cover the member's inbox.

All tools accept an optional current `rootId`; another root is rejected. Request
IDs identify work within that root. Repeating the same ID, owner, target, text and
waiting choice neither publishes nor delivers it again; conflicting reuse fails.
Results must belong to the current root, target and open state. A normal public
reply cannot silently abandon incoming requests: return their result or pass.

There is one pending inbox per Agent per root. Explicit requests merge into a
member's already scheduled turn, including an original user-question turn.
Distinct requests remain distinct. The model sees member names and IDs, the
source user request, active/pending/completed/waiting participation, and the request
ledger. A result creates no acknowledgement obligation. Only an owner that set
`waitForResult: true` receives a bounded continuation after a result or decline.
A member delegating its own incoming work must wait and then return its result.
Tools never wait for a child run inside a tool call.

Successful outcomes go straight to graph finish: no extra model request, and no
later tools in the same batch execute. Unexecuted calls receive error results
saying they did not run. Denied or invalid outcome tools remain tool errors and
the member can recover. Ordinary assistant text, reasoning and tool commentary
stay private; a bare assistant response is not an implicit public answer. Only
accepted explicit output is published once, after successful execution, with
participant, recipients, reply and root attribution. Room progress suppresses
private text and reasoning while preserving original Approval/form routing and
private Session/Computer provenance. `ask_user` uses the existing form broker and
continues after the answer; there is no second question system.
Private Room run completions and errors send no reply Notifications. Approval and
question Notifications still use their original private Session and broker IDs.

## Private Sessions and limits

Each Agent has one private execution Session per group Room Session. These never
adopt Direct Sessions or another member's history. Private instructions, Soul,
Memory, tools and results stay isolated. Reflection is disabled for these
Sessions. A saved cursor supplies only new public entries: the newest 20 since
that cursor, capped at 4,000 characters each, with explicit omission/truncation
markers. The current source request and bounded request ledger remain available
for followups. Shared Session compaction handles growing private history. The
complete public timeline remains saved.

Defaults per user root are 12 dispatches, 3 turns per member, followup depth 3,
24 public member messages, a 2-minute deadline and 100,000 measured input/output
tokens. Every append also enforces the Session's 1,000-message public-log cap.
Limits are configurable internally for tests. Exact use of a budget is successful
when no further work is rejected. Cycles and floods cannot bypass these limits;
normal replies and results settle as soon as there are no runnable inboxes.
Accepting a request reserves dispatch, member-turn and depth capacity for its
pending target and unresolved waiting continuations together. Waits from one owner
to the same target share a continuation; already resolved waits reserve nothing
beyond their pending inbox. An owner with unrelated pending work still needs a
later turn reserved for an open wait. Unserviceable requests are rejected before
publication or delivery.

Token accounting uses existing actual request measurements, including compaction,
and checks before every model request (including each compaction chunk) and
dispatch. Providers may omit usage; missing counts are not estimates. A completed
provider request may cross the token budget before its usage is known. There is no
monetary-cost cap or invented pricing. Dispatch, depth, message and wall-clock
limits remain independent guards.

Stop cancels preparation, active execution, Approval/form waits and queued or
awaited work, releases Runs reservations and prevents late publication. Private
Session identity, cursor and an interruption marker survive reload. Queues and
request ledgers are deliberately in process: interrupted work is marked as such,
never silently resumed or replayed. A new user message starts a new root. Legacy
Room Session JSON defaults to no private execution mappings; no migration deletes
history. Old coordinator fields remain readable but do not control scheduling.

## Settings and access

Execution overrides belong to a Direct Session or one member of a group Session.
Unset values follow that Agent's defaults. Preparation snapshots the next run's
configuration; changes during a run apply at its next run boundary. Reused private
Sessions replace their settings on every preparation, including cleared overrides.
Exec backend selection concerns shell/file tools independently of a paired
Computer's screen.

Execution changes and Computer watch/control require Server management access.
Ordinary paired Devices can create and title Room Sessions. Execution controls
require a Server reporting effective Session settings; older Servers show an
update message. Computer previews require actual Agent and Session attribution.
Artifact Session filters use the last saver's attribution, never a guess based on
the selected Agent or Server alias.

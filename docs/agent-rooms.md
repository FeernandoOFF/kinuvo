# Agent Rooms

Direct Sessions keep their existing IDs and histories. A group Room contains
independent titled Sessions, each with its own public history and member settings.
Deleting an Agent does not delete that public history.

Members participate as peers. A message without structured recipients offers each
available member one sequential turn in membership order. Structured recipients
select only those members. Prose `@mentions` do not authorize dispatch. During a
turn, `address_member` can queue a public followup; `pass_turn` lets a member stay
silent. There is a finite dispatch budget and no coordinator or synthesis model
loop. Stop cancels the active private run and prevents queued turns from starting.
Older coordinator fields remain readable but do not control participation.

Each dispatch uses a fresh private Agent Session. The Agent keeps its own
instructions, Memory, tools and Approval rules. Only public replies and explicitly
addressed public requests enter the Room history. Approvals and questions retain
the private run's IDs and brokers.
The shared context contains the latest 20 public messages, capped at 4,000
characters each; the complete saved public history remains available to the user.

Execution overrides belong to the direct Session or one member of a group
Session. Unset values follow that Agent's defaults. They do not change another
Session or the Agent's defaults. A run snapshots its effective configuration at
preparation; changes made while it runs apply at the next run boundary. Exec
backend selection concerns shell and file tools, independently of a paired
Computer's screen.

Execution changes and Computer watch/control require Server management access.
Ordinary paired Devices can create and title Room Sessions. Execution controls
require a Server that reports effective Session settings; older Servers show an
update message. Computer previews require the Server's actual Agent and Session
attribution. Artifact Session filters use the last saver's attribution, never a
guess based on the selected Agent or Server alias.

Room scheduling is in process. Saved history and settings survive reload, but
queued peer turns are not a durable restart/replay queue. An interrupted Server
run must be continued with a new user message.

---
title: "Queued and steering messages"
description: "Sessions accept queued turns and safe-point steering with explicit delivery capabilities."
---

# Queued and steering messages

`chat.send.delivery` defaults to `Turn`. `SessionCapabilities.queue` and `.steer` default to false so older peers keep their behavior. Built-in Sessions advertise both; external harnesses advertise neither and reject both choices.

Queue accepts messages in FIFO order, including attachments. Each receives its own run ID, also used by `chat.cancelQueued`. Completion and failure advance the queue. Stop pauses waiting messages; an explicit Turn, or Steer while idle, resumes execution. Messages queued while paused stay waiting. Cancellation is idempotent and affects only messages not yet started.

Steer accepts messages and attachments into an active chat run. The built-in loop takes them after tools finish and before loading the next request's history. Each is saved as a normal user message. Skill commands cannot steer. Runs started outside chat cannot take steering input and reject it clearly. Steering that misses the final request becomes a queued turn; Stop keeps it paused too.

Queue snapshots travel in live Session state and `QUEUE_CHANGED` events, including catch-up after reconnect. Waiting messages belong to the running Server and are lost on restart. Persistent queues and external harness delivery are future work.

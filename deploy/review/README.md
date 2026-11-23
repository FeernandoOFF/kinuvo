# Store review Server

This deployment runs the real Server with fictional Maya Chen and Lisbon travel
data. Reviewers get restricted review access. Everyone shares this demo; do not enter
real personal information. It is separate from the owner's Server.

The seed includes Memory and User Notes, five Tasks in several Board states,
three linked Knowledge notes, three Sessions and two disabled Routines. Routines
are disabled so the demo does not spend credits while idle.

Only `memory`, `tasks`, `knowledge`, `web_search` and `ask_user` can run. In the
current protocol, `memory` remembers facts and `tasks` creates Tasks. Shell,
MCP, email, messaging, notifications, contact writes, browser/computer control,
device tools, arbitrary web fetches, agent management and remote harness tools
are excluded. Task and Knowledge writes stay in the local demo. Search uses a
private SearXNG sidecar, without exposing a public search endpoint.

The existing mobile client requires the management flag to display Routines.
Review pairing supplies that flag, while a separate Server RPC allowlist limits
access to local demo data and inspection. Provider credentials, pairing
administration, Agent configuration, external harnesses and integration settings
cannot be changed. Seeded Routines can be inspected but not enabled or edited.

## Build and deploy

Package the Server without the desktop or web app:

```sh
mise run kotlin -- package -m cli -p jvm -f executable-jar
mkdir -p dist
cp build/tasks/_cli_executableJarJvm/cli-jvm-executable.jar dist/kinuvo.jar
docker build -f deploy/review/Dockerfile -t kinuvo-review:local .
```

The jar is portable; build the container on the target architecture. This image
does not bundle the web client or local voice workers. Mobile clients use `/ws`.

Create a separate Coolify Docker Compose service from `compose.yaml`. Set its
review service domain to an HTTPS hostname with destination port 8787. Leave
search without a domain, and publish no host ports. Keep the service network
separate from other apps. Set:

- `REVIEW_PUBLIC_URL`: the HTTPS origin, without `:8787`.
- `REVIEW_PAIRING_CODE`: a random 32-byte hex value (`openssl rand -hex 32`).
- `REVIEW_EXPIRES_AT`: an explicit UTC timestamp several weeks ahead.
- `SEARCH_SECRET`: another random 32-byte hex value.

Review mode requires an empty home or one already marked as a review home. It
does not initialize an existing personal home. The container fixes the home to
`/data/state` in its dedicated volume. Device credentials are stored separately
at `/data/review-devices.json`; no personal home or Docker socket is mounted.

## Model key and cap

The model is OpenRouter's `openai/gpt-4.1-mini`. Install a dedicated OpenRouter key
with a **USD 1 daily limit**, resetting daily, into `/data/openrouter-key` in this
service's volume, owned by UID 10001 with mode 0600. Use a secure secret transfer;
do not put it in the compose file, repository, command arguments or logs. Restart
only this service after installing it.

The supervisor checks the key's limit through OpenRouter's `/api/v1/key` before
giving it to Java. It refuses an unbounded key or a limit above USD 1/day. Without
a key, pairing and seeded data work, but live chat is unavailable. The spend
limit is enforced by OpenRouter and survives container restarts and demo resets.
The key must be used only by this deployment.

## Limits and reset

The Server closes connections above 120 incoming frames per minute per Device,
shared across connections and token rotation, or 600 per minute per source IP.
Existing limits also allow at most ten authentication attempts per source IP per
minute, sixty globally, eight simultaneous connections per IP and sixty-four
globally. The reverse proxy must append the real client address; never publish
the Server port directly.

At 03:00 UTC each day the supervisor stops Java, removes only the marked demo
state, then restarts and seeds it again. A restart after a missed reset performs
that reset before starting. Paired Devices and the Server identity survive.
The fixed pairing code and its Device tokens expire at `REVIEW_EXPIRES_AT`.

Update the expiry and rotate the pairing code in Coolify for a new review cycle.
Changing the code does not revoke already paired Devices; revoke those separately
or replace the review-only Device credential file while this service is stopped.

## Reviewer notes

After verifying live chat with the installed capped key, replace the placeholders:

> To review Kinuvo, choose “Connect to a Server” and enter **[HTTPS URL]** with
> pairing code **[CODE]**. This hosted demo requires no personal Server or Apple
> Intelligence. It includes fictional travel plans, Memory, Tasks, Knowledge,
> Routines and example Sessions. You can chat, search the web, remember a
> preference and create a Task. External messaging and other outside-world
> actions are disabled. Demo edits reset daily at 03:00 UTC; pairing stays valid
> until **[EXPIRY]**.

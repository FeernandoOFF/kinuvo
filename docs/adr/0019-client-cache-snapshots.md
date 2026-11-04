# Client cache as per-Server snapshots

Apps keep what they last saw of each remote Server so they open with it before connecting and stay readable while they can't reach it. The cache lives in `shared/app` (`ClientCache`), so every UI, including future native ones, gets it from the shared Repositories rather than doing its own caching.

A Server's cache is one `Snapshot`: Agents, capabilities, Session lists, the cached Sessions' last 100 entries, Routines and the Board, saved as JSON with KStore after the shown state settles for a second while online. Files go in `client-cache/` under the desktop home (`KINUVO_HOME`), and in the app's cache folder on Android and iOS; the web uses localStorage on a best-effort basis and drops transcripts when storage is full. A snapshot from another protocol version, or one that fails to read, is discarded. The embedded Server uses `ClientCache.None`. Encryption relies on what the platform provides.

Opening a cached Server shows it right away as `Connection.Ready` with `online = false` and `syncedAt`, and the client keeps retrying until it connects. Only bad credentials or a protocol mismatch stop it, and either one also drops that Server's snapshot. Reconnecting catches the cached Sessions up from their last seq instead of reloading them. Offline, the chat says when it last synced; writes fail as they do during any disconnect.

Settings can clear every snapshot, or reset the Device: forget all Servers, their credentials, preferences and cached data, as on a fresh install. Servers aren't told about a reset.

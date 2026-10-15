# Attachments

The composer’s + menu offers Photos and Files on iOS, Android, and desktop. Selected images have previews; files show their name and size. Remove a selection before sending, or send attachments without text. Failed sends retain the draft and selections. Successfully uploaded files are reused when retrying a failed send.

Supported inputs are JPEG, PNG, GIF, WebP, PDF, DOCX, XLSX, PPTX, CSV, JSON, Markdown, text, and common source-code files. Each message accepts up to four files, at most 10 MB each and 20 MB combined. UTF-8 text and code files are limited to 256 KB.

The iOS Photos picker converts photos to JPEG, resizes them to at most 2048 pixels, and drops photo metadata. The Server similarly resizes JPEG, PNG, and GIF inputs and saves a fresh JPEG without metadata; GIF inputs use the first frame. WebP and other files retain their uploaded bytes. The transcript’s download action saves the stored version through the platform’s file dialog or iOS share sheet.

Paired Devices upload through `attachments.upload`; `chat.send` carries attachment IDs. The Server resolves those IDs before starting a run. Transcripts contain attachment metadata, while bytes and metadata live separately under `~/.kinuvo/attachments`. Old text-only transcripts remain readable. Attachments remain in Server storage; there is no attachment garbage collection yet.

The Codex provider sends images as `input_image` and files as `input_file`, replaying the stored bytes while their user turn remains in active history. Compaction summarizes older attachment turns with the rest of the transcript; the full transcript and downloads remain available. File interpretation depends on the selected model and provider.

Tools can show the model files too, e.g. the knowledge tool's `file` action for an image or PDF in an Anytype note. They're stored like uploads and kept on the `tool_result` entry; Codex follows the tool's output with a user message carrying them, since function outputs are text only.

Validation:

- `./kotlin test -m core -m server -m codex -m ui -p jvm`
- `KINUVO_LIVE_ATTACHMENTS=1 ./kotlin test -m codex -p jvm --include-classes '*CodexAttachmentsLiveTest'` verifies synthetic image, PDF, and text inputs with the current Codex login.
- `./kotlin build -m ui -p android`
- `./kotlin build -m ios -p iosSimulatorArm64`

Phase 8 closure verification (2026-10-06): the core, Server, Codex and UI JVM suites passed, as did CLI, protocol, Firecrawl and ntfy tests and the full `./kotlin build`. The opt-in live check verified synthetic PNG, PDF and text inputs through the current Codex login. Native picker behavior was also manually verified by the user. DOCX/XLSX/PPTX interpretation remains model-dependent and was not covered by that live check.

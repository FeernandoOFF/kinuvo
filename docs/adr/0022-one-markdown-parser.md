# One Markdown parser

JetBrains Markdown 0.7.17 with its GFM flavour is the parser for display and editing. Compose displays Markdown through multiplatform-markdown-renderer, with Kinuvo typography, code blocks and the library’s default tables.

The Composer edits Markdown source, showing inline formatting while keeping syntax markers visible and dimmed. Shared spans and editing operations live in `shared/app/src/markdown`. `MarkdownBuffer` is the seam between these operations and Compose’s text field, and can also serve a future native text view.

Future work: native SwiftUI rendering mirroring MarkdownComponents.

# One Markdown parser

JetBrains Markdown 0.7.17 with its GFM flavour is the parser for display and editing. Compose displays Markdown through multiplatform-markdown-renderer, with Kinuvo typography, code blocks and the library’s default tables.

The Composer edits Markdown source, showing inline formatting while keeping syntax markers visible and dimmed. Shared spans and editing operations live in `shared/app/src/markdown`. `MarkdownBuffer` is the seam between these operations and Compose’s text field, and can also serve a future native text view.

`markdownDocument` projects the same parser AST into immutable `MarkdownDocument` values in `shared/app/src/markdown`, the input for the future SwiftUI renderer. Blocks and inlines are sealed classes with typed child lists; list items carry optional checked state and tables carry column alignment. Compose keeps using mikepenz directly on the AST.

The iOS umbrella uses plain Objective-C export, without SKIE. Swift receives classes and typed arrays, not Swift enums: subtype switches require a fallback case (Kotlin switches remain exhaustive). A native Swift enum adapter or a sealed-type export tool would be needed for compiler-enforced exhaustiveness in Swift. Native rendering itself remains future work.

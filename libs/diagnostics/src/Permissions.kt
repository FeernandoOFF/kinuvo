package kinuvo.diagnostics

import kotlinx.io.files.Path

/** Keeps logs and crash files from other users of a shared computer; phones are already sandboxed. */
internal expect fun restrictToOwner(path: Path)

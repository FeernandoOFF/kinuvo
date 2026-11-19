package kinuvo.plugins.exec

internal actual fun killTree(process: Process) {
    process.descendants().forEach { it.destroyForcibly() }
    process.destroyForcibly()
}

package kinuvo.plugins.exec

internal actual fun killTree(process: Process) {
    process.destroyForcibly()
}

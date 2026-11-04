package kinuvo.ui.e2e

/** Shared steps mirroring `.maestro/shared/`. */

internal suspend fun AppTest.openPairing(code: String) {
    await("Your agents, on your own Server.")
    if (visible("Enter address and code")) tap("Enter address and code")
    if (!visible("Code from the Server")) tap("Connect to a Server")
    tap("Code from the Server")
    type(code)
    scrollTo("Connect")
    tap("Connect")
}

internal suspend fun AppTest.pair() {
    openPairing("123456")
    await("Start a Session with E2E Agent")
}

internal suspend fun AppTest.startChat() {
    pair()
    tap("Message")
    type("Hello from Maestro")
    tap("Send")
    await("Hello from WireMock")
}

internal suspend fun AppTest.openHome() {
    tap("Agents and sessions")
    tap("Home")
}

internal suspend fun AppTest.returnToChat() {
    tap("Home and agents")
    tapAbove("E2E Agent", "New agent")
    tap("E2E chat")
}

internal fun sessionEvent(seq: Int, event: String) =
    """{"type":"event","event":"session.update","payload":{"agentId":"e2e","sessionId":"e2e-session","change":{"type":"event","event":$event}},"seq":$seq}"""

internal fun sessionEntry(seq: Int, entrySeq: Int, entry: String) =
    """{"type":"event","event":"session.update","payload":{"agentId":"e2e","sessionId":"e2e-session","change":{"type":"entry","seq":$entrySeq,"entry":$entry}},"seq":$seq}"""


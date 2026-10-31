package kinuvo.harness.browser

/** A capture belongs to one control generation; changing control never preserves Agent refs. */
class BrowserObservation {
    var revision: String = ""
        private set
    private var generation: Long = -1
    fun capture(revision: String, generation: Long) { this.revision = revision; this.generation = generation }
    fun invalidate() { revision = ""; generation = -1 }
    fun requireGeneration(current: Long) { check(generation == current && revision.isNotEmpty()) { "Screen control changed; capture again" } }
    fun requireReference(revision: String?, current: Long) {
        requireGeneration(current)
        require(this.revision == revision) { "Stale browser reference; capture again" }
    }
}

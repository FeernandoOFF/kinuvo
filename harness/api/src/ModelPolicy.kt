package kinuvo.harness

/** How a provider shapes the runs of its models; what the Server asks of it without building a model. */
interface ModelPolicy {
    val id: String

    /** [model]'s [base] profile as this provider runs it, e.g. with a lower tool cap. */
    fun profile(model: String, base: ModelProfile): ModelProfile = base

    /** Whether [model] can be offered [tool], beyond what its profile allows. */
    fun accepts(model: String, tool: ToolSpec): Boolean = true
}

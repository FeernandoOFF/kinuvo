package kinuvo.harness.core

import kinuvo.protocol.ExecutionLocation
import kinuvo.protocol.ModelInfo
import kinuvo.protocol.ModelTier

/**
 * The tier of [info]: the one it declares, else from what it can do. Models that can't call tools are [ModelTier.NoTools];
 * a context of 4096 tokens or less is [ModelTier.Micro]; on-device models and those of 8B parameters or fewer are [ModelTier.Small];
 * up to 70B parameters, or named like a small cloud model, [ModelTier.Mid]; the rest, including cloud models of unknown size, [ModelTier.Frontier].
 */
fun inferTier(info: ModelInfo): ModelTier {
    info.tier?.let { return it }
    if (info.tools == false) return ModelTier.NoTools
    if (info.context?.let { it <= MICRO_CONTEXT } == true) return ModelTier.Micro
    val billions = parameterBillions(info.id)
    if (info.location != ExecutionLocation.External || billions != null && billions <= 8.0) return ModelTier.Small
    if (billions != null && billions <= 70.0 || MID_NAMES.containsMatchIn(info.id.lowercase())) return ModelTier.Mid
    return ModelTier.Frontier
}

private const val MICRO_CONTEXT = 4096
private val MID_NAMES = Regex("(?<![a-z])(mini|flash|nano|haiku|small|lite)(?![a-z])")
private val PARAMETERS = Regex("""(?<![a-z0-9.])(\d+(?:\.\d+)?)b(?![a-z])""")
private val EXPERTS = Regex("""(?<![a-z0-9.])(\d+)x(\d+(?:\.\d+)?)b(?![a-z])""")
private val EFFECTIVE = Regex("""(?<![a-z0-9.])e(\d+)b(?![a-z])""")

/** Parameter count in billions from a model ID such as `qwen2.5-7b`, `llama3.1:8b` or `gemma-4-e4b` (effective parameters) or `mixtral-8x7b`; null when it names none. */
internal fun parameterBillions(id: String): Double? {
    val name = id.lowercase()
    EXPERTS.find(name)?.let { return it.groupValues[1].toDouble() * it.groupValues[2].toDouble() }
    EFFECTIVE.find(name)?.let { return it.groupValues[1].toDouble() }
    return PARAMETERS.findAll(name).mapNotNull { it.groupValues[1].toDoubleOrNull() }.lastOrNull()
}

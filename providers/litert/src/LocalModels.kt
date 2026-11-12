package kinuvo.providers.litert

import kinuvo.models.ModelDownload
import kinuvo.protocol.voice.ModelFile
import kinuvo.protocol.model.ModelTier

/** A model the Server can download and run with LiteRT-LM; [memory] is the device RAM it needs, [tier] how capable it is and [cap] how many tools it's offered when not its tier's usual. */
internal class LocalModel(val id: String, val name: String, repo: String, revision: String, file: String, bytes: Long, sha256: String,
    val memory: Long, val context: Int, val tier: ModelTier, val cap: Int? = null) {
    val source = "https://huggingface.co/$repo"
    val license = "Apache-2.0"
    val download = ModelDownload(id, listOf(ModelFile(file, "$source/resolve/$revision/$file", bytes, sha256)))
    val file get() = download.files.single().name
}

private const val GB = 1_000_000_000L

internal val localModels = listOf(
    LocalModel("gemma-4-e2b", "Gemma 4 E2B", "litert-community/gemma-4-E2B-it-litert-lm", "c658da437244f072bf2f1cabc916b60d66d97663",
        "gemma-4-E2B-it.litertlm", 2588147712, "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", memory = 6 * GB, context = 8192, tier = ModelTier.Small),
    LocalModel("gemma-4-e4b", "Gemma 4 E4B", "litert-community/gemma-4-E4B-it-litert-lm", "e6a3892a8c22cf87ff99583996149cf3cb32452b",
        "gemma-4-E4B-it.litertlm", 3659530240, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", memory = 10 * GB, context = 8192, tier = ModelTier.Small, cap = 10),
    LocalModel("qwen3-0.6b", "Qwen3 0.6B", "litert-community/Qwen3-0.6B", "722a0a54c1301a4b07a9856a9590d56362f0969b",
        "Qwen3-0.6B.litertlm", 613722264, "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108", memory = 3 * GB, context = 4096, tier = ModelTier.Micro),
)

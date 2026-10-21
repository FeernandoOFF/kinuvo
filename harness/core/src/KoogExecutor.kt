package kinuvo.harness.core

import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.retry.DefaultRetryAfterExtractor
import ai.koog.prompt.executor.clients.retry.RetryConfig
import ai.koog.prompt.executor.clients.retry.RetryablePattern
import ai.koog.prompt.executor.clients.retry.RetryingLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import kotlin.time.Duration.Companion.seconds

/** One executor per configured provider, including separate OpenAI API and Codex accounts. */
fun koogExecutor(client: LLMClient, retry: RetryConfig = modelRetryConfig): PromptExecutor =
    MultiLLMPromptExecutor(RetryingLLMClient(client, retry))

val modelRetryConfig = RetryConfig.PRODUCTION.copy(
    retryablePatterns = listOf(RetryablePattern.Custom { message ->
        !PERMANENT_FAILURE.containsMatchIn(message) && RetryConfig.DEFAULT_PATTERNS.any { it.matches(message) }
    }),
    retryAfterExtractor = { message ->
        DefaultRetryAfterExtractor.extract(message)?.coerceIn(1.seconds, 20.seconds)
    },
)

private val PERMANENT_FAILURE = Regex("\\b(401|403)\\b|insufficient_quota|quota.{0,30}(exceeded|exhausted)|usage[_ -]limit|billing|invalid.{0,10}api.{0,5}key", RegexOption.IGNORE_CASE)

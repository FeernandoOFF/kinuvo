package kinuvo.server

import kinuvo.protocol.MAX_CONCURRENT_REQUESTS

data class ConnectionSecurity(
    val authenticationTimeoutMillis: Long = 10_000,
    val validationIntervalMillis: Long = 30_000,
    val concurrentRequests: Int = MAX_CONCURRENT_REQUESTS,
    val messagesPerMinute: Int = 600,
    val liveVoiceInputsPerMinute: Int = 6000,
    val screenAcksPerMinute: Int = 15_000,
)

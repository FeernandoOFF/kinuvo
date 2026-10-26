package kinuvo.harness.koog

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.java.Java

internal actual fun providerEngine(): HttpClientEngine = Java.create()

package kinuvo.harness.koog

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

internal actual fun providerEngine(): HttpClientEngine = Darwin.create()

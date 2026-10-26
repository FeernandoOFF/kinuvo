package kinuvo.harness.koog

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

internal actual fun providerEngine(): HttpClientEngine = OkHttp.create()

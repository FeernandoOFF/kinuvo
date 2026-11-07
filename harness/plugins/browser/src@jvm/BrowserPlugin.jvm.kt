package kinuvo.harness.browser

import java.io.File
import kinuvo.harness.core.Secrets
import kinuvo.harness.core.plugin.BrowserService

fun BrowserPlugin(home: File, browser: BrowserService = BrowserRuntime(home), secrets: Secrets = Secrets()): BrowserPlugin = BrowserPlugin(browser, secrets)

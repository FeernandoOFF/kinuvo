package kinuvo.plugins.browser

import java.io.File
import kinuvo.server.plugin.BrowserService
import kinuvo.server.plugin.SecretResolver

fun BrowserPlugin(home: File, browser: BrowserService = BrowserRuntime(home), secrets: SecretResolver = SecretResolver.None): BrowserPlugin = BrowserPlugin(browser, secrets)

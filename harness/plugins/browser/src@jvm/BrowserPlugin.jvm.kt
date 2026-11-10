package kinuvo.harness.browser

import java.io.File
import kinuvo.server.plugin.BrowserService

fun BrowserPlugin(home: File, browser: BrowserService = BrowserRuntime(home)): BrowserPlugin = BrowserPlugin(browser)

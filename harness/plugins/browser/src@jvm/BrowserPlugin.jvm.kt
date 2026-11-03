package kinuvo.harness.browser

import java.io.File
import kinuvo.harness.core.BrowserService

fun BrowserPlugin(home: File, browser: BrowserService = BrowserRuntime(home)): BrowserPlugin = BrowserPlugin(browser)

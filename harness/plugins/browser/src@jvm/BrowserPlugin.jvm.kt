package kinuvo.harness.browser

import java.io.File

fun BrowserPlugin(home: File, browser: BrowserRuntime = BrowserRuntime(home)): BrowserPlugin = BrowserPlugin(browser)

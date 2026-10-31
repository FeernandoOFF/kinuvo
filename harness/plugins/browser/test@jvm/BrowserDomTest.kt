package kinuvo.harness.browser

import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import kinuvo.protocol.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*
import java.nio.file.Files

class BrowserDomTest {
    @Test fun formPropertiesInvalidateReferencesAndTypingRespectsEditability() = runBlocking {
        val home = Files.createTempDirectory("kinuvo-dom-").toFile()
        val runtime = BrowserRuntime(home)
        try {
            runtime.command(BrowserCommand(BrowserOwner("fixture"), BrowserAction.Open, text = "about:blank"))
            val env = System.getenv().toMutableMap().apply { put("PLAYWRIGHT_BROWSERS_PATH", home.resolve("runtime/playwright-1.64.0").path) }
            Playwright.create(Playwright.CreateOptions().setEnv(env)).use { playwright ->
                playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true).setChromiumSandbox(true)).use { browser ->
                    val page = browser.newPage()
                    page.setContent("""<input id="message"><input id="check" type="checkbox"><input id="readonly" readonly value="original">
                        <div id="editor" contenteditable="true">original</div><button id="submit">Submit</button>""")
                    fun capture() { page.evaluate(BrowserDom.capture("revision")) }
                    fun ref(id: String) = page.evaluate("() => Object.keys(window.__kinuvoBrowser.refs).find(k => window.__kinuvoBrowser.refs[k].id === '$id')").toString()
                    capture()
                    val submit = ref("submit")
                    page.evaluate("() => document.querySelector('#message').value = 'changed'")
                    assertContains(assertFails { page.evaluate(BrowserDom.action("revision", submit)) }.message.orEmpty(), "Form state changed")
                    capture()
                    page.evaluate("() => document.querySelector('#check').checked = true")
                    assertContains(assertFails { page.evaluate(BrowserDom.action("revision", ref("message"), "overwrite")) }.message.orEmpty(), "Form state changed")
                    capture()
                    assertContains(assertFails { page.evaluate(BrowserDom.action("revision", ref("readonly"), "overwrite")) }.message.orEmpty(), "not editable")
                    assertEquals("original", page.locator("#readonly").inputValue())
                    capture()
                    page.evaluate(BrowserDom.action("revision", ref("editor"), "Edited text"))
                    assertEquals("Edited text", page.locator("#editor").innerText())
                    capture()
                    page.evaluate(BrowserDom.action("revision", ref("message"), "Ordinary text"))
                    assertEquals("Ordinary text", page.locator("#message").inputValue())
                }
            }
        } finally { runtime.close() }
    }
}

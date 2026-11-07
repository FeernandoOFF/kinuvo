package kinuvo.ui

import kinuvo.ui.artifacts.editedHtml
import kotlin.test.Test
import kotlin.test.assertEquals

class EditedHtmlTest {
    @Test
    fun readsThePageFromTheCallbackArguments() {
        assertEquals("<p>\"a\"</p>", editedHtml("[\"<p>\\\"a\\\"</p>\"]"))
        assertEquals("<p>a</p>", editedHtml("<p>a</p>"))
    }
}

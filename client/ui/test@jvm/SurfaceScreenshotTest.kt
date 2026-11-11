package kinuvo.ui

import com.composables.icons.lucide.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.CompositionLocalProvider
import dev.ynagai.a2ui.core.surface.JsonPointer
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.composables.ui.theme.backgroundColor
import dev.ynagai.a2ui.compose.*
import dev.ynagai.a2ui.core.protocol.*
import dev.ynagai.a2ui.core.validation.CatalogValidator
import kinuvo.ui.design.*
import kinuvo.ui.session.ChatItemView
import kinuvo.ui.session.ChatActions
import kinuvo.app.surface.sumSurfaceValues
import kinuvo.app.surface.surfaceValues
import kinuvo.app.session.ChatItem
import kinuvo.protocol.surface.SurfaceAction
import kinuvo.protocol.session.ToolCallId
import kotlinx.serialization.json.*
import kinuvo.ui.surface.*
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.*

class SurfaceScreenshotTest {
    @Test fun editableWidgets() = javax.swing.SwingUtilities.invokeAndWait {
        for (fixture in listOf("editable-tasks", "weekly-timesheet", "examples/trip-budget", "examples/habit-tracker", "examples/recipe-scaler", "examples/workout-log", "examples/dinner-poll", "examples/invoice-builder")) {
            val name = if (fixture.startsWith("examples/")) "example-${fixture.substringAfter('/')}" else fixture
            val raw = A2uiJson.strict.parseToJsonElement(File("../../server/core/testResources/$fixture.json").readText())
            val checked = CatalogValidator.of(surfaceCatalogDefinitions).validateMessage(raw, catalogId = kinuvoCatalogDefinition.catalogId)
            assertTrue(checked.isValid, checked.violations.joinToString())
            for (dark in listOf(false, true)) {
                val renderer = A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(surfaceCatalogDefinitions))
                renderer.apply(A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), raw))
                var copied: AnnotatedString? = null
                val clipboard = object : ClipboardManager {
                    override fun getText() = copied
                    override fun setText(annotatedString: AnnotatedString) { copied = annotatedString }
                }
                val messages = mutableListOf<RendererToAgentMessage>()
                ImageComposeScene(720, if (name in listOf("weekly-timesheet", "example-workout-log")) 3000 else 1800, Density(2f), content = {
                    HarnessTheme(dark = dark) {
                        Box(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                            CompositionLocalProvider(LocalClipboardManager provides clipboard) {
                                A2uiSurface(renderer, name, surfaceComponents, Modifier.fillMaxWidth(), onMessage = { messages += it })
                            }
                        }
                    }
                }).use { scene ->
                    repeat(8) { scene.render(it * 200_000_000L) }
                    File("build/screens").apply { mkdirs() }.resolve("$name-${if (dark) "dark" else "light"}.png")
                        .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
                    if (dark) {
                        fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                        fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                        fun hasText(text: String) = nodes().any { it.config.getOrNull(SemanticsProperties.Text)?.any { value -> value.text == text } == true }
                        var frame = 3_000_000_000L
                        fun click(label: String, index: Int = 0) {
                            val nodes = nodes()
                            val button = nodes.filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                                flatten(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == label } == true || it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true } }[index]
                            assertTrue(button.config[SemanticsActions.OnClick].action!!.invoke())
                            repeat(4) { frame += 200_000_000L; scene.render(frame) }
                        }
                        fun model() = renderer.state.surfaces.getValue(name).dataModel
                        if (name == "editable-tasks") {
                            click("+ Add row")
                            assertEquals(5, model().getValue("rows").jsonArray.size)
                            assertTrue(hasText("5 tasks · 1 completed"))
                            click("delete")
                            assertEquals(4, model().getValue("rows").jsonArray.size)
                            assertTrue(hasText("4 tasks · 0 completed"))
                            click("Copy table")
                            assertTrue(assertNotNull(copied).text.startsWith("| task | status | priority |"))
                            click("Medium")
                            click("Low", 1)
                            assertEquals(JsonPrimitive("Low"), model().getValue("rows").jsonArray[0].jsonObject.getValue("priority").jsonArray.single())
                        } else if (name == "weekly-timesheet") {
                            click("Increase")
                            assertEquals(75.0, model().getValue("days").jsonArray[0].jsonObject.getValue("tasks").jsonArray[0].jsonObject.getValue("minutes").jsonPrimitive.double)
                            assertTrue(hasText("5h 30m"))
                            click("30m")
                            click("Increase")
                            assertTrue(hasText("6h 00m"))
                            click("delete")
                            assertEquals(255.0, sumSurfaceValues(surfaceValues(model(), listOf("days", "*", "tasks", "*", "minutes"))))
                            assertTrue(hasText("4h 15m"))
                            click("+ Task", 1)
                            assertEquals(1, model().getValue("days").jsonArray[1].jsonObject.getValue("tasks").jsonArray.size)
                            click("Increase", 2)
                            assertEquals(30.0, model().getValue("days").jsonArray[1].jsonObject.getValue("tasks").jsonArray[0].jsonObject.getValue("minutes").jsonPrimitive.double)
                            click("delete", 2)
                            assertEquals(0, model().getValue("days").jsonArray[1].jsonObject.getValue("tasks").jsonArray.size)
                        }
                        if (name.startsWith("example-")) {
                            when (name) {
                                "example-recipe-scaler" -> { click("Increase"); assertTrue(hasText("300 g")) }
                                "example-workout-log" -> { click("Increase"); assertTrue(hasText("1,920 kg total volume")) }
                                "example-invoice-builder" -> { click("Increase"); assertTrue(hasText("€1,260.00")) }
                                "example-habit-tracker" -> assertTrue(hasText("5 wins"))
                                "example-trip-budget" -> {
                                    assertTrue(hasText("Paid €165.00"))
                                    assertTrue(hasText("Pays €10.00"))
                                    assertTrue(hasText("€100.00 each"))
                                }
                                "example-dinner-poll" -> click("Pizza")
                            }
                        }
                        assertTrue(messages.isEmpty(), "Local edits must not send an Agent message")
                        click(when (name) {
                            "example-recipe-scaler" -> "Save grocery list"
                            "example-workout-log" -> "Finish workout"
                            "example-invoice-builder" -> "Send invoice"
                            "example-habit-tracker" -> "Save week"
                            "example-trip-budget" -> "Share settlement"
                            "example-dinner-poll" -> "Submit vote"
                            else -> "Send"
                        })
                        assertEquals(1, messages.size)
                        if (name == "example-invoice-builder") assertEquals(JsonPrimitive("€1,260.00"), (messages.single() as ActionMessage).context["total"])
                    }
                }
            }
        }
    }

    @Test fun kinuvoComponents() = javax.swing.SwingUtilities.invokeAndWait {
        val raw = A2uiJson.strict.parseToJsonElement("""
            {"version":"v1.0","createSurface":{"surfaceId":"extras","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[
            {"id":"root","component":"Column","children":["badge","space","item","table","transition","choice"]},
            {"id":"badge","component":"Badge","label":"Ready"},
            {"id":"space","component":"Spacer","minSize":16},
            {"id":"item","component":"ListItem","title":"Review item","subtitle":"Details","leading":"icon","trailing":"badge","action":{"event":{"name":"review"}}},
            {"id":"icon","component":"Icon","name":"check"},
            {"id":"table","component":"Table","children":{"componentId":"row","path":"/rows"}},
            {"id":"row","component":"TableRow","children":["cell","actionCell","inputCell"]},
            {"id":"cell","component":"TableCell","children":["name"]},
            {"id":"name","component":"Text","text":{"path":"name"}},
            {"id":"actionCell","component":"TableCell","children":["button"]},
            {"id":"button","component":"Button","child":"buttonLabel","action":{"event":{"name":"choose","context":{"name":{"path":"name"}}}}},
            {"id":"buttonLabel","component":"Text","text":"Choose"},
            {"id":"inputCell","component":"TableCell","children":["input"]},
            {"id":"input","component":"TextField","label":"Name","value":{"path":"name"}},
            {"id":"transition","component":"Transition","child":"badge"},
            {"id":"choice","component":"ChoicePicker","label":"Status","displayStyle":"dropdown","options":[{"value":"ready","label":"Ready"},{"value":"waiting","label":"Waiting"}],"value":{"path":"/status"}}],"dataModel":{"status":["ready"],"rows":[{"name":"First"},{"name":"Second"}]}}}
        """)
        val checked = CatalogValidator.of(surfaceCatalogDefinitions).validateMessage(raw, catalogId = kinuvoCatalogDefinition.catalogId)
        assertTrue(checked.isValid, checked.violations.joinToString())
        assertEquals(kinuvoCatalogDefinition.components.keys, surfaceComponents.types)
        val renderer = A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(surfaceCatalogDefinitions))
        renderer.apply(A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), raw))
        val actions = mutableListOf<ActionMessage>()
        for (dark in listOf(false, true)) ImageComposeScene(780, 1400, Density(2f), content = {
            HarnessTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                    A2uiSurface(renderer, "extras", surfaceComponents, Modifier.fillMaxWidth(), onMessage = { if (it is ActionMessage) actions += it })
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-kinuvo-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            if (dark) {
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                val buttons = nodes().filter { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
                    flatten(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "Choose" } == true } }
                buttons.last().config[SemanticsActions.OnClick].action!!.invoke()
                assertEquals(JsonPrimitive("Second"), actions.single().context["name"])
                nodes().last { it.config.getOrNull(SemanticsActions.SetText) != null }
                    .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Edited"))
                repeat(8) { scene.render(3_000_000_000L + it * 200_000_000L) }
                val rows = renderer.state.surfaces.getValue("extras").dataModel.getValue("rows").jsonArray
                assertEquals(JsonPrimitive("First"), rows[0].jsonObject["name"])
                assertEquals(JsonPrimitive("Edited"), rows[1].jsonObject["name"])
            }
        }
    }

    @Test fun allBasicComponents() = javax.swing.SwingUtilities.invokeAndWait {
        val message = A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), allSurface)
        val renderer = A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(listOf(kinuvoCatalogDefinition)))
        val checked = CatalogValidator.of(listOf(kinuvoCatalogDefinition)).validateMessage(
            A2uiJson.strict.encodeToJsonElement(AgentToRendererMessage.serializer(), message), catalogId = kinuvoCatalogDefinition.catalogId)
        assertTrue(checked.isValid, checked.violations.joinToString())
        renderer.apply(message)
        assertEquals(kinuvoCatalogDefinition.components.keys, surfaceComponents.types)
        for (dark in listOf(false, true)) ImageComposeScene(1040, 2700, Density(2f), content = {
            HarnessTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(color(backgroundColor)).padding(24.dp)) {
                    A2uiSurface(renderer, "all", surfaceComponents, Modifier.fillMaxWidth())
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-all-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun modalRetainsRendererContext() = javax.swing.SwingUtilities.invokeAndWait {
        val message = A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), """
            {"version":"v1.0","createSurface":{"surfaceId":"modal","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[
            {"id":"root","component":"Modal","trigger":"trigger","content":"details"},
            {"id":"trigger","component":"Text","text":"Review details"},
            {"id":"details","component":"Text","text":"Inside modal"}]}}
        """)
        for (dark in listOf(false, true)) {
            val renderer = A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(listOf(kinuvoCatalogDefinition)))
            renderer.apply(message)
            ImageComposeScene(1000, 800, Density(2f), content = {
                HarnessTheme(dark = dark) {
                    val popovers = remember { Popovers() }
                    CompositionLocalProvider(LocalPopovers provides popovers) {
                        Box(Modifier.fillMaxSize().background(color(backgroundColor))) {
                            A2uiSurface(renderer, "modal", surfaceComponents, Modifier.padding(24.dp))
                            popovers.Host()
                        }
                    }
                }
            }).use { scene ->
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                repeat(8) { scene.render(it * 200_000_000L) }
                nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open" }
                    .config[SemanticsActions.OnClick].action!!.invoke()
                repeat(8) { scene.render(2_000_000_000L + it * 200_000_000L) }
                assertTrue(nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Inside modal" } == true })
                File("build/screens").apply { mkdirs() }.resolve("surface-native-modal-${if (dark) "dark" else "light"}.png")
                    .writeBytes(scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            }
        }
    }

    @Test fun catalogIcons() {
        val names = kinuvoCatalogDefinition.components.getValue("Icon").schema.getValue("properties").jsonObject
            .getValue("name").jsonObject.getValue("oneOf").jsonArray.first().jsonObject.getValue("enum").jsonArray
            .map { it.jsonPrimitive.content }
        names.filter { it != "help" }.forEach { assertNotEquals(catalogIcon("unknown"), catalogIcon(it), it) }
        assertEquals(Lucide.Heart, catalogIcon("favorite"))
        assertEquals(catalogIcon("help"), catalogIcon("unknown"))
        assertEquals(catalogIcon("help"), catalogIcon(null))
    }

    @Test fun textEditsRetainSelectionAcrossModelEchoes() = javax.swing.SwingUtilities.invokeAndWait {
        for (variant in listOf("shortText", "number")) {
            val args = """{"messages":[{"createSurface":{"surfaceId":"form","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[
                {"id":"root","component":"TextField","variant":"$variant","value":{"path":"/value"}}],"dataModel":{"value":${if (variant == "number") "700.0" else "\"12.00\""}}}}]}"""
            val call = ChatItem.ToolCall("tool", "call", "show_ui", args, result = "Shown")
            val cache = mutableMapOf<String, SurfaceRenderer>()
            ImageComposeScene(780, 300, Density(2f), content = {
                HarnessTheme(dark = false) {
                    SelectionContainer {
                        CompositionLocalProvider(LocalSurfaceRenderers provides cache) {
                            SurfaceCall(call, { _, _ -> }) { fail("Surface failed to render") }
                        }
                    }
                }
            }).use { scene ->
                var frame = 0L
                fun settle() { repeat(8) { frame += 200_000_000L; scene.render(frame) } }
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                fun field() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .first { it.config.getOrNull(SemanticsActions.SetText) != null }
                settle()
                if (variant == "number") {
                    assertEquals("700", field().config[SemanticsProperties.EditableText].text)
                    field().config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("12.00"))
                    settle()
                }
                field().config[SemanticsActions.SetSelection].action!!.invoke(1, 1, false)
                settle()
                cache.getValue("call").renderer.write("form", JsonPointer.parse("/value"),
                    if (variant == "number") JsonPrimitive(12.0) else JsonPrimitive("12.00"))
                settle()
                assertEquals("12.00", field().config[SemanticsProperties.EditableText].text)
                assertEquals(TextRange(1), field().config[SemanticsProperties.TextSelectionRange])
                field().config[SemanticsActions.InsertTextAtCursor].action!!.invoke(AnnotatedString("3"))
                settle()
                assertEquals("132.00", field().config[SemanticsProperties.EditableText].text)
                assertEquals(TextRange(2), field().config[SemanticsProperties.TextSelectionRange])
                assertEquals(if (variant == "number") JsonPrimitive(132.0) else JsonPrimitive("132.00"),
                    cache.getValue("call").renderer.state.surfaces.getValue("form").dataModel["value"])
                if (variant == "number") {
                    cache.getValue("call").renderer.write("form", JsonPointer.parse("/value"), JsonPrimitive(420.0))
                    settle()
                    assertEquals("420", field().config[SemanticsProperties.EditableText].text)
                    cache.getValue("call").renderer.write("form", JsonPointer.parse("/value"), JsonPrimitive(420.5))
                    settle()
                    assertEquals("420.5", field().config[SemanticsProperties.EditableText].text)
                }
            }
        }
    }

    @Test fun restoresSubmittedInputsAndKeepsCachedEdits() = javax.swing.SwingUtilities.invokeAndWait {
        val args = """{"messages":[{"createSurface":{"surfaceId":"form","catalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json","components":[{"id":"root","component":"TextField","label":"Name","value":{"path":"/name"}}],"dataModel":{"name":"Initial"}}}]}"""
        val call = ChatItem.ToolCall("tool", "call", "show_ui", args, result = "Shown")
        val saved = JsonObject(mapOf("name" to JsonPrimitive("Alex")))
        val action = SurfaceAction(ToolCallId("call"), "form", "submit", "submit", saved, saved)
        val cache = mutableMapOf<String, SurfaceRenderer>()
        repeat(2) { pass ->
            ImageComposeScene(780, 240, Density(2f), content = {
                HarnessTheme(dark = false) {
                    CompositionLocalProvider(LocalSurfaceRenderers provides cache,
                        LocalSurfaceItems provides listOf(call, ChatItem.User("action", "Save", action = action))) {
                        SurfaceCall(call, { _, _ -> }) { fail("Surface failed to render") }
                    }
                }
            }).use { scene ->
                repeat(8) { scene.render(it * 200_000_000L) }
                val renderer = cache.getValue("call").renderer
                assertEquals(JsonPrimitive(if (pass == 0) "Alex" else "Unsaved edit"), renderer.state.surfaces.getValue("form").dataModel["name"])
                renderer.write("form", JsonPointer.parse("/name"), JsonPrimitive("Unsaved edit"))
            }
        }
    }


    @Test fun cachedRendererAppliesActionArrivingAfterComposition() = javax.swing.SwingUtilities.invokeAndWait {
        val args = """{"messages":[{"createSurface":{"surfaceId":"form","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[{"id":"root","component":"TextField","value":{"path":"/name"}}],"dataModel":{"name":"Initial"}}}]}"""
        val call = ChatItem.ToolCall("tool", "call", "show_ui", args, result = "Shown")
        val items = mutableStateOf<List<ChatItem>>(listOf(call))
        val cache = mutableMapOf<String, SurfaceRenderer>()
        ImageComposeScene(780, 240, Density(2f), content = {
            HarnessTheme(dark = false) {
                CompositionLocalProvider(LocalSurfaceRenderers provides cache, LocalSurfaceItems provides items.value) {
                    SurfaceCall(call, { _, _ -> }) { fail("Surface failed to render") }
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            val renderer = cache.getValue("call").renderer
            renderer.write("form", JsonPointer.parse("/name"), JsonPrimitive("Local edit"))
            val model = buildJsonObject { put("name", "Other Device") }
            items.value = listOf(call, ChatItem.User("new-action", "Save", action = SurfaceAction(ToolCallId("call"), "form", "save", "save", model, model)))
            repeat(8) { scene.render(2_000_000_000L + it * 200_000_000L) }
            assertSame(renderer, cache.getValue("call").renderer)
            assertEquals(model, renderer.state.surfaces.getValue("form").dataModel)
        }
    }

    @Test fun invalidDateEditCannotSubmitPreviousValue() = javax.swing.SwingUtilities.invokeAndWait {
        val args = """{"messages":[{"createSurface":{"surfaceId":"form","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[
            {"id":"root","component":"Column","children":["date","save"]},
            {"id":"date","component":"DateTimeInput","enableDate":true,"value":{"path":"/date"}},
            {"id":"save","component":"Button","child":"label","action":{"event":{"name":"save"}}},
            {"id":"label","component":"Text","text":"Save"}],"dataModel":{"date":"2026-02-28"}}}]}"""
        val call = ChatItem.ToolCall("tool", "call", "show_ui", args, result = "Shown")
        val sent = mutableListOf<SurfaceAction>()
        val items = mutableStateOf<List<ChatItem>>(listOf(call))
        ImageComposeScene(780, 500, Density(2f), content = {
            HarnessTheme(dark = false) {
                CompositionLocalProvider(LocalSurfaceItems provides items.value) {
                    SurfaceCall(call, { _, action -> sent += action }) { fail("Surface failed to render") }
                }
            }
        }).use { scene ->
            fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
            fun nodes() = scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
            repeat(8) { scene.render(it * 200_000_000L) }
            nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("2026-02-30"))
            repeat(8) { scene.render(2_000_000_000L + it * 200_000_000L) }
            nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsActions.SetText) == null }
                .config[SemanticsActions.OnClick].action!!.invoke()
            assertTrue(sent.isEmpty())
            val submitted = buildJsonObject { put("date", "2026-02-28") }
            items.value = listOf(call, ChatItem.User("remote-action", "Save", action = SurfaceAction(ToolCallId("call"), "form", "save", "save", submitted, submitted)))
            repeat(8) { scene.render(4_000_000_000L + it * 200_000_000L) }
            nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsActions.SetText) == null }
                .config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(submitted, sent.single().dataModel)
            nodes().first { it.config.getOrNull(SemanticsActions.SetText) != null }
                .config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("2026-03-01"))
            repeat(8) { scene.render(6_000_000_000L + it * 200_000_000L) }
            nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null && it.config.getOrNull(SemanticsActions.SetText) == null }
                .config[SemanticsActions.OnClick].action!!.invoke()
            assertEquals(JsonPrimitive("2026-03-01"), sent.last().dataModel["date"])
        }
    }

    @Test fun openUrlDispatchUsesPlatformOpenerForHttpOnly() = javax.swing.SwingUtilities.invokeAndWait {
        val opened = mutableListOf<String>()
        for (url in listOf("https://example.com", "file:///secret")) {
            val args = """{"messages":[{"createSurface":{"surfaceId":"form","catalogId":"${kinuvoCatalogDefinition.catalogId}","components":[
                {"id":"root","component":"Button","child":"label","action":{"functionCall":{"call":"openUrl","args":{"url":"$url"}}}},
                {"id":"label","component":"Text","text":"Open"}]}}]}"""
            val call = ChatItem.ToolCall("tool", url, "show_ui", args, result = "Shown")
            ImageComposeScene(780, 240, Density(2f), content = {
                HarnessTheme(dark = false) {
                    CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                        override fun openUri(uri: String) { opened += uri }
                    }) { SurfaceCall(call, { _, _ -> fail("URL action sent a message") }) { fail("Surface failed to render") } }
                }
            }).use { scene ->
                fun flatten(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::flatten)
                repeat(8) { scene.render(it * 200_000_000L) }
                scene.semanticsOwners.flatMap { flatten(it.unmergedRootSemanticsNode) }
                    .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
                    .config[SemanticsActions.OnClick].action!!.invoke()
            }
        }
        assertEquals(listOf("https://example.com"), opened)
    }

    @Test fun actionLine() = javax.swing.SwingUtilities.invokeAndWait {
        for (dark in listOf(false, true)) ImageComposeScene(780, 180, Density(2f), content = {
            HarnessTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(color(backgroundColor)).padding(16.dp)) {
                    ChatItemView(ChatItem.User("action", "Plan your day", action = SurfaceAction(ToolCallId("call"), "form", "submit", "submit", JsonObject(emptyMap()), JsonObject(emptyMap()))), ChatActions())
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-action-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }

    @Test fun nativeForm() = javax.swing.SwingUtilities.invokeAndWait {
        val message = A2uiJson.strict.decodeFromString(AgentToRendererMessage.serializer(), """
            {"version":"v1.0","createSurface":{"surfaceId":"form","catalogId":"https://a2ui.org/specification/v1_0/catalogs/basic/catalog.json",
            "components":[
              {"id":"root","component":"Card","child":"fields"},
              {"id":"fields","component":"Column","align":"stretch","children":["title","name","choices","accept","buttons"]},
              {"id":"title","component":"Text","text":"## Plan your day"},
              {"id":"name","component":"TextField","label":"Name","value":{"path":"/name"}},
              {"id":"choices","component":"ChoicePicker","label":"Focus","value":{"path":"/focus"},"variant":"mutuallyExclusive","options":[{"label":"Creative work","value":"create"},{"label":"Planning","value":"plan"}]},
              {"id":"accept","component":"CheckBox","label":"Remind me tomorrow","value":{"path":"/remind"}},
              {"id":"buttons","component":"Row","children":["submit","cancel"]},
              {"id":"submit","component":"Button","variant":"primary","child":"submitLabel","action":{"event":{"name":"submit","context":{"name":{"path":"/name"}}}}},
              {"id":"submitLabel","component":"Text","text":"Save plan"},
              {"id":"cancel","component":"Button","child":"cancelLabel","action":{"event":{"name":"cancel"}}},
              {"id":"cancelLabel","component":"Text","text":"Cancel"}
            ],"dataModel":{"name":"Alex","focus":["create"],"remind":true}}}
        """)
        val renderer = A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(listOf(kinuvoCatalogDefinition)))
        val checked = CatalogValidator.of(listOf(kinuvoCatalogDefinition)).validateMessage(
            A2uiJson.strict.encodeToJsonElement(AgentToRendererMessage.serializer(), message),
            catalogId = kinuvoCatalogDefinition.catalogId,
        )
        assertTrue(checked.isValid, checked.violations.joinToString())
        renderer.apply(message)
        assertEquals(kinuvoCatalogDefinition.components.keys, surfaceComponents.types)
        for (dark in listOf(false, true)) ImageComposeScene(1040, 1240, Density(2f), content = {
            HarnessTheme(dark = dark) {
                Box(Modifier.fillMaxSize().background(color(backgroundColor)).padding(24.dp)) {
                    A2uiSurface(renderer, "form", surfaceComponents, Modifier.fillMaxWidth())
                }
            }
        }).use { scene ->
            repeat(8) { scene.render(it * 200_000_000L) }
            File("build/screens").apply { mkdirs() }.resolve("surface-form-${if (dark) "dark" else "light"}.png")
                .writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        }
    }
}

package kinuvo.ui.surface

import androidx.compose.ui.platform.LocalUriHandler
import kinuvo.app.surface.SurfaceDrafts
import kinuvo.app.surface.SurfaceSubmissions
import kinuvo.app.surface.openSurfaceUrl
import kinuvo.app.surface.surfaceAction
import kinuvo.app.surface.surfaceSubmissions
import kinuvo.protocol.SurfaceAction

import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import dev.ynagai.a2ui.compose.*
import dev.ynagai.a2ui.core.protocol.*
import kinuvo.app.session.ChatItem
import kinuvo.protocol.ProtocolJson
import kinuvo.protocol.uiMessages
import kotlinx.serialization.json.jsonObject

internal val LocalSurfaceItems = staticCompositionLocalOf<List<ChatItem>> { emptyList() }

internal class SurfaceRenderer(val renderer: A2uiRenderer) {
    val submissions = SurfaceSubmissions()
}

internal val LocalSurfaceDrafts = staticCompositionLocalOf<SurfaceDrafts?> { null }
internal val LocalSurfaceRenderers = staticCompositionLocalOf<MutableMap<String, SurfaceRenderer>> { mutableMapOf() }

@Composable
internal fun SurfaceCall(call: ChatItem.ToolCall, send: (String, SurfaceAction) -> Unit, fallback: @Composable () -> Unit) {
    val opener by rememberUpdatedState(LocalUriHandler.current)
    val cache = LocalSurfaceRenderers.current
    val models = LocalSurfaceItems.current.surfaceSubmissions(call.callId)
    val renderer = remember(call.callId, call.args, cache) {
        cache[call.callId] ?: runCatching {
            val messages = uiMessages(ProtocolJson.parseToJsonElement(call.args).jsonObject).map {
                A2uiJson.strict.decodeFromJsonElement(AgentToRendererMessage.serializer(), it)
            }
            A2uiRenderer(A2uiRendererConfig.Default.withCatalogs(surfaceCatalogDefinitions)
                .withUrlOpener { url -> openSurfaceUrl(url) { runCatching { opener.openUri(it) } } }).apply {
                applyAll(messages)
            }
        }.getOrNull()?.let(::SurfaceRenderer)?.also { cache[call.callId] = it }
    }
    if (renderer == null) fallback()
    else DisableSelection {
        Column {
            val state = renderer.renderer
            renderer.submissions.update(models) { id, model -> state.apply(UpdateDataModelMessage(surfaceId = id, path = "/", value = model)) }
            state.state.surfaces.keys.forEach { id ->
                key(id, models[id]?.first) {
                    val drafts = remember { SurfaceDrafts() }
                    CompositionLocalProvider(LocalSurfaceDrafts provides drafts) {
                        A2uiSurface(state, id, surfaceComponents, onMessage = { message ->
                            if (message is ActionMessage && drafts.canSubmit) send(message.userMessage ?: message.name,
                                message.surfaceAction(call.callId, state.state.surfaces.getValue(id).dataModel))
                        })
                    }
                }
            }
        }
    }
}

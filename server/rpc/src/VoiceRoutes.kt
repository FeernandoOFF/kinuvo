package kinuvo.server.rpc

import kinuvo.protocol.Api

internal fun RouteBuilder.voiceRoutes() {
    device(Api.LiveCallStart, ordered = true) { caller.startLiveCall(it) }
    device(Api.LiveCallPeer, ordered = true) { caller.liveCallPeer(it) }
    device(Api.LiveCallPause, ordered = true) { caller.pauseLiveCall(it.callId) }
    device(Api.LiveCallResume, ordered = true) { caller.resumeLiveCall(it.callId) }
    device(Api.LiveCallEnd, ordered = true) { caller.endLiveCall(it.callId) }
    device(Api.LiveVoiceSettings) { server.voice.liveSettings() }
    handle(Api.SaveLiveVoice) { server.voice.saveLive(it) }
    handle(Api.VoiceModels) { server.voice.models() }
    device(Api.VoiceSetup) { server.voice.setup() }
    handle(Api.VoiceDownload) { server.voice.download(it) }
    handle(Api.VoiceCancelDownload) { server.voice.cancelDownload() }
    handle(Api.VoiceRemove) { server.voice.remove(it) }
    handle(Api.VoiceSelect) { server.voice.select(it) }
    handle(Api.VoiceLanguage) { server.voice.language(it) }
    device(Api.Transcribe) { server.voice.transcribe(it) }
    device(Api.Synthesize) { server.voice.synthesize(it) }
}

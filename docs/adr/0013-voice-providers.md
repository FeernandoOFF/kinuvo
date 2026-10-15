# Voice providers: a Device pipeline, a Server pipeline, or speech to speech

Voice chat ships on-device only: the Device's recognizer turns speech into a message (`voice = true`), the Agent replies as usual, and the Device's voice reads the reply aloud. A proof of concept (git stash `voice: server STT/processing/TTS + OpenAI realtime POC`) added two more modes; it was rolled back unreleased, keeping the seam to bring them back:

- **Server**: `VoiceProviders` (`harness/core/src/voice/`) holds the parts it provides. The default holds none.
- **Protocol**: `ServerCapabilities.voice` is a `VoiceSetup` saying which parts exist; older Servers mean all-Device.
- **Device**: `Transcriber` and `Synthesizer` (`shared/app/src/session/Voice.kt`). `SpeechEngine` is the Device's own pair (`AppleSpeech` on iOS) and the default for every part. `SpeechVoiceChat` runs over any pair and dictation over any `Transcriber`; `VoiceChat` is the seam for speech to speech.

1. **Device pipeline** (shipped): recognition and speech on the Device. Needs no Server support.
2. **Server pipeline**: any of `Transcriber` (audio → text), `TranscriptProcessor` (text → corrected text) and `Synthesizer` (text → audio) run on the Server; missing parts stay on the Device. The Agent and its model are unchanged.
3. **Speech to speech**: `SpeechToSpeech` holds one live audio conversation. The voice model talks; anything needing tools, Memory or reasoning goes through `askAgent`, which runs the Session's Agent with its normal tools and Approvals.

Audio never passes through `Model.stream`: Agents stay text, and voice providers are separate from model providers. The Server owns endpoints and keys (the `voice` section of `config.yaml`, `${…}` Secret references) and tells Devices only which parts exist. Settings are read when voice chat starts. A provider failure ends voice chat with an error rather than silently falling back.

## What the POC taught

**Provider APIs.** OpenAI-compatible endpoints cover most of the pipeline, local ones included (MLX Audio on a Mac served Whisper and Kokoro): multipart `/v1/audio/transcriptions`, `/v1/chat/completions` for processing, `/v1/audio/speech` returning WAV. One adapter per protocol, not per vendor. Allow HTTPS, or HTTP/WS on loopback only; refuse credentials in URLs. Bound every size: 2 MB recordings, 20,000-character transcripts, 2,000 characters per synthesis, 8 MB WAV, 128 KB JSON responses. First model loads can exceed request timeouts; warm them up.

**Server pipeline turn-taking.** Without echo cancellation the microphone must close while a reply plays, so interruption means Pause. Device recording ends after ~1.3 s of quiet following speech, or 30 s; ten seconds of silence restarts without a provider request. Speak replies sentence by sentence (chunks up to 240 characters), synthesizing one chunk ahead while the current one plays, keeping order.

**Speech to speech.** OpenAI Realtime over WebSocket; PCM16 mono at 24 kHz in 200 ms chunks, played as it arrives.
- *Phantom turns* were the main problem. Disable the provider's automatic replies (`create_response: false`) and decide on the Server: zero frames below about −45 dBFS, require 120 ms of audible input in the turn and a transcript with letters or digits, else delete the item. Use near-field noise reduction, a 0.75 VAD threshold and 900 ms of ending silence. The instructions must say that silence, noise and echoes are not turns.
- *Interruption*: the Device reports how much of each reply it has played (`Played`, `Interrupt`). The provider item is truncated there, so the model knows what was heard. Transcripts can't be aligned to audio exactly, so an interrupted reply is saved with a marker.
- *Agent handoff*: one `ask_agent` tool, one task at a time. Wait for the user's transcript before running it. Pause or interruption cancels it, and the model is told completion is unconfirmed. The run records no user message of its own, since the spoken transcript already did. The model is told never to claim success before `ask_agent` confirms.
- *Context*: Agent instructions, personal context and the last ~40 messages, redacted, as of connection time. Each handoff loads current context.

**Session history.** The Session stays the record: the user's transcript is saved once, and a reply is saved when fully played or flushed when cut short. Only a Session entry shows the user's turn; keeping it in the live draft too showed it twice. While voice chat owns a Session, typed sends to it are refused. One Device connection owns a voice chat, which ends when that connection drops.

**Devices.** `VoiceChat` and `VoiceChatState` covered all three modes unchanged; choose the implementation from what the Server offers when voice chat starts. `SpeechEngine` grew a flag per mode (`recordsAudio`, `playsAudio`, `streamsAudio`). Split it instead into a microphone (recognize, record a turn, or stream PCM) and a speaker (speak text, play a clip, or play a stream and report its position). Platform callbacks outlive chats, and the Server can change mid-chat, so every callback is checked against the chat's generation. iOS echo cancellation (voice-chat audio session with voice processing) makes talking over replies work; the simulator has none.

## Bringing it back

Configuration chooses a provider per part (`transcription`, `processing`, `synthesis`, or `realtime`, each with `type`, `url`, `model`, `apiKey` and options such as `voice`), built into `VoiceProviders` when voice chat starts. Implement the Server parts, then give the Device a client for each: a `Transcriber` that records a turn and calls `voice.prepare`, a `Synthesizer` that calls `voice.synthesize` and plays the clip, and a `VoiceChat` over a voice stream (`voice.start`, `voice.command`, the `voice.event` event) for speech to speech. `VoiceViewModel` picks them from `VoiceSetup` when voice chat starts.

Accepted cost: a third-party provider receives the audio, Agent instructions and recent conversation. Speech-to-speech protocols differ per vendor (OpenAI Realtime, Gemini Live, local models), so each needs its own `SpeechToSpeech` adapter. They differ in sample rates (each adapter declares its own, and the Device converts), in who detects turns and interruptions, and in whether a reply can be truncated where it was cut off. The voice model and the Agent can phrase the same answer differently.

package kinuvo.app

import kinuvo.app.session.*

import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceTest {
    @Test fun speechChunksKeepWordsAndSurrogatePairsTogether() {
        val text = "hello ".repeat(60).trim()
        val chunks = speechChunks(text)
        assertEquals(text, chunks.joinToString(" "))
        assertEquals(true, chunks.all { it.length <= 240 && it.endsWith("hello") })
        val emoji = "a".repeat(239) + "😀 tail"
        assertEquals(emoji, speechChunks(emoji).joinToString(""))
    }
    @Test
    fun speaksOnlyFinishedSentences() {
        val text = "Sure. It's 3.5 km away! Want the"
        assertEquals(listOf("Sure.".length, "Sure. It's 3.5 km away!".length), sentenceEnds(text, 0))
        assertEquals(listOf("Sure. It's 3.5 km away!".length), sentenceEnds(text, "Sure.".length))
        assertEquals(emptyList(), sentenceEnds(text, text.length))
        assertEquals(listOf("One\n".length), sentenceEnds("One\nTwo", 0))
    }

    @Test
    fun readsMarkdownAsPlainText() {
        assertEquals("Buy milk\nCall Ana, see notes", speakable("- **Buy** milk\n1. Call `Ana`, see [notes](https://x.y)"))
    }

    @Test
    fun onlyRecentWordsThatArentTheReplyInterruptIt() {
        val said = words("Sure, the weather in Dublin is mild today with light rain later").toSet()
        assertEquals(false, interrupts("the weather in dublin is mild today", said))
        assertEquals(false, interrupts("sure the whether in dub lynn is mild today with light rain", said))
        assertEquals(false, interrupts("keen of oh weather in dub lynn is mild today with light rain later", said))
        assertEquals(true, interrupts("the weather in dublin wait stop that what about cork", said))
    }

    @Test
    fun splitsWords() = assertEquals(listOf("hello", "whos", "there"), words("Hello, who's there?"))
}

private class FakeSpeech : Transcriber {
    var onPartial: (String) -> Unit = {}
    var onFinal: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var stops = 0
    override fun authorize(completion: (String?) -> Unit) = completion(null)
    override fun listen(untilPause: Boolean, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit) {
        this.onPartial = onPartial; this.onFinal = onFinal; this.onError = onError
    }
    override fun stop() { stops++ }
}

class DictationTest {
    @Test
    fun keepsTheFinalWordsAfterStopping() {
        val speech = FakeSpeech()
        val dictation = DictationViewModel(speech)
        dictation.toggle()
        speech.onPartial("call me")
        speech.onFinal("Call me back.")
        assertEquals(DictationState(dictating = false, text = "Call me back."), dictation.state.value)
        assertEquals(1, speech.stops)
        dictation.discard()
        assertEquals(DictationState(), dictation.state.value)
    }

    @Test
    fun dictationGoesAfterTheDraftAndKeepsEditsMadeMeanwhile() {
        var draft = DictationDraft("Hi")
        draft = draft.next("Hi", "call me")
        assertEquals("Hi call me", draft.shown)
        draft = draft.next(draft.shown, "call me back")
        assertEquals("Hi call me back", draft.shown)
        draft = draft.next("Hi, call me later", "call me back tomorrow")
        assertEquals("Hi, call me later tomorrow", draft.shown)
        draft = draft.next(draft.shown, "call me back tomorrow at 5")
        assertEquals("Hi, call me later tomorrow at 5", draft.shown)
    }

    @Test
    fun anErrorReleasesTheMicrophone() {
        val speech = FakeSpeech()
        val dictation = DictationViewModel(speech)
        dictation.toggle()
        speech.onError("Speech recognition is unavailable")
        assertEquals(DictationState(error = "Speech recognition is unavailable"), dictation.state.value)
        assertEquals(1, speech.stops)
    }
}

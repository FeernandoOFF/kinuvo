using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Speech.AudioFormat;
using System.Speech.Recognition;
using System.Speech.Synthesis;
using System.Text;
using System.Web.Script.Serialization;

// System.Speech uses desktop SAPI, without MSIX identity or a WinRT capability manifest.
internal static class WindowsSpeech
{
    static readonly JavaScriptSerializer Json = new JavaScriptSerializer();
    static readonly BlockingCollection<Action> Events = new BlockingCollection<Action>();
    static readonly Dictionary<Prompt, int> Spoken = new Dictionary<Prompt, int>();
    static bool running = true;
    static SpeechRecognitionEngine recognizer;
    static SpeechSynthesizer synth;

    static void Emit(string kind, int id, string text = "")
    {
        Console.WriteLine(Json.Serialize(new { @event = kind, id = id, text = text }));
        Console.Out.Flush();
    }

    static SpeechRecognitionEngine Recognizer()
    {
        var installed = SpeechRecognitionEngine.InstalledRecognizers();
        var selected = installed.FirstOrDefault(r => r.Culture.Equals(CultureInfo.CurrentUICulture))
            ?? installed.FirstOrDefault();
        if (selected == null) throw new InvalidOperationException("No desktop speech recognizer installed. Install a Windows speech language in Settings > Time & language > Speech.");
        return new SpeechRecognitionEngine(selected);
    }

    static void StopListening()
    {
        var previous = recognizer;
        recognizer = null;
        if (previous != null) { previous.RecognizeAsyncCancel(); previous.Dispose(); }
    }

    static void Listen(int id, bool untilPause)
    {
        StopListening();
        var engine = Recognizer();
        recognizer = engine;
        engine.LoadGrammar(new DictationGrammar());
        engine.SetInputToDefaultAudioDevice();
        var text = "";
        engine.SpeechHypothesized += (s, e) => Events.Add(() => {
            if (recognizer == engine) Emit("partial", id, (text + " " + e.Result.Text).Trim());
        });
        engine.SpeechRecognized += (s, e) => Events.Add(() => {
            if (recognizer != engine) return;
            text = (text + " " + e.Result.Text).Trim();
            Emit("partial", id, text);
            if (untilPause) { StopListening(); Emit("final", id, text); }
        });
        engine.RecognizeCompleted += (s, e) => Events.Add(() => {
            if (recognizer != engine) return;
            StopListening();
            if (e.Error != null) Emit("error", id, e.Error.Message);
            else Emit("final", id, text);
        });
        engine.RecognizeAsync(RecognizeMode.Multiple);
    }

    static SpeechSynthesizer Synthesizer()
    {
        if (synth != null) return synth;
        synth = new SpeechSynthesizer();
        synth.SetOutputToDefaultAudioDevice();
        synth.SpeakCompleted += (s, e) => Events.Add(() => {
            int id;
            if (!Spoken.TryGetValue(e.Prompt, out id)) return;
            Spoken.Remove(e.Prompt);
            if (e.Error != null) Emit("error", id, e.Error.Message);
            Emit("done", id);
        });
        return synth;
    }

    static void StopSpeaking()
    {
        if (synth != null) synth.SpeakAsyncCancelAll();
        foreach (var id in Spoken.Values) Emit("done", id);
        Spoken.Clear();
    }

    static void Handle(Dictionary<string, object> command)
    {
        var id = Convert.ToInt32(command["id"]);
        var op = (string)command["op"];
        try {
            switch (op) {
                case "authorize":
                    using (var engine = Recognizer()) { engine.LoadGrammar(new DictationGrammar()); engine.SetInputToDefaultAudioDevice(); }
                    Synthesizer();
                    Emit("authorized", id);
                    break;
                case "listen": Listen(id, (bool)command["untilPause"]); break;
                case "stop": StopListening(); break;
                case "speak":
                    var prompt = new Prompt((string)command["text"]);
                    var voice = Synthesizer();
                    Spoken.Add(prompt, id);
                    voice.SpeakAsync(prompt);
                    break;
                case "stopSpeaking": StopSpeaking(); break;
            }
        } catch (Exception e) {
            if (op == "listen") StopListening();
            Emit(op == "authorize" ? "authorized" : "error", id,
                e.Message + " Check Windows microphone access for desktop apps, audio devices, and installed speech languages.");
            if (op == "speak") Emit("done", id);
        }
    }

    // Exercises real native synthesis and recognition without a microphone or speaker on CI.
    static int SelfTest()
    {
        var path = Path.GetTempFileName();
        try {
            using (var engine = Recognizer())
            using (var voice = new SpeechSynthesizer()) {
                voice.SelectVoiceByHints(VoiceGender.NotSet, VoiceAge.NotSet, 0, engine.RecognizerInfo.Culture);
                voice.SetOutputToWaveFile(path, new SpeechAudioFormatInfo(16000, AudioBitsPerSample.Sixteen, AudioChannel.Mono));
                voice.Speak("Hello computer");
                voice.SetOutputToNull();
                if (new FileInfo(path).Length <= 44) throw new Exception("Synthesis produced no audio");
                engine.LoadGrammar(new DictationGrammar());
                engine.SetInputToWaveFile(path);
                var result = engine.Recognize(TimeSpan.FromSeconds(20));
                if (result == null || String.IsNullOrWhiteSpace(result.Text)) throw new Exception("Native recognition produced no text");
                Console.WriteLine("Native SAPI WAV synthesis + dictation recognition: " + result.Text + " (" + engine.RecognizerInfo.Culture + ")");
            }
            return 0;
        } finally { File.Delete(path); }
    }

    static int Main(string[] args)
    {
        Console.InputEncoding = new UTF8Encoding(false);
        Console.OutputEncoding = new UTF8Encoding(false);
        if (args.Length == 1 && args[0] == "--self-test") return SelfTest();
        var input = new System.Threading.Thread(() => {
            string line;
            while ((line = Console.ReadLine()) != null) {
                var data = line;
                Events.Add(() => Handle(Json.Deserialize<Dictionary<string, object>>(data)));
            }
            Events.Add(() => running = false);
        });
        input.IsBackground = true;
        input.Start();
        try {
            while (running) Events.Take()();
        } finally {
            StopListening();
            if (synth != null) synth.Dispose();
        }
        return 0;
    }
}

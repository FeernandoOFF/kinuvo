import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import kinuvo.plugins.voice.Inference;

final class VoiceWorkerProbe {
    static void run() throws Exception {
        var worker = Inference.Companion.bundled();
        if (!new Inference(worker).getSupported()) throw new AssertionError("Bundled worker unavailable: " + worker);
        try (var original = VoiceWorkerProbe.class.getClassLoader().getResourceAsStream("server-voice/MacOS/KinuvoVoiceInference")) {
            if (original == null || !Arrays.equals(original.readAllBytes(), Files.readAllBytes(worker.toPath()))) throw new AssertionError("Worker bytes changed");
        }
        var signature = new ProcessBuilder("/usr/bin/codesign", "--verify", "--strict", worker.getAbsolutePath()).inheritIO().start();
        if (!signature.waitFor(10, TimeUnit.SECONDS)) { signature.destroyForcibly(); signature.waitFor(); throw new AssertionError("codesign timed out"); }
        if (signature.exitValue() != 0) throw new AssertionError("Worker signature invalid");
        var process = new ProcessBuilder(worker.getAbsolutePath()).inheritIO().start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) throw new AssertionError("Worker did not exit");
            if (process.exitValue() != 2) throw new AssertionError("Unexpected worker usage exit: " + process.exitValue());
        } finally { process.destroyForcibly(); process.waitFor(); }
        System.out.println("VOICE_WORKER_READY signed/executable/usage-exit-2 bytes=" + worker.length());
    }
}

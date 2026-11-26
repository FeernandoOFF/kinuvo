import ai.koog.http.client.ktor.KtorKoogHttpClient;
import ai.koog.prompt.Prompt;
import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig;
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings;
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient;
import ai.koog.prompt.executor.clients.openai.models.Item;
import ai.koog.prompt.executor.clients.openai.models.OpenAIResponsesAPIRequest;
import ai.koog.prompt.llm.LLMCapability;
import ai.koog.prompt.llm.LLMProvider;
import ai.koog.prompt.llm.LLModel;
import ai.koog.prompt.streaming.StreamFrame;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import kinuvo.cli.MainKt;
import kinuvo.plugins.apple.NativeAppleKt;
import kinuvo.plugins.browser.BrowserRuntime;
import kinuvo.protocol.browser.BrowserCommand;
import kinuvo.protocol.browser.BrowserFrame;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.flow.FlowKt;
import kotlinx.serialization.internal.PlatformKt;
import kotlinx.serialization.json.Json;

final class NativeCli {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && args[0].equals("--responses-probe")) {
            var settings = new OpenAIClientSettings(args[1], new ConnectionTimeoutConfig(15000, 5000, 15000), "v1/chat/completions", "v1/responses", "v1/embeddings", "v1/moderations", "v1/models");
            var client = new OpenAILLMClient("stub-key", settings, new KtorKoogHttpClient.Factory());
            try {
                var prompt = Prompt.builder("native-probe").user("Say hello").build();
                var model = new LLModel(LLMProvider.OpenAI, "stub", List.of(LLMCapability.OpenAIEndpoint.Responses.INSTANCE));
                var flow = client.executeStreaming(prompt, model, List.of());
                List<? extends StreamFrame> frames = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> FlowKt.toList(flow, new ArrayList<StreamFrame>(), continuation));
                System.out.println(frames);
                if (!frames.toString().contains("SPIKE_RESPONSES_REPLY")) throw new AssertionError("Responses text missing");
            } finally { client.close(); }
            return;
        }
        if (args.length == 1 && args[0].equals("--serialization-probe")) {
            if (PlatformKt.constructSerializerForGivenTypeArgs(Item.InputMessage.class) == null) throw new AssertionError("InputMessage serializer missing");
            for (String name : List.of(
                "ai.koog.prompt.executor.clients.anthropic.models.AnthropicContent$Text",
                "ai.koog.prompt.executor.ollama.client.dto.OllamaChatRequestDTO",
                "com.agentclientprotocol.model.InitializeRequest",
                "io.modelcontextprotocol.kotlin.sdk.types.InitializeRequest",
                "kinuvo.protocol.browser.BrowserOwner")) {
                if (PlatformKt.constructSerializerForGivenTypeArgs(Class.forName(name)) == null) throw new AssertionError("Serializer missing: " + name);
                System.out.println("SERIALIZER_READY " + name);
            }
            var serializer = OpenAIResponsesAPIRequest.Companion.serializer();
            var request = Json.Default.decodeFromString(serializer, "{\"model\":\"stub\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hello\"}]}]}");
            System.out.println(Json.Default.encodeToString(serializer, request));
            return;
        }
        if (args.length == 1 && args[0].equals("--browser-probe")) {
            File home = new File(System.getenv("KINUVO_HOME"));
            home.mkdirs();
            var runtime = new BrowserRuntime(home);
            var command = Json.Default.decodeFromString(BrowserCommand.Companion.serializer(), "{\"owner\":{\"agentId\":\"native-probe\"},\"action\":\"Open\",\"text\":\"about:blank\"}");
            try {
                BrowserFrame frame = BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> runtime.command(command, false, continuation));
                if (frame == null || frame.getScreenshot() == null) throw new AssertionError("Browser frame missing");
                System.out.println("BROWSER_READY url=" + frame.getTab().getUrl() + " screenshot_chars=" + frame.getScreenshot().length());
            } finally {
                BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (scope, continuation) -> runtime.close(continuation));
            }
            return;
        }
        if (args.length == 1 && args[0].equals("--apple-status")) {
            System.out.println(NativeAppleKt.loadAppleSystem().call("status", "{}"));
        } else {
            MainKt.main(args);
        }
    }
}

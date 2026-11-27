package com.mediflow.agent.agent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.model.DecisionModel;
import com.mediflow.agent.agent.tool.ReadToolSpecs;
import dev.ai4j.openai4j.OpenAiHttpException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
@Profile("mysql")
public final class AgentModelClients {
    private final ObjectMapper json;
    private final Clock clock;
    private final String initialKey;
    private final String initialUrl;
    private final String initialModel;
    private final java.util.Set<String> allowedUrls;

    public AgentModelClients(ObjectMapper json, Clock clock,
            @Value("${mediflow.llm.api-key:}") String initialKey,
            @Value("${mediflow.llm.base-url:https://api.deepseek.com/v1}") String initialUrl,
            @Value("${mediflow.llm.model:deepseek-chat}") String initialModel,
            @Value("${mediflow.llm.allowed-base-urls:}") String allowedUrls) {
        this.json = json;
        this.clock = clock;
        this.initialKey = initialKey;
        this.initialUrl = initialUrl;
        this.initialModel = initialModel;
        this.allowedUrls = Arrays.stream(allowedUrls.split(",")).map(String::strip).filter(url -> !url.isEmpty())
                .map(AgentModelClients::canonicalUrl).collect(Collectors.toUnmodifiableSet());
    }

    public boolean demo() { return initialKey.isBlank(); }
    public String initialUrl() { return initialUrl; }
    public String initialModel() { return initialModel; }
    public String initialIdentity() { return initialUrl + "\n" + initialModel + "\n" + initialKey; }

    public String managedUrl(String input) {
        String url = canonicalUrl(input);
        URI uri = URI.create(url);
        // A local substitute is only eligible when explicitly configured at deployment.
        if (url.equals(canonicalUrl(initialUrl))
                && ("https".equals(uri.getScheme()) || List.of("127.0.0.1", "localhost").contains(uri.getHost()))) {
            return url;
        }
        if (!"https".equals(uri.getScheme()) || !allowedUrls.contains(url)) {
            throw new IllegalArgumentException("此接口地址未获部署配置授权。请联系部署人员添加该 HTTPS 地址。");
        }
        return url;
    }

    private static String canonicalUrl(String input) {
        try {
            URI uri = URI.create(input.strip()).normalize();
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toASCIIString().replaceAll("/+$", "");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("请输入有效的模型接口基础地址，地址中不能包含凭证、查询参数或片段。");
        }
    }

    public DecisionModel initial(long timeoutMs) {
        return demo() ? new DemoDecisionModel() : managed(initialUrl, initialModel, initialKey, timeoutMs);
    }

    public DecisionModel managed(String url, String name, String key, long timeoutMs) {
        return new DeepSeekDecisionModel(client(url, name, key, timeoutMs), json, clock);
    }

    private OpenAiChatModel client(String url, String name, String key, long timeoutMs) {
        return OpenAiChatModel.builder().apiKey(key).baseUrl(url).modelName(name)
                .temperature(0.1).maxTokens(1200).timeout(Duration.ofMillis(timeoutMs))
                .maxRetries(DeepSeekDecisionModel.SDK_MAX_RETRIES).strictTools(false)
                .logRequests(false).logResponses(false).build();
    }

    public Probe probe(String url, String name, String key) {
        long started = System.nanoTime();
        String code = "REQUEST_FAILED";
        try {
            var client = client(url, name, key, 10_000);
            var basic = client.generate(List.of(UserMessage.from(
                    "这是合成接口测试。请只回复 JSON 对象：{\"type\":\"REPLY\",\"message\":\"测试通过\"}。"))).content();
            code = "INVALID_DECISION";
            Map<String, Object> decision = json.readValue(basic.text(), new TypeReference<>() {});
            if ("REPLY".equals(decision.get("type")) && decision.get("message") instanceof String message
                    && !message.isBlank() && !basic.hasToolExecutionRequests()) {
                code = "REQUEST_FAILED";
                var tool = client.generate(List.of(UserMessage.from(
                        "这是合成工具测试。请调用 findAvailableSlots，departmentName 填写 测试科室。")),
                        DeepSeekDecisionModel.specification(ReadToolSpecs.FIND_AVAILABLE_SLOTS)).content();
                code = "INVALID_TOOL_CALL";
                if (tool.hasToolExecutionRequests() && tool.toolExecutionRequests().size() == 1) {
                    var call = tool.toolExecutionRequests().get(0);
                    Map<String, Object> arguments = json.readValue(call.arguments(), new TypeReference<>() {});
                    if ("findAvailableSlots".equals(call.name()) && "测试科室".equals(arguments.get("departmentName"))) {
                        code = "PASSED";
                    }
                }
            }
        } catch (Exception failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof OpenAiHttpException http) {
                    code = switch (http.code()) {
                        case 401, 403 -> "AUTH_REJECTED";
                        case 402, 429 -> "QUOTA_OR_LIMIT";
                        default -> "PROVIDER_REJECTED";
                    };
                    break;
                }
                if (cause instanceof java.net.SocketTimeoutException || cause instanceof java.net.http.HttpTimeoutException) {
                    code = "TIMEOUT";
                    break;
                }
                if (cause.getCause() == cause) break;
            }
        }
        return new Probe(code, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), clock.instant());
    }

    public record Probe(String code, long durationMs, Instant testedAt) {}
}

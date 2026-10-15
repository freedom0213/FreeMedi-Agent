package com.mediflow.agent.config;

import java.time.Clock;

import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.AgentRuntimeService;
import com.mediflow.agent.agent.AppointmentApplicationService;
import com.mediflow.agent.agent.DecisionModel;
import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.StreamingReplyService;
import com.mediflow.agent.agent.TaskStateStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@Profile("mysql")
public class AgentRuntimeConfiguration {
    @Bean
    DecisionModel decisionModel(
            ObjectMapper objectMapper, Clock agentClock,
            @Value("${mediflow.llm.api-key:}") String apiKey,
            @Value("${mediflow.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${mediflow.llm.model:deepseek-chat}") String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DemoDecisionModel();
        }
        ChatLanguageModel model = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(1200)
                .strictTools(false)
                .logRequests(false)
                .logResponses(false)
                .build();
        return new DeepSeekDecisionModel(model, objectMapper, agentClock);
    }

    @Bean
    StreamingReplyService streamingReplyService(
            @Value("${mediflow.llm.api-key:}") String apiKey,
            @Value("${mediflow.llm.base-url:https://api.deepseek.com/v1}") String baseUrl,
            @Value("${mediflow.llm.model:deepseek-chat}") String modelName) {
        if (apiKey == null || apiKey.isBlank()) {
            return new DemoStreamingReplyService();
        }
        return new DeepSeekStreamingReplyService(OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(0.1)
                .maxTokens(800)
                .logRequests(false)
                .logResponses(false)
                .build());
    }

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
    }

    @Bean(name = "applicationTaskExecutor")
    Executor applicationTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("agent-stream-");
        executor.initialize();
        return executor;
    }

    @Bean
    AgentRuntime agentRuntime(TaskStateStore stateStore, DecisionModel decisionModel,
                             ReadToolExecutor toolExecutor,
                             AppointmentApplicationService appointmentService,
                             Clock agentClock,
                             @Value("${mediflow.agent.max-steps:8}") int maxSteps) {
        return new AgentRuntimeService(stateStore, decisionModel, toolExecutor,
                appointmentService, maxSteps, agentClock);
    }
}

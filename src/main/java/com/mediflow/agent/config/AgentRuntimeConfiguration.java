package com.mediflow.agent.config;

import java.time.Clock;
import java.util.List;

import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.AgentRuntimeService;
import com.mediflow.agent.agent.AgentCallPolicy;
import com.mediflow.agent.agent.AppointmentApplicationService;
import com.mediflow.agent.agent.AppointmentOptions;
import com.mediflow.agent.agent.CompositeReadToolExecutor;
import com.mediflow.agent.agent.DepartmentCatalog;
import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.TaskStateStore;
import com.mediflow.agent.appointment.MySqlReadToolExecutor;
import com.mediflow.agent.knowledge.KnowledgeReadToolExecutor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
@Profile("mysql")
public class AgentRuntimeConfiguration {
    @Bean
    ThreadPoolTaskExecutor agentCallExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        // Bounded, with no queue of abandoned requests when a downstream ignores interruption.
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("agent-call-");
        return executor;
    }

    @Bean
    AgentCallPolicy agentCallPolicy(ThreadPoolTaskExecutor agentCallExecutor,
            @Value("${mediflow.agent.model-timeout-ms:15000}") long modelMs,
            @Value("${mediflow.agent.tool-timeout-ms:5000}") long toolMs,
            @Value("${mediflow.agent.request-timeout-ms:45000}") long requestMs) {
        return new AgentCallPolicy(agentCallExecutor, modelMs, toolMs, requestMs);
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

    /**
     * The single read-tool entry point the runtime sees. Each executor keeps its own subject and
     * dependencies, so the appointment tools never have to know about hospital material, or the other
     * way round; the model simply gets one combined tool list.
     */
    @Bean
    @Primary
    ReadToolExecutor readToolExecutor(MySqlReadToolExecutor appointmentTools,
                                     KnowledgeReadToolExecutor knowledgeTools) {
        return new CompositeReadToolExecutor(List.of(appointmentTools, knowledgeTools));
    }

    @Bean
    AgentRuntime agentRuntime(TaskStateStore stateStore, AgentSettings settings,
                             ReadToolExecutor toolExecutor,
                             AppointmentApplicationService appointmentService,
                             DepartmentCatalog departmentCatalog,
                             AppointmentOptions appointmentOptions,
                             Clock agentClock,
                             @Value("${mediflow.agent.max-steps:8}") int maxSteps) {
        return new AgentRuntimeService(stateStore, toolExecutor,
                appointmentService, maxSteps, agentClock, departmentCatalog, appointmentOptions, settings::snapshot);
    }
}

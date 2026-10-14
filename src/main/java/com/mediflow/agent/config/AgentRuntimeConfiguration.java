package com.mediflow.agent.config;

import java.time.Clock;

import com.mediflow.agent.agent.AgentRuntime;
import com.mediflow.agent.agent.AgentRuntimeService;
import com.mediflow.agent.agent.AppointmentApplicationService;
import com.mediflow.agent.agent.DecisionModel;
import com.mediflow.agent.agent.ReadToolExecutor;
import com.mediflow.agent.agent.TaskStateStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("mysql")
public class AgentRuntimeConfiguration {
    @Bean
    @ConditionalOnMissingBean(DecisionModel.class)
    DemoDecisionModel demoDecisionModel() {
        return new DemoDecisionModel();
    }

    @Bean
    Clock agentClock() {
        return Clock.systemUTC();
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

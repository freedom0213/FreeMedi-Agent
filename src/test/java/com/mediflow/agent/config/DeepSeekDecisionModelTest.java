package com.mediflow.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.PlanDraft;
import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.agent.ReadToolSpec;
import com.mediflow.agent.agent.TaskContext;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.TaskStatus;
import com.mediflow.agent.agent.TaskSnapshot;
import com.mediflow.agent.agent.ToolCall;
import com.mediflow.agent.agent.ToolResult;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

class DeepSeekDecisionModelTest {
    private final ChatLanguageModel chatModel = mock(ChatLanguageModel.class);
    private final DeepSeekDecisionModel model = new DeepSeekDecisionModel(chatModel, new ObjectMapper());
    private final List<ReadToolSpec> tools = List.of(
            new ReadToolSpec("findAvailableSlots", "查询可用号源"),
            new ReadToolSpec("getAppointmentRules", "查询预约规则"));

    @Test
    void mapsFunctionCallToAllowedRuntimeToolCall() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("findAvailableSlots")
                .arguments("{\"departmentId\":2002,\"clinicPeriod\":\"AFTERNOON\"}")
                .build();
        when(chatModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from(request)));

        AgentDecision decision = model.decide(context("我想约呼吸科下午"), tools);

        assertThat(decision).isInstanceOf(AgentDecision.CallReadTool.class);
        ToolCall call = ((AgentDecision.CallReadTool) decision).call();
        assertThat(call.name()).isEqualTo("findAvailableSlots");
        assertThat(call.arguments()).containsEntry("departmentId", 2002)
                .containsEntry("clinicPeriod", "AFTERNOON");
        verify(chatModel).generate(anyList(), org.mockito.ArgumentMatchers.<List<ToolSpecification>>argThat(specs ->
                specs.size() == 2 && specs.stream().anyMatch(spec ->
                        spec.name().equals("findAvailableSlots") && spec.parameters() != null
                                && spec.parameters().properties().containsKey("departmentName"))));
    }

    @Test
    void parsesQuestionDecisionFromConstrainedJson() {
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("""
                {"type":"ASK_USER","question":"你想预约哪一天？","missingFields":["appointmentDate"]}
                """)));

        AgentDecision decision = model.decide(context("预约心内科"), tools);

        assertThat(decision).isEqualTo(new AgentDecision.AskUser("你想预约哪一天？", java.util.Set.of("appointmentDate")));
    }

    @Test
    void sequentialRuntimeCanStartWithOneOfMultipleReadOnlyCalls() {
        var slots = ToolExecutionRequest.builder().id("slots").name("findAvailableSlots")
                .arguments("{\"departmentName\":\"呼吸内科\"}").build();
        var rules = ToolExecutionRequest.builder().id("rules").name("getAppointmentRules")
                .arguments("{}").build();
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from(slots, rules)));

        assertThat(model.decide(context("帮我最近时间预约一个呼吸内科"), tools))
                .isInstanceOf(AgentDecision.CallReadTool.class);
    }

    @Test
    void asksModelOnceToRepairInvalidDecisionInsteadOfImmediatelyEndingConversation() {
        when(chatModel.generate(anyList(), anyList()))
                .thenReturn(Response.from(AiMessage.from("您哪里不舒服？")))
                .thenReturn(Response.from(AiMessage.from("""
                        {"type":"ASK_USER","question":"您哪里不舒服？","missingFields":[]}
                        """)));

        assertThat(model.decide(context("我想看病"), tools)).isInstanceOf(AgentDecision.AskUser.class);
        org.mockito.Mockito.verify(chatModel, org.mockito.Mockito.times(2)).generate(anyList(), anyList());
    }

    @Test
    void earliestRequestDoesNotPauseForAnOptionalDoctorPreference() {
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("""
                {"type":"ASK_USER","question":"请问您有想看的医生吗？","missingFields":["doctorName"]}
                """)));
        var request = ToolExecutionRequest.builder().id("nearest").name("findAvailableSlots")
                .arguments("{\"departmentName\":\"呼吸内科\"}").build();
        when(chatModel.generate(anyList(), org.mockito.ArgumentMatchers.any(ToolSpecification.class)))
                .thenReturn(Response.from(AiMessage.from(request)));

        var decision = model.decide(context("帮我最近时间预约一个呼吸内科的号"), tools);

        assertThat(decision).isInstanceOf(AgentDecision.CallReadTool.class);
        verify(chatModel).generate(anyList(), org.mockito.ArgumentMatchers.<ToolSpecification>argThat(
                specification -> specification.name().equals("findAvailableSlots")));
    }

    @Test
    void earliestRequestDoesNotRestartSymptomIntakeWhenDepartmentIsExplicit() {
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("""
                {"type":"ASK_USER","question":"您最近有咳嗽、咽痛这类不舒服吗？","missingFields":[]}
                """)));
        var request = ToolExecutionRequest.builder().id("nearest").name("findAvailableSlots")
                .arguments("{\"departmentName\":\"呼吸内科\"}").build();
        when(chatModel.generate(anyList(), org.mockito.ArgumentMatchers.any(ToolSpecification.class)))
                .thenReturn(Response.from(AiMessage.from(request)));

        var decision = model.decide(context("帮我最近时间预约一个呼吸内科的号"), tools);

        assertThat(decision).isInstanceOf(AgentDecision.CallReadTool.class);
        verify(chatModel).generate(anyList(), org.mockito.ArgumentMatchers.<ToolSpecification>argThat(
                specification -> specification.name().equals("findAvailableSlots")));
    }

    @Test
    void injectsPatientAndExpiryWhenParsingPlanDecision() {
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("""
                {"type":"PROPOSE_PLAN","departmentId":2002,"doctorId":3003,"slotId":5005,
                 "appointmentDate":"2027-01-16","timeRange":"09:00-09:30","message":"请确认方案"}
                """)));

        var base = context("帮我预约");
        AgentDecision decision = model.decide(new DecisionContext(base.task(), base.userMessage(),
                List.of(ToolResult.success(Map.of("slots", List.of(Map.of(
                        "departmentId", 2002, "doctorId", 3003, "slotId", 5005,
                        "appointmentDate", "2027-01-16", "timeRange", "09:00-09:30")))))), tools);

        PlanDraft plan = ((AgentDecision.ProposePlan) decision).draft();
        assertThat(plan.patientId()).isEqualTo(1001L);
        assertThat(plan.departmentId()).isEqualTo(2002L);
        assertThat(plan.doctorId()).isEqualTo(3003L);
        assertThat(plan.slotId()).isEqualTo(5005L);
        assertThat(plan.expiresAt()).isAfter(Instant.now());
    }

    @Test
    void refusesInventedSlotEvenWhenDecisionJsonIsValid() {
        when(chatModel.generate(anyList(), anyList())).thenReturn(Response.from(AiMessage.from("""
                {"type":"PROPOSE_PLAN","slotId":999999,"message":"请确认方案"}
                """)));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> model.decide(context("预约"), tools))
                .isInstanceOf(com.mediflow.agent.agent.ModelDecisionException.class)
                .hasMessage("UNSOURCED_SLOT");
    }

    private DecisionContext context(String message) {
        TaskContext task = new TaskContext(new TaskId("test-task"), 1001L);
        TaskSnapshot snapshot = task.snapshot();
        return new DecisionContext(snapshot, message, List.of());
    }
}

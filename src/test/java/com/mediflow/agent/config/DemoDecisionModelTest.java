package com.mediflow.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.mediflow.agent.agent.AgentDecision;
import com.mediflow.agent.agent.DecisionContext;
import com.mediflow.agent.agent.TaskContext;
import com.mediflow.agent.agent.TaskId;
import com.mediflow.agent.agent.ToolResult;

import org.junit.jupiter.api.Test;

class DemoDecisionModelTest {
    private final DemoDecisionModel model = new DemoDecisionModel();

    @Test
    void invitesPatientToDescribeSymptomsForGreeting() {
        AgentDecision decision = model.decide(context("你好", List.of()), List.of());

        assertThat(decision).isInstanceOf(AgentDecision.AskUser.class);
        assertThat(((AgentDecision.AskUser) decision).question()).contains("哪里不舒服");
    }

    @Test
    void selectsRequestedDepartmentAndClinicPeriodFromSyntheticSlots() {
        DecisionContext context = context("帮我预约呼吸内科，优先下午", slots());

        AgentDecision decision = model.decide(context, List.of());

        assertThat(decision).isInstanceOf(AgentDecision.ProposePlan.class);
        var plan = ((AgentDecision.ProposePlan) decision).draft();
        assertThat(plan.departmentId()).isEqualTo(2002L);
        assertThat(plan.doctorId()).isEqualTo(3003L);
        assertThat(plan.slotId()).isEqualTo(5007L);
        assertThat(plan.appointmentDate()).isEqualTo(LocalDate.of(2027, 1, 16));
    }

    @Test
    void explainsAlternativeTimeWhenRequestedPeriodHasNoSlot() {
        ToolResult result = ToolResult.success(Map.of("slots", List.of(slot(
                5005, 2002, "呼吸内科", 3003, "MORNING", "09:00-09:30"))));

        AgentDecision decision = model.decide(context("预约呼吸内科下午", List.of(result)), List.of());

        assertThat(decision).isInstanceOf(AgentDecision.ProposePlan.class);
        assertThat(((AgentDecision.ProposePlan) decision).message()).contains("替代时间");
    }

    @Test
    void suggestsDepartmentFromSymptomsBeforeQueryingAndRetainsItWhenPatientAgrees() {
        TaskContext task = new TaskContext(new TaskId("symptom-demo"), 1001L);
        String symptom = "我老是咳嗽一周了";
        task.beginTurn(symptom);
        var question = (AgentDecision.AskUser) model.decide(new DecisionContext(task.snapshot(), symptom, List.of()), List.of());
        assertThat(question.question()).contains("呼吸内科").contains("不是诊断");
        task.completeTurn(question.question());

        var decision = model.decide(new DecisionContext(task.snapshot(), "可以，2027年1月16号下午", List.of()), List.of());
        assertThat(decision).isInstanceOf(AgentDecision.CallReadTool.class);
        assertThat(((AgentDecision.CallReadTool) decision).call().arguments())
                .containsEntry("departmentName", "呼吸内科").containsEntry("appointmentDate", "2027-01-16")
                .containsEntry("clinicPeriod", "AFTERNOON");
    }

    @Test
    void directTriageUsesAvailableHeadacheContextWithoutRequiringFullIntake() {
        TaskContext task = new TaskContext(new TaskId("headache-triage"), 1001L);
        task.beginTurn("我头疼");
        task.completeTurn("头疼确实难受。持续多久了？");

        AgentDecision decision = model.decide(new DecisionContext(task.snapshot(),
                "我现在比较着急，请根据目前描述的情况先给我一个就诊方向建议和原因。",
                List.of()), List.of());

        assertThat(decision).isInstanceOf(AgentDecision.AskUser.class);
        assertThat(((AgentDecision.AskUser) decision).question())
                .contains("神经内科", "初步就诊方向", "查找近期号源");
    }

    @Test
    void asksForAppointmentDetailsInsteadOfProposingAPlanForGreeting() {
        DecisionContext context = context("你好", slots());

        AgentDecision decision = model.decide(context, List.of());

        assertThat(decision).isInstanceOf(AgentDecision.AskUser.class);
        assertThat(((AgentDecision.AskUser) decision).question()).contains("科室");
    }

    private static DecisionContext context(String message, List<ToolResult> results) {
        var task = new TaskContext(new TaskId("demo-task"), 1001L).snapshot();
        return new DecisionContext(task, message, results);
    }

    private static List<ToolResult> slots() {
        return List.of(ToolResult.success(Map.of("slots", List.of(
                slot(5001, 2001, "心内科", 3001, "AFTERNOON", "14:00-14:30"),
                slot(5005, 2002, "呼吸内科", 3003, "MORNING", "09:00-09:30"),
                slot(5007, 2002, "呼吸内科", 3003, "AFTERNOON", "14:00-14:30")))));
    }

    private static Map<String, Object> slot(long slotId, long departmentId, String departmentName,
                                            long doctorId, String clinicPeriod, String timeRange) {
        return Map.of(
                "slotId", slotId,
                "departmentId", departmentId,
                "departmentName", departmentName,
                "doctorId", doctorId,
                "appointmentDate", "2027-01-16",
                "clinicPeriod", clinicPeriod,
                "timeRange", timeRange);
    }
}

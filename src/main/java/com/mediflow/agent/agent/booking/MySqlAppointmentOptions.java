package com.mediflow.agent.agent.booking;

import com.mediflow.agent.agent.tool.MySqlReadToolExecutor;
import com.mediflow.agent.service.appointment.policy.AppointmentDateParser;
import com.mediflow.agent.service.appointment.query.AvailableSlot;
import com.mediflow.agent.service.appointment.query.MySqlHospitalDirectory;
import com.mediflow.agent.service.appointment.query.SlotCriteria;
import com.mediflow.agent.service.appointment.query.SlotQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.mediflow.agent.agent.booking.AppointmentOptions;
import com.mediflow.agent.agent.booking.PlanDraft;
import com.mediflow.agent.service.appointment.query.MySqlHospitalDirectory.DepartmentView;
import com.mediflow.agent.service.appointment.query.MySqlHospitalDirectory.DoctorView;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("mysql")
public final class MySqlAppointmentOptions implements AppointmentOptions {
    private static final int LIMIT = 3;
    private final MySqlHospitalDirectory directory;
    private final SlotQueryService slots;

    public MySqlAppointmentOptions(MySqlHospitalDirectory directory, SlotQueryService slots) {
        this.directory = directory;
        this.slots = slots;
    }

    @Override
    public Search search(Map<String, Object> saved, Map<String, Object> changes, String source) {
        var departments = directory.listActiveDepartments();
        Long savedDepartment = id(saved.get("departmentId"));
        Long departmentId = changed(changes, "department")
                ? resolveDepartment(departments, changes) : savedDepartment;
        Long doctorId = changed(changes, "doctor") ? id(changes.get("doctorId"))
                : Objects.equals(departmentId, savedDepartment) ? id(saved.get("doctorId")) : null;
        String doctorName = changed(changes, "doctor") ? text(changes.get("doctorName")) : null;
        DoctorView doctor = resolveDoctor(departments, departmentId, doctorId, doctorName);
        if (doctor != null) departmentId = doctor.departmentId();
        DepartmentView department = department(departments, departmentId);

        LocalDate today = slots.today();
        var date = AppointmentDateParser.parse(text(changes.containsKey("appointmentDate")
                ? changes.get("appointmentDate") : saved.get("appointmentDate")), today);
        if (date != null && (date.isBefore(today) || date.isAfter(slots.patientWindowEnd(today)))) {
            throw new IllegalArgumentException("当前可查询至 " + slots.patientWindowEnd(today) + "，请在范围内选择日期。");
        }
        String period = SlotQueryService.normalisePeriod(text(changes.containsKey("clinicPeriod")
                ? changes.get("clinicPeriod") : saved.get("clinicPeriod")));
        boolean dateFlexible = date != null && flexibility(saved, changes, "appointmentDate", "dateFlexible", date.toString());
        boolean periodFlexible = period != null && flexibility(saved, changes, "clinicPeriod", "periodFlexible", period);
        Map<String, Object> requirements = requirements(saved, source, department, doctor, date, period,
                dateFlexible, periodFlexible);
        if (department == null) return new Search(requirements, List.of(), "已保存需求。请先选择科室或医生，再查找号源。");

        String previousDoctor = doctor == null && changed(changes, "doctor") && Objects.equals(departmentId, savedDepartment)
                ? text(saved.get("doctorName")) : null;
        var criteria = new SlotCriteria(department.id(), null, doctor == null ? null : doctor.id(),
                null, date, period, LIMIT);
        List<AvailableSlot> results = slots.searchStrict(criteria).slots();
        if (results.isEmpty() && dateFlexible) results = relaxed(criteria, null, period);
        if (results.isEmpty() && periodFlexible) results = relaxed(criteria, date, null);
        if (results.isEmpty() && dateFlexible && periodFlexible) results = relaxed(criteria, null, null);
        if (results.isEmpty()) {
            return new Search(requirements, List.of(), "暂时没有满足这些条件的可用号源。你可以修改条件或稍后再查。");
        }
        List<Map<String, Object>> candidates = results.stream().map(slot -> {
            var row = MySqlReadToolExecutor.toolPayload(slot);
            List<String> differences = new ArrayList<>();
            if (previousDoctor != null && !previousDoctor.equals(slot.doctorName())) differences.add("备选医生");
            if (date != null && !date.equals(slot.appointmentDate())) differences.add("日期不同");
            if (period != null && !period.equals(slot.clinicPeriod())) differences.add("时段不同");
            row.put("differences", List.copyOf(differences));
            return java.util.Collections.unmodifiableMap(row);
        }).toList();
        String message = previousDoctor == null ? "已找到可用号源。请比较后选择一个，再核对并确认预约。"
                : "原先指定的医生是「" + previousDoctor + "」。以下是放宽医生条件后的同科室号源，请选择后核对确认。";
        return new Search(requirements, candidates, message);
    }

    private List<AvailableSlot> relaxed(SlotCriteria base, LocalDate date, String period) {
        return slots.searchStrict(new SlotCriteria(base.departmentId(), null, base.doctorId(), null,
                date, period, LIMIT)).slots();
    }

    @Override
    public PlanDraft draft(long patientId, Map<?, ?> candidate, Instant expiresAt) {
        var slot = slots.findAvailable(id(candidate.get("slotId"))).orElseThrow(
                () -> new IllegalArgumentException("这个号源已不可用，请重新查询。"));
        Map<String, Object> current = MySqlReadToolExecutor.toolPayload(slot);
        for (String field : List.of("departmentId", "doctorId", "appointmentDate", "clinicPeriod", "timeRange")) {
            if (!Objects.equals(String.valueOf(candidate.get(field)), String.valueOf(current.get(field)))) {
                throw new IllegalArgumentException("这个号源的信息已变更，请重新查询。");
            }
        }
        return new PlanDraft(patientId, slot.departmentId(), slot.doctorId(), slot.slotId(),
                slot.appointmentDate(), slot.timeRange(), expiresAt);
    }

    private Long resolveDepartment(List<DepartmentView> departments, Map<String, Object> changes) {
        Long id = id(changes.get("departmentId"));
        if (id != null) return department(departments, id).id();
        String name = text(changes.get("departmentName"));
        if (name == null) return null;
        String canonical = switch (name) {
            case "呼吸科" -> "呼吸内科";
            case "心血管内科", "心脏内科" -> "心内科";
            case "全科" -> "全科医学科";
            default -> name;
        };
        long found = directory.findActiveDepartmentId(canonical);
        if (found == 0) throw new IllegalArgumentException("本院目录中未找到该科室，请从科室列表选择。");
        return found;
    }

    private static DepartmentView department(List<DepartmentView> departments, Long id) {
        if (id == null) return null;
        return departments.stream().filter(item -> item.id() == id).findFirst().orElseThrow(
                () -> new IllegalArgumentException("该科室目前不可选，请重新选择。"));
    }

    private DoctorView resolveDoctor(List<DepartmentView> departments, Long departmentId, Long id, String name) {
        if (id == null && name == null) return null;
        var matches = departments.stream().filter(item -> departmentId == null || item.id() == departmentId)
                .flatMap(item -> directory.listActiveDoctors(item.id()).stream())
                .filter(item -> id != null ? item.id() == id : name.equals(item.name())).limit(2).toList();
        if (matches.isEmpty()) throw new IllegalArgumentException("未找到该医生，请核对姓名或所属科室。");
        if (matches.size() != 1) throw new IllegalArgumentException("有同名医生，请先指定科室。");
        return matches.get(0);
    }

    private static boolean flexibility(Map<String, Object> saved, Map<String, Object> changes,
                                       String field, String flag, String value) {
        if (changes.containsKey(flag)) {
            if (changes.get(flag) instanceof Boolean allowed) return allowed;
            throw new IllegalArgumentException("时间条件的放宽标记必须为布尔值。");
        }
        return value.equals(saved.get(field)) && Boolean.TRUE.equals(saved.get(flag));
    }

    private static Map<String, Object> requirements(Map<String, Object> saved, String source, DepartmentView department,
            DoctorView doctor, LocalDate date, String period, boolean dateFlexible, boolean periodFlexible) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (department != null) {
            values.put("departmentId", department.id());
            values.put("departmentName", department.name());
        }
        if (doctor != null) {
            values.put("doctorId", doctor.id());
            values.put("doctorName", doctor.name());
        }
        if (date != null) {
            values.put("appointmentDate", date.toString());
            values.put("dateFlexible", dateFlexible);
        }
        if (period != null) {
            values.put("clinicPeriod", period);
            values.put("periodFlexible", periodFlexible);
        }
        Map<?, ?> priorSources = saved.get("sources") instanceof Map<?, ?> map ? map : Map.of();
        Map<String, String> sources = new LinkedHashMap<>();
        for (String field : List.of("departmentId", "doctorId", "appointmentDate", "clinicPeriod")) {
            if (values.containsKey(field)) {
                boolean same = Objects.equals(String.valueOf(values.get(field)), String.valueOf(saved.get(field)))
                        && (field.equals("appointmentDate") ? Objects.equals(values.get("dateFlexible"), saved.get("dateFlexible"))
                        : !field.equals("clinicPeriod") || Objects.equals(values.get("periodFlexible"), saved.get("periodFlexible")));
                sources.put(field, same && priorSources.get(field) instanceof String previous ? previous : source);
            }
        }
        values.put("sources", Map.copyOf(sources));
        return Map.copyOf(values);
    }

    private static boolean changed(Map<String, Object> patch, String field) {
        return patch.containsKey(field + "Id") || patch.containsKey(field + "Name");
    }

    private static Long id(Object value) {
        String text = text(value);
        if (text == null) return null;
        try {
            long parsed = Long.parseLong(text);
            if (parsed > 0) return parsed;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException("请选择有效的科室或医生。");
    }

    private static String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }
}

package com.mediflow.agent.api;

import java.time.LocalDate;
import java.util.List;

import com.mediflow.agent.appointment.AppointmentDateParser;
import com.mediflow.agent.appointment.AvailableSlot;
import com.mediflow.agent.appointment.HospitalRules;
import com.mediflow.agent.appointment.MySqlHospitalDirectory;
import com.mediflow.agent.appointment.SlotCriteria;
import com.mediflow.agent.appointment.SlotQueryService;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only catalog endpoints for ordinary registration, which never goes through the agent. */
@RestController
@RequestMapping("/api/v1")
@Profile("mysql")
public class PortalCatalogController {

    public record SlotSearchResponse(List<AvailableSlot> slots, int count, String searchEndDate,
                                     String searchStartDate, int page, int totalCount, int totalPages) {
    }

    private final MySqlHospitalDirectory directory;
    private final SlotQueryService slotQuery;
    private final HospitalRules rules;

    public PortalCatalogController(MySqlHospitalDirectory directory, SlotQueryService slotQuery, HospitalRules rules) {
        this.directory = directory;
        this.slotQuery = slotQuery;
        this.rules = rules;
    }

    @GetMapping("/hospital")
    public HospitalRules.PublicProfile hospital() { return rules.publicProfile(); }

    public record SearchWindow(LocalDate searchStartDate, LocalDate searchEndDate) {}

    @GetMapping("/slots/window")
    public SearchWindow searchWindow() {
        LocalDate today = slotQuery.today();
        return new SearchWindow(today, slotQuery.patientWindowEnd(today));
    }

    @GetMapping("/departments")
    public List<MySqlHospitalDirectory.DepartmentView> departments() {
        return directory.listActiveDepartments();
    }

    @GetMapping("/departments/{departmentId}/doctors")
    public List<MySqlHospitalDirectory.DoctorView> doctors(@PathVariable long departmentId) {
        return directory.listActiveDoctors(departmentId);
    }

    /**
     * Ordinary registration search. A missing date or period means "any"; a supplied one is only
     * ever matched exactly, so a patient who picked Tuesday morning is told there is nothing rather
     * than silently moved to another day.
     */
    @GetMapping("/slots")
    public SlotSearchResponse slots(@RequestParam(required = false) Long departmentId,
                                    @RequestParam(required = false) Long doctorId,
                                    @RequestParam(required = false) String date,
                                    @RequestParam(required = false) String period,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(defaultValue = "1") int page) {
        if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
        if (departmentId == null && doctorId == null) {
            throw new IllegalArgumentException("请先选择科室或医生");
        }
        LocalDate today = slotQuery.today();
        SlotCriteria criteria = new SlotCriteria(
                departmentId, null,
                doctorId, null,
                date == null || date.isBlank() ? null : AppointmentDateParser.parse(date.trim(), today),
                SlotQueryService.normalisePeriod(period),
                limit(limit));
        var search = slotQuery.searchPortal(criteria, page);
        return new SlotSearchResponse(search.slots(), search.slots().size(), search.endDate().toString(),
                search.startDate().toString(), search.page(), search.totalCount(), search.totalPages());
    }

    private static int limit(Integer limit) {
        if (limit == null) {
            return SlotQueryService.DEFAULT_LIMIT;
        }
        if (limit <= 0 || limit > SlotQueryService.MAX_LIMIT) {
            throw new IllegalArgumentException("limit 必须在 1 到 " + SlotQueryService.MAX_LIMIT + " 之间");
        }
        return limit;
    }
}

package com.mediflow.agent.api;

import com.mediflow.agent.appointment.HospitalCatalogImport;
import com.mediflow.agent.appointment.HospitalRules;
import com.mediflow.agent.config.AdminSecurityConfiguration.AdminAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.sql.Time;
import java.sql.Timestamp;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/setup")
public class AdminSetupController {
    private final JdbcTemplate jdbc;
    private final HospitalRules rules;
    private final HospitalCatalogImport catalog;
    private final Clock clock;

    public AdminSetupController(JdbcTemplate jdbc, HospitalRules rules, HospitalCatalogImport catalog,
                                Clock clock) {
        this.jdbc = jdbc;
        this.rules = rules;
        this.catalog = catalog;
        this.clock = clock;
    }

    public record SetupProfile(String name, String contact, Instant activatedAt) {}
    public record Checks(long departments, long doctors, long schedules) {}
    public record Setup(SetupProfile profile, HospitalRules.Rules rules, HospitalRules.Rules defaults, Checks checks,
                        String timezone, boolean required) {}
    public record Activate(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 100) String contact,
                           @Min(1) @Max(90) int scheduleDays, @Min(1) @Max(90) int patientDays,
                           @NotNull LocalTime cancellationCutoff, @Min(0) @Max(120) int checkInLead) {}
    public record Import(@NotNull HospitalCatalogImport.Kind kind, @NotBlank @Size(max = 128000) String csv,
                         String token) {}

    @GetMapping
    public Setup read() {
        SetupProfile profile = jdbc.queryForObject("SELECT hospital_name, contact, activated_at FROM hospital_settings WHERE id=1",
                (row, i) -> new SetupProfile(row.getString(1), row.getString(2),
                        row.getTimestamp(3) == null ? null : row.getTimestamp(3).toInstant()));
        var activeRules = rules.active();
        return new Setup(profile, activeRules, HospitalRules.DEFAULTS, checks(activeRules.patientDays()),
                HospitalRules.ZONE.getId(), !rules.bookingEnabled());
    }

    @GetMapping("/checks")
    public Checks previewChecks(@RequestParam @Min(1) @Max(90) int patientDays) {
        return checks(patientDays);
    }

    @PutMapping("/activate")
    @Transactional
    public Setup activate(@AuthenticationPrincipal AdminAccount operator, @Valid @RequestBody Activate input) {
        if (input.patientDays() > input.scheduleDays() + 1 || input.cancellationCutoff().getSecond() != 0
                || input.cancellationCutoff().getNano() != 0) {
            throw new IllegalArgumentException("患者查号范围应在排班范围内，截止时间填写到分钟。");
        }
        Checks checks = checks(input.patientDays());
        if (checks.departments() == 0 || checks.doctors() == 0 || checks.schedules() == 0) {
            throw new IllegalArgumentException("请先准备启用的科室、医生和有余号的开放排班。");
        }
        jdbc.update("""
                UPDATE hospital_settings SET hospital_name=?, contact=?, schedule_days=?, patient_days=?,
                    cancellation_cutoff=?, check_in_lead=?, activated_at=?, activated_by=? WHERE id=1
                """, input.name().strip(), input.contact().strip(), input.scheduleDays(), input.patientDays(),
                Time.valueOf(input.cancellationCutoff()), input.checkInLead(),
                Timestamp.from(clock.instant()), Long.parseLong(operator.adminId()));
        return read();
    }

    @PostMapping("/import/preview")
    public HospitalCatalogImport.Preview preview(@Valid @RequestBody Import input) {
        return catalog.preview(input.kind(), input.csv());
    }

    @PostMapping("/import/commit")
    public int commit(@Valid @RequestBody Import input) {
        return catalog.commit(input.kind(), input.csv(), input.token());
    }

    private Checks checks(int patientDays) {
        var now = clock.instant();
        return jdbc.queryForObject("""
                SELECT (SELECT COUNT(*) FROM department WHERE status='ACTIVE'),
                       (SELECT COUNT(*) FROM doctor d JOIN department dp ON dp.id=d.department_id
                         WHERE d.status='ACTIVE' AND dp.status='ACTIVE'),
                       (SELECT COUNT(*) FROM doctor_schedule sc JOIN doctor d ON d.id=sc.doctor_id
                         JOIN department dp ON dp.id=d.department_id
                         WHERE sc.status='OPEN' AND d.status='ACTIVE' AND dp.status='ACTIVE' AND sc.schedule_date BETWEEN ? AND ?
                         AND EXISTS(SELECT 1 FROM appointment_slot s WHERE s.schedule_id=sc.id
                             AND s.status='AVAILABLE' AND s.start_at > ?))
                """, (row, i) -> new Checks(row.getLong(1), row.getLong(2), row.getLong(3)),
                now.atZone(HospitalRules.ZONE).toLocalDate(), now.atZone(HospitalRules.ZONE).toLocalDate().plusDays(patientDays - 1L),
                Timestamp.from(now));
    }
}

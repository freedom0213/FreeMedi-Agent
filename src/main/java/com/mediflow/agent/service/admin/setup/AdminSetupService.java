package com.mediflow.agent.service.admin.setup;

import com.mediflow.agent.service.admin.catalog.HospitalCatalogImport;
import com.mediflow.agent.service.appointment.policy.HospitalRules;
import com.mediflow.agent.mapper.admin.setup.AdminSetupMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;

@Service
@Profile("mysql")
public class AdminSetupService {
    private final AdminSetupMapper mapper;
    private final HospitalRules rules;
    private final HospitalCatalogImport catalog;
    private final Clock clock;

    public AdminSetupService(AdminSetupMapper mapper, HospitalRules rules, HospitalCatalogImport catalog, Clock clock) {
        this.mapper = mapper;
        this.rules = rules;
        this.catalog = catalog;
        this.clock = clock;
    }

    public record SetupProfile(String name, String contact, Instant activatedAt) {}
    public record Checks(long departments, long doctors, long schedules) {}
    public record Setup(SetupProfile profile, HospitalRules.Rules rules, HospitalRules.Rules defaults, Checks checks,
                        String timezone, boolean required) {}
    public record Activation(String name, String contact, int scheduleDays, int patientDays,
                             LocalTime cancellationCutoff, int checkInLead) {}

    public Setup read() {
        var stored = mapper.readProfile();
        var profile = new SetupProfile(stored.name(), stored.contact(), stored.activatedAt());
        var activeRules = rules.active();
        return new Setup(profile, activeRules, HospitalRules.DEFAULTS, checks(activeRules.patientDays()),
                HospitalRules.ZONE.getId(), !rules.bookingEnabled());
    }

    @Transactional
    public Setup activate(long operatorId, Activation input) {
        if (input.patientDays() > input.scheduleDays() + 1 || input.cancellationCutoff().getSecond() != 0
                || input.cancellationCutoff().getNano() != 0) {
            throw new IllegalArgumentException("患者查号范围应在排班范围内，截止时间填写到分钟。");
        }
        Checks checks = checks(input.patientDays());
        if (checks.departments() == 0 || checks.doctors() == 0 || checks.schedules() == 0) {
            throw new IllegalArgumentException("请先准备启用的科室、医生和有余号的开放排班。");
        }
        mapper.activate(input.name().strip(), input.contact().strip(), input.scheduleDays(), input.patientDays(),
                input.cancellationCutoff(), input.checkInLead(), clock.instant(), operatorId);
        return read();
    }

    public HospitalCatalogImport.Preview previewImport(HospitalCatalogImport.Kind kind, String csv) {
        return catalog.preview(kind, csv);
    }

    public int commitImport(HospitalCatalogImport.Kind kind, String csv, String token) {
        return catalog.commit(kind, csv, token);
    }

    public Checks checks(int patientDays) {
        var now = clock.instant();
        var from = now.atZone(HospitalRules.ZONE).toLocalDate();
        var readiness = mapper.readiness(from, from.plusDays(patientDays - 1L), now);
        return new Checks(readiness.departments(), readiness.doctors(), readiness.schedules());
    }
}

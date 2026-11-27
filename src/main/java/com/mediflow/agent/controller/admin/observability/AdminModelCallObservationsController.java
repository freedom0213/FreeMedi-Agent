package com.mediflow.agent.controller.admin.observability;

import com.mediflow.agent.service.admin.observability.AdminModelCallObservations;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@Profile("mysql")
@RequestMapping("/api/v1/admin/observability/model-calls")
public class AdminModelCallObservationsController {
    private final AdminModelCallObservations observations;

    public AdminModelCallObservationsController(AdminModelCallObservations observations) {
        this.observations = observations;
    }

    @GetMapping
    public AdminModelCallObservations.Overview overview(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String model) {
        return observations.overview(from, to, model);
    }

    @GetMapping("/attempts")
    public AdminModelCallObservations.AttemptPage attempts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String model, @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String before) {
        return observations.attempts(from, to, model, outcome, before);
    }
}

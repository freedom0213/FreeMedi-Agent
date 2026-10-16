package com.mediflow.agent.api;

import java.util.List;

import com.mediflow.agent.agent.Principal;
import com.mediflow.agent.appointment.AppointmentQueryService;
import com.mediflow.agent.appointment.AppointmentQueryService.AppointmentView;
import com.mediflow.agent.appointment.AppointmentWriteService;
import com.mediflow.agent.appointment.AppointmentWriteService.BookingResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ordinary registration without the agent: book a chosen slot and read back your own appointments.
 *
 * <p>A booking conflict is a normal outcome of a busy clinic, so it is reported as HTTP 200 with a
 * business code in the body rather than as a transport error. The write itself always goes through
 * {@link AppointmentWriteService}, the same service the agent confirmation uses.
 */
@RestController
@RequestMapping("/api/v1/appointments")
@Profile("mysql")
public class PortalAppointmentController {

    /** {@code idempotencyKey} is capped at the column width so a client cannot cause a 500. */
    public record BookingRequest(@NotNull @Positive Long slotId,
                                 @NotBlank @Size(max = 64) String idempotencyKey) {
    }

    public record BookingResponse(boolean success, BookingResult.Outcome outcome,
                                  String appointmentNo, String code, String message) {

        static BookingResponse of(BookingResult result) {
            return new BookingResponse(result.success(), result.outcome(), result.appointmentNo(),
                    result.code(), result.message());
        }
    }

    private final AppointmentWriteService writeService;
    private final AppointmentQueryService queryService;
    private final DemoIdentityService identity;

    public PortalAppointmentController(AppointmentWriteService writeService,
                                       AppointmentQueryService queryService,
                                       DemoIdentityService identity) {
        this.writeService = writeService;
        this.queryService = queryService;
        this.identity = identity;
    }

    @PostMapping
    public BookingResponse book(@RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
                                @Valid @RequestBody BookingRequest request) {
        Principal principal = identity.resolve(userId);
        return BookingResponse.of(writeService.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(principal.userId(), principal.role()),
                request.idempotencyKey(), request.slotId())));
    }

    @GetMapping("/mine")
    public List<AppointmentView> mine(@RequestHeader(value = "X-Demo-User-Id", required = false) String userId,
                                      @RequestParam(required = false) Integer limit) {
        Principal principal = identity.resolve(userId);
        return queryService.listForPatient(principal.userId(),
                limit == null ? AppointmentQueryService.DEFAULT_LIMIT : limit);
    }

    /**
     * Someone else's appointment number is answered with 404 rather than 403: a patient should not be
     * able to learn whether a booking exists at all.
     */
    @GetMapping("/{appointmentNo}")
    public ResponseEntity<AppointmentView> detail(
            @PathVariable String appointmentNo,
            @RequestHeader(value = "X-Demo-User-Id", required = false) String userId) {
        Principal principal = identity.resolve(userId);
        return queryService.findForPatient(appointmentNo, principal.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}

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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
    private final MySqlTaskApplicationService tasks;

    public PortalAppointmentController(AppointmentWriteService writeService,
                                       AppointmentQueryService queryService, MySqlTaskApplicationService tasks) {
        this.writeService = writeService;
        this.queryService = queryService;
        this.tasks = tasks;
    }

    @PostMapping
    public BookingResponse book(@AuthenticationPrincipal(expression = "patient") Principal principal,
                                @Valid @RequestBody BookingRequest request) {
        return BookingResponse.of(writeService.book(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(principal.userId(), principal.role()),
                request.idempotencyKey(), request.slotId())));
    }

    @PostMapping("/status")
    public BookingResponse checkBooking(@AuthenticationPrincipal(expression = "patient") Principal principal,
                                       @Valid @RequestBody BookingRequest request) {
        return writeService.lookup(AppointmentWriteService.BookingCommand.forSlot(
                new AppointmentWriteService.Actor(principal.userId(), principal.role()),
                request.idempotencyKey(), request.slotId())).map(BookingResponse::of)
                .orElseGet(() -> new BookingResponse(false, null, null,
                        "NOT_FOUND", "暂未查到原请求的结果，可再次核验或重试同一次提交。"));
    }

    @GetMapping("/mine")
    public List<AppointmentView> mine(@AuthenticationPrincipal(expression = "patient") Principal principal,
                                      @RequestParam(required = false) Integer limit) {
        return queryService.listForPatient(principal.userId(),
                limit == null ? AppointmentQueryService.DEFAULT_LIMIT : limit);
    }

    @GetMapping("/mine/overview")
    public AppointmentQueryService.PatientOverview overview(
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @RequestParam(defaultValue = "1") int upcomingPage,
            @RequestParam(defaultValue = "1") int historyPage) {
        return queryService.patientOverview(principal.userId(), upcomingPage, historyPage);
    }

    /**
     * Someone else's appointment number is answered with 404 rather than 403: a patient should not be
     * able to learn whether a booking exists at all.
     */
    @GetMapping("/{appointmentNo}")
    public ResponseEntity<AppointmentView> detail(
            @PathVariable String appointmentNo,
            @AuthenticationPrincipal(expression = "patient") Principal principal) {
        return queryService.findForPatient(appointmentNo, principal.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{appointmentNo}/cancel")
    public ResponseEntity<AppointmentWriteService.LifecycleResult> cancel(
            @PathVariable String appointmentNo,
            @AuthenticationPrincipal(expression = "patient") Principal principal) {
        var result = writeService.cancel(appointmentNo, principal.userId());
        return ResponseEntity.status("NOT_FOUND".equals(result.code()) ? 404 : result.success() ? 200 : 409).body(result);
    }

    @PostMapping("/{appointmentNo}/reschedule")
    public ResponseEntity<BookingResponse> reschedule(
            @PathVariable String appointmentNo,
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @Valid @RequestBody BookingRequest request) {
        var result = writeService.reschedule(new AppointmentWriteService.RescheduleCommand(
                principal.userId(), appointmentNo, request.slotId(), request.idempotencyKey()));
        return ResponseEntity.status("NOT_FOUND".equals(result.code()) ? 404 : 200).body(BookingResponse.of(result));
    }

    @PostMapping("/{appointmentNo}/reschedule/status")
    public ResponseEntity<BookingResponse> rescheduleStatus(
            @PathVariable String appointmentNo,
            @AuthenticationPrincipal(expression = "patient") Principal principal,
            @Valid @RequestBody BookingRequest request) {
        return writeService.lookupReschedule(new AppointmentWriteService.RescheduleCommand(
                        principal.userId(), appointmentNo, request.slotId(), request.idempotencyKey()))
                .map(result -> ResponseEntity.status("NOT_FOUND".equals(result.code()) ? 404 : 200)
                        .body(BookingResponse.of(result)))
                .orElseGet(() -> ResponseEntity.ok(new BookingResponse(false, null, null,
                        "NOT_FOUND", "暂未查到这次改约的结果，可再次核验或重试同一次提交。")));
    }

    @PostMapping("/{appointmentNo}/consultation")
    public ResponseEntity<VisitConsultationResponse> consultation(
            @PathVariable String appointmentNo,
            @AuthenticationPrincipal(expression = "patient") Principal principal) {
        return tasks.openVisitConsultation(principal, appointmentNo)
                .map(taskId -> ResponseEntity.ok(new VisitConsultationResponse(taskId.value())))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record VisitConsultationResponse(String taskId) { }
}

package com.mediflow.agent.service.appointment.query;

import java.util.List;
import java.util.Optional;

import com.mediflow.agent.agent.booking.DepartmentCatalog;
import com.mediflow.agent.agent.booking.DoctorDirectory;
import com.mediflow.agent.mapper.appointment.HospitalDirectoryMapper;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Reads the synthetic hospital's department and doctor catalog.
 *
 * <p>It serves three callers: the agent resolves a triage recommendation's department name through
 * {@link DepartmentCatalog}, the knowledge tool checks that a passage's claimed clinician is really
 * active in the claimed department, and the portal browses the published directory. All of them only
 * ever see active rows, so a disabled department or clinician disappears from every path at once.
 */
@Service
@Profile("mysql")
public final class MySqlHospitalDirectory implements DepartmentCatalog, DoctorDirectory {

    public record DepartmentView(long id, String code, String name, String description) {
    }

    public record DoctorView(long id, long departmentId, String name, String title,
                             String specialty, String introduction) {
    }

    private final HospitalDirectoryMapper mapper;

    public MySqlHospitalDirectory(HospitalDirectoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long findActiveDepartmentId(String departmentName) {
        if (departmentName == null || departmentName.isBlank()) {
            return 0L;
        }
        // Matches the read tool's lookup: a patient may name either the display name or the code.
        List<Long> matches = mapper.activeDepartmentIds(departmentName.trim());
        return matches.isEmpty() ? 0L : matches.get(0);
    }

    public List<DepartmentView> listActiveDepartments() {
        return mapper.activeDepartments().stream()
                .map(row -> new DepartmentView(row.id(), row.code(), row.name(), row.description())).toList();
    }

    @Override
    public long findActiveDepartmentIdMentionedIn(String question) {
        List<Long> matches = mapper.mentionedDepartmentIds(question);
        return matches.size() == 1 ? matches.get(0) : 0L;
    }

    public List<DoctorView> listActiveDoctors(long departmentId) {
        return mapper.activeDoctors(departmentId).stream()
                .map(row -> new DoctorView(row.id(), row.departmentId(), row.name(), row.title(),
                        row.specialty(), row.introduction())).toList();
    }

    @Override
    public Optional<Clinician> findActiveClinicianMentionedIn(String question) {
        List<Clinician> named = mapper.mentionedClinicians(question);
        return named.size() == 1 ? Optional.of(named.get(0)) : Optional.empty();
    }

    /**
     * One predicate, both conditions. Asking only "is this doctor active" would accept a passage that
     * attributes a clinician to the wrong department, which is exactly the kind of claim a patient
     * would act on.
     */
    @Override
    public boolean isActiveDoctorInDepartment(long doctorId, long departmentId) {
        if (doctorId <= 0 || departmentId <= 0) {
            return false;
        }
        return mapper.countActiveDoctorInDepartment(doctorId, departmentId) > 0;
    }
}

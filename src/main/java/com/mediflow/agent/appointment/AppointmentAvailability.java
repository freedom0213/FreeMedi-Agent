package com.mediflow.agent.appointment;

import org.springframework.jdbc.core.JdbcTemplate;

/** Shared locking order for appointment operations. Patient locks precede inventory and appointment locks. */
final class AppointmentAvailability {
    private AppointmentAvailability() {}

    static void lockPatient(JdbcTemplate jdbc, long patientId) {
        jdbc.queryForList("SELECT id FROM sys_user WHERE id = ? FOR UPDATE", Long.class, patientId);
    }

    static String lockDepartment(JdbcTemplate jdbc, long departmentId) {
        return lockStatus(jdbc, "SELECT status FROM department WHERE id = ? FOR UPDATE", departmentId);
    }

    static String lockDoctor(JdbcTemplate jdbc, long departmentId, long doctorId) {
        return lockStatus(jdbc, "SELECT status FROM doctor WHERE id = ? AND department_id = ? FOR UPDATE", doctorId, departmentId);
    }

    static String lockSchedule(JdbcTemplate jdbc, long doctorId, long scheduleId) {
        return lockStatus(jdbc, "SELECT status FROM doctor_schedule WHERE id = ? AND doctor_id = ? FOR UPDATE", scheduleId, doctorId);
    }

    private static String lockStatus(JdbcTemplate jdbc, String sql, Object... ids) {
        return jdbc.queryForList(sql, String.class, ids).stream().findFirst().orElse(null);
    }
}

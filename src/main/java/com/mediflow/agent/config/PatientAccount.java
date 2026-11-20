package com.mediflow.agent.config;

import com.mediflow.agent.agent.Principal;
import org.springframework.security.core.userdetails.User;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;

/** The authenticated account carries the database identity across REST and stream requests. */
public final class PatientAccount extends User {
    private final Principal patient;
    private final String displayName;

    PatientAccount(long id, String username, String passwordHash, String displayName, boolean enabled) {
        super(username, passwordHash, enabled, true, true, true, createAuthorityList("ROLE_PATIENT"));
        this.patient = new Principal(id, "PATIENT");
        this.displayName = displayName;
    }

    public Principal getPatient() {
        return patient;
    }

    public String getDisplayName() {
        return displayName;
    }
}

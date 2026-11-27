package com.mediflow.agent.security.account;

import com.mediflow.agent.agent.runtime.Principal;
import org.springframework.security.core.userdetails.User;

import static org.springframework.security.core.authority.AuthorityUtils.createAuthorityList;

/** The authenticated account carries the database identity across REST and stream requests. */
public final class PatientAccount extends User implements SessionAccount {
    private final Principal patient;
    private final String displayName;
    private final long version;

    public PatientAccount(long id, String username, String passwordHash, String displayName, boolean enabled, long version) {
        super(username, passwordHash, enabled, true, true, true, createAuthorityList("ROLE_PATIENT"));
        this.patient = new Principal(id, "PATIENT");
        this.displayName = displayName;
        this.version = version;
    }

    public Principal getPatient() {
        return patient;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override public long accountId() { return patient.userId(); }
    @Override public long credentialVersion() { return version; }
}

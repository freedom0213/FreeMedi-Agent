package com.mediflow.agent.config;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

/** Two account types can share a browser session without sharing credentials or CSRF state. */
final class RoleSession {
    private RoleSession() {}

    static void configure(HttpSecurity http, String role) throws Exception {
        var sessionContext = new HttpSessionSecurityContextRepository();
        sessionContext.setSpringSecurityContextKey("MEDIFLOW_" + role + "_SECURITY_CONTEXT");
        var contexts = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), sessionContext);
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setSessionAttributeName("MEDIFLOW_" + role + "_CSRF_TOKEN");

        http.securityContext(config -> config.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrfTokens))
                .logout(config -> config.invalidateHttpSession(false));
    }
}

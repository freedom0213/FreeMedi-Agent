package com.mediflow.agent.security.account;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Two account types can share a browser session without sharing credentials or CSRF state. */
public final class RoleSession {
    private RoleSession() {}

    public static void configure(HttpSecurity http, String role, AccountCredentials credentials) throws Exception {
        var sessionContext = new HttpSessionSecurityContextRepository();
        sessionContext.setSpringSecurityContextKey("MEDIFLOW_" + role + "_SECURITY_CONTEXT");
        var contexts = new DelegatingSecurityContextRepository(
                new RequestAttributeSecurityContextRepository(), sessionContext);
        var csrfTokens = new HttpSessionCsrfTokenRepository();
        csrfTokens.setSessionAttributeName("MEDIFLOW_" + role + "_CSRF_TOKEN");

        http.securityContext(config -> config.securityContextRepository(contexts))
                .csrf(config -> config.csrfTokenRepository(csrfTokens))
                .logout(config -> config.invalidateHttpSession(false))
                .addFilterBefore(new OncePerRequestFilter() {
                    @Override
                    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                            throws ServletException, IOException {
                        var security = SecurityContextHolder.getContext();
                        var authentication = security.getAuthentication();
                        if (authentication != null && authentication.getPrincipal() instanceof SessionAccount account) {
                            var state = credentials.state(account.accountId(), role);
                            if (!credentials.valid(state, account)) {
                                SecurityContextHolder.clearContext();
                                contexts.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
                            } else if (state.temporaryExpiresAt() != null && !passwordAction(request)) {
                                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                                response.setContentType("application/json");
                                response.getWriter().write("{\"code\":\"PASSWORD_CHANGE_REQUIRED\"}");
                                return;
                            }
                        }
                        chain.doFilter(request, response);
                    }
                }, CsrfFilter.class);
    }

    private static boolean passwordAction(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return switch (request.getMethod()) {
            case "GET" -> path.equals("/api/v1/admin/auth/csrf") || path.equals("/api/v1/admin/auth/session");
            case "POST" -> path.equals("/api/v1/admin/auth/logout") || path.equals("/api/v1/admin/auth/password");
            default -> false;
        };
    }
}

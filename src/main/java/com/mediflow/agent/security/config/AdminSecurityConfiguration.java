package com.mediflow.agent.security.config;

import com.mediflow.agent.security.account.AccountCredentials;
import com.mediflow.agent.security.account.AdminAccess;
import com.mediflow.agent.security.account.RoleSession;
import com.mediflow.agent.security.account.SessionAccount;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.mediflow.agent.security.account.AdminAccess.Role;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static jakarta.servlet.DispatcherType.ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

@Configuration
@Profile("mysql")
public class AdminSecurityConfiguration {
    @Bean
    @Order(1)
    SecurityFilterChain adminSecurityFilterChain(HttpSecurity http, JdbcTemplate jdbc,
                                                 PasswordEncoder passwords, AdminAccess access,
                                                 AccountCredentials credentials) throws Exception {
        RoleSession.configure(http, "ADMIN", credentials);
        var provider = new DaoAuthenticationProvider(passwords);
        provider.setUserDetailsService(username -> jdbc.query("""
                SELECT id, username, password_hash, display_name, status, credential_version, temporary_password_expires_at
                FROM sys_user WHERE username = ? AND role = 'ADMIN'
                """, (row, index) -> new AdminAccount(row.getLong("id"), row.getString("username"),
                row.getString("password_hash"), row.getString("display_name"),
                credentials.loginAllowed("ACTIVE".equals(row.getString("status")), row.getTimestamp("temporary_password_expires_at")),
                row.getLong("credential_version"), row.getTimestamp("temporary_password_expires_at") != null), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Administrator not found")));
        var logoutPath = AntPathRequestMatcher.antMatcher(HttpMethod.POST, "/api/v1/admin/auth/logout");
        return http.securityMatcher("/api/v1/admin/**")
                .authenticationManager(new ProviderManager(provider))
                .authorizeHttpRequests(authorize -> authorize.dispatcherTypeMatchers(ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/auth/csrf").permitAll()
                        .requestMatchers("/api/v1/admin/auth/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/staff/**", "/api/v1/admin/setup/**",
                                "/api/v1/admin/catalog/departments", "/api/v1/admin/catalog/doctors",
                                "/api/v1/admin/agent/model-config/**", "/api/v1/admin/agent/prompt-templates/**",
                                "/api/v1/admin/agent/tools/**").access(access.allow(Role.SYSTEM_ADMIN))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/catalog/departments/options",
                                "/api/v1/admin/catalog/doctors/options").access(access.allow(Role.SYSTEM_ADMIN, Role.SCHEDULER, Role.TRIAGE, Role.VISIT_SUPERVISOR))
                        .requestMatchers("/api/v1/admin/catalog/departments/**", "/api/v1/admin/catalog/doctors/**")
                                .access(access.allow(Role.SYSTEM_ADMIN))
                        .requestMatchers("/api/v1/admin/catalog/schedules/**").access(access.allow(Role.SCHEDULER))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/appointments/*/check-in").access(access.allow(Role.TRIAGE))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/appointments/*/visit-completion").access(access.allow(Role.TRIAGE))
                        .requestMatchers(HttpMethod.POST, "/api/v1/admin/appointments/*/visit-review").access(access.allow(Role.VISIT_SUPERVISOR))
                        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/appointments/*/arrangement").access(access.allow(Role.SCHEDULER))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/appointments/interruption-pending/count").access(access.allow(Role.SCHEDULER))
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/appointments/**")
                                .access(access.allow(Role.TRIAGE, Role.SCHEDULER, Role.VISIT_SUPERVISOR))
                        .requestMatchers("/api/v1/admin/knowledge/**").access(access.allow(Role.KNOWLEDGE))
                        .anyRequest().access(access.allow()))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(SC_UNAUTHORIZED)))
                .formLogin(login -> login.loginPage("/admin/login")
                        .loginProcessingUrl("/api/v1/admin/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(SC_NO_CONTENT))
                        .failureHandler((request, response, exception) -> response.setStatus(SC_UNAUTHORIZED)))
                .logout(logout -> logout.logoutRequestMatcher(request -> {
                            var authentication = SecurityContextHolder.getContext().getAuthentication();
                            return logoutPath.matches(request) && authentication != null
                                    && authentication.getPrincipal() instanceof AdminAccount;
                        })
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(SC_NO_CONTENT)))
                .build();
    }

    @Bean
    ApplicationRunner localAdminAccount(JdbcTemplate jdbc, PasswordEncoder passwords, PlatformTransactionManager transactions,
                                       @Value("${mediflow.admin.username:}") String username,
                                       @Value("${mediflow.admin.password:}") String password,
                                       @Value("${mediflow.admin.display-name:资料管理员}") String displayName) {
        return arguments -> {
            if (username.isEmpty() && password.isEmpty()) return;
            if (username.isBlank() || username.length() > 64 || !username.equals(username.strip())
                    || username.chars().anyMatch(Character::isISOControl)
                    || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72
                    || displayName.isBlank() || displayName.length() > 100) {
                throw new IllegalArgumentException("Invalid local administrator configuration");
            }
            if (existingAdmin(jdbc, username)) return;
            try {
                new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                    jdbc.update("""
                            INSERT INTO sys_user(username, password_hash, display_name, role, status)
                            VALUES (?, ?, ?, 'ADMIN', 'ACTIVE')
                            """, username, passwords.encode(password), displayName);
                    jdbc.update("""
                            INSERT INTO admin_grant(admin_id, role_code)
                            SELECT id, 'SUPER_ADMIN' FROM sys_user WHERE username = ?
                            """, username);
                });
            } catch (DuplicateKeyException duplicate) {
                if (!existingAdmin(jdbc, username)) throw duplicate;
            }
        };
    }

    private boolean existingAdmin(JdbcTemplate jdbc, String username) {
        List<String> roles = jdbc.queryForList("SELECT role FROM sys_user WHERE username = ?",
                String.class, username);
        if (roles.isEmpty()) return false;
        if ("ADMIN".equals(roles.get(0))) return true;
        throw new IllegalArgumentException("Local administrator username belongs to another role");
    }

    public static final class AdminAccount extends User implements SessionAccount {
        private final long adminId;
        private final String displayName;
        private final long version;
        private final boolean mustChangePassword;

        AdminAccount(long adminId, String username, String password, String displayName, boolean enabled,
                     long version, boolean mustChangePassword) {
            super(username, password, enabled, true, true, true, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            this.adminId = adminId;
            this.displayName = displayName;
            this.version = version;
            this.mustChangePassword = mustChangePassword;
        }

        public String adminId() { return Long.toString(adminId); }

        public String displayName() { return displayName; }

        @Override public long accountId() { return adminId; }
        @Override public long credentialVersion() { return version; }
        public boolean mustChangePassword() { return mustChangePassword; }
    }
}

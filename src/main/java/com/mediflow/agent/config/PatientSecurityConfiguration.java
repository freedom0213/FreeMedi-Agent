package com.mediflow.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import static jakarta.servlet.DispatcherType.ERROR;
import static jakarta.servlet.http.HttpServletResponse.SC_NO_CONTENT;
import static jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED;

@Configuration
@Profile("mysql")
public class PatientSecurityConfiguration {
    @Bean
    PasswordEncoder patientPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService patientAccounts(JdbcTemplate jdbc) {
        return username -> jdbc.query("""
                SELECT id, username, password_hash, display_name, status
                FROM sys_user WHERE username = ? AND role = 'PATIENT'
                """, (row, index) -> new PatientAccount(row.getLong("id"), row.getString("username"),
                row.getString("password_hash"), row.getString("display_name"),
                "ACTIVE".equals(row.getString("status"))), username)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Patient not found"));
    }

    @Bean
    SecurityFilterChain patientSecurityFilterChain(HttpSecurity http) throws Exception {
        RoleSession.configure(http, "PATIENT");
        return http.authorizeHttpRequests(authorize -> authorize
                        .dispatcherTypeMatchers(ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf",
                                "/api/v1/departments", "/api/v1/departments/*/doctors", "/api/v1/slots", "/api/v1/slots/window").permitAll()
                        .requestMatchers("/api/**").hasRole("PATIENT")
                        .anyRequest().permitAll())
                .requestCache(cache -> cache.disable())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(
                        (request, response, exception) -> response.setStatus(SC_UNAUTHORIZED)))
                .formLogin(login -> login.loginPage("/login")
                        .loginProcessingUrl("/api/v1/auth/login")
                        .successHandler((request, response, authentication) -> response.setStatus(SC_NO_CONTENT))
                        .failureHandler((request, response, exception) -> response.setStatus(SC_UNAUTHORIZED)))
                .logout(logout -> logout.logoutUrl("/api/v1/auth/logout")
                        .logoutSuccessHandler((request, response, authentication) -> response.setStatus(SC_NO_CONTENT)))
                .build();
    }
}

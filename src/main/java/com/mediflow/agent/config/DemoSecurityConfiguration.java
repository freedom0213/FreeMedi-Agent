package com.mediflow.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/** Local-only security setup; identity is resolved from X-Demo-User-Id by the API layer. */
@Configuration
@Profile("mysql")
public class DemoSecurityConfiguration {
    @Bean
    SecurityFilterChain demoSecurityFilterChain(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                .build();
    }
}

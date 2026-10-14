package com.mediflow.agent.api;

import com.mediflow.agent.agent.Principal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DemoIdentityService {
    private final JdbcTemplate jdbc;

    public DemoIdentityService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Principal resolve(String rawUserId) {
        long userId;
        try {
            userId = Long.parseLong(rawUserId == null || rawUserId.isBlank() ? "1001" : rawUserId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("X-Demo-User-Id must be a positive integer");
        }
        if (userId <= 0) {
            throw new IllegalArgumentException("X-Demo-User-Id must be a positive integer");
        }
        String role = jdbc.query("""
                SELECT role FROM sys_user WHERE id = ? AND status = 'ACTIVE'
                """, (result, rowNum) -> result.getString("role"), userId)
                .stream().findFirst()
                .orElseThrow(() -> new SecurityException("demo user does not exist or is disabled"));
        return new Principal(userId, role);
    }
}

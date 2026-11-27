package com.mediflow.agent.agent.runtime;

public record Principal(long userId, String role) {
    public Principal {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id must be positive");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
    }
}

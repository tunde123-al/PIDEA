package com.pidea.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code pidea.security.*}. Roles: ADMIN, OPERATOR, VIEWER. */
@ConfigurationProperties(prefix = "pidea.security")
public record SecurityUsersProperties(List<UserEntry> users, List<String> allowedOrigins) {

    public SecurityUsersProperties {
        users = users == null ? List.of() : users;
        allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
    }

    public record UserEntry(String username, String passwordHash, String role) {}
}

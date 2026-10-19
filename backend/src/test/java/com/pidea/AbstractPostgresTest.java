package com.pidea;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that need a real database. One PostgreSQL container is started once per JVM
 * (static initialiser) and shared by all subclasses; Flyway builds the schema from the real migrations.
 */
@SpringBootTest
public abstract class AbstractPostgresTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
        // generous timeout: Docker Desktop on a busy laptop can take minutes to run initdb
        .withStartupTimeout(java.time.Duration.ofMinutes(6))
        .withCommand("postgres", "-c", "fsync=off", "-c", "synchronous_commit=off");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
    }
}

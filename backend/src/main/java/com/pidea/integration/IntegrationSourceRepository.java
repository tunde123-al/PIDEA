package com.pidea.integration;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data generates the implementation at runtime from this interface and the method names. */
public interface IntegrationSourceRepository extends JpaRepository<IntegrationSource, UUID> {
    boolean existsByName(String name);
}

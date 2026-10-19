package com.pidea.integration;

import com.pidea.common.PageResponse;
import com.pidea.integration.SourceDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Thin HTTP layer: validate, authorise, delegate to the service. No business logic here. */
@RestController
@RequestMapping("/api/v1/integration-sources")
@Tag(name = "Integration sources")
@SecurityRequirement(name = "basicAuth")
public class IntegrationSourceController {
    private static final String ANY_ROLE = "hasAnyRole('ADMIN','OPERATOR','VIEWER')";
    private final IntegrationSourceService service;

    public IntegrationSourceController(IntegrationSourceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register an integration source (ADMIN)",
        description = "Returns 409 if the name is taken, 400 if config contains credential-like keys.")
    public SourceResponse create(@Valid @RequestBody CreateSourceRequest req) {
        return service.create(req);
    }

    @GetMapping
    @PreAuthorize(ANY_ROLE)
    @Operation(summary = "List integration sources (paged, max page size 100)")
    public PageResponse<SourceResponse> list(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize(ANY_ROLE)
    public SourceResponse get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Update config / secret reference (ADMIN)")
    public SourceResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateSourceRequest req) {
        return service.update(id, req);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR')")
    @Operation(summary = "Enable or disable a source (ADMIN, OPERATOR)")
    public SourceResponse status(@PathVariable UUID id, @Valid @RequestBody UpdateStatusRequest req) {
        return service.changeStatus(id, req.status());
    }

    @GetMapping("/{id}/health")
    @PreAuthorize(ANY_ROLE)
    @Operation(summary = "Latest job status and recent failure count for a source")
    public SourceHealthResponse health(@PathVariable UUID id) {
        return service.health(id);
    }
}

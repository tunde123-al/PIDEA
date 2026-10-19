package com.pidea.integration;

import com.pidea.common.PageResponse;
import com.pidea.integration.ScheduleDtos.CreateScheduleRequest;
import com.pidea.integration.ScheduleDtos.ScheduleResponse;
import com.pidea.integration.ScheduleDtos.UpdateScheduleRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integration-schedules")
@Tag(name = "Integration schedules")
@SecurityRequirement(name = "basicAuth")
public class IntegrationScheduleController {
    private final IntegrationScheduleService service;

    public IntegrationScheduleController(IntegrationScheduleService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    public ScheduleResponse create(@Valid @RequestBody CreateScheduleRequest request,
                                   java.security.Principal principal) {
        return service.create(request, principal.getName());
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','OPERATOR','VIEWER')")
    public PageResponse<ScheduleResponse> list(@RequestParam(required = false) UUID sourceId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.list(sourceId, page, size);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ScheduleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateScheduleRequest request) {
        return service.update(id, request);
    }
}

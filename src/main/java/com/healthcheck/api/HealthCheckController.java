package com.healthcheck.api;

import com.healthcheck.model.*;
import com.healthcheck.service.HealthCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Health Check API", description = "Manage and monitor third-party API health")
public class HealthCheckController {
    private final HealthCheckService service;

    public HealthCheckController(HealthCheckService service) {
        this.service = service;
    }

    @PostMapping("/targets")
    @Operation(summary = "Create a new health check target", 
               description = "Creates a new target to monitor. Use test data from README.md")
    @ApiResponse(responseCode = "201", description = "Target created successfully")
    @ApiResponse(responseCode = "400", description = "Invalid request body")
    public ResponseEntity<Target> createTarget(@Valid @RequestBody CreateTargetRequest request) {
        Target created = service.createTarget(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/targets")
    @Operation(summary = "List all health check targets")
    @ApiResponse(responseCode = "200", description = "List of all targets")
    public ResponseEntity<List<Target>> listTargets() {
        return ResponseEntity.ok(service.listTargets());
    }

    @GetMapping("/targets/{id}")
    @Operation(summary = "Get a specific target by ID")
    @ApiResponse(responseCode = "200", description = "Target found")
    @ApiResponse(responseCode = "404", description = "Target not found")
    public ResponseEntity<Target> getTarget(@PathVariable UUID id) {
        Target target = service.getTarget(id);
        if (target == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(target);
    }

    @PutMapping("/targets/{id}")
    @Operation(summary = "Update an existing target")
    @ApiResponse(responseCode = "200", description = "Target updated successfully")
    @ApiResponse(responseCode = "404", description = "Target not found")
    public ResponseEntity<Target> updateTarget(@PathVariable UUID id,
                                               @Valid @RequestBody UpdateTargetRequest request) {
        Target updated = service.updateTarget(id, request);
        if (updated == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/targets/{id}")
    @Operation(summary = "Delete a target")
    @ApiResponse(responseCode = "204", description = "Target deleted successfully")
    @ApiResponse(responseCode = "404", description = "Target not found")
    public ResponseEntity<Void> deleteTarget(@PathVariable UUID id) {
        if (service.deleteTarget(id)) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/targets/{id}/check")
    @Operation(summary = "Trigger a health check for a target",
               description = "force=true bypasses cache, force=false uses cached result if available")
    @ApiResponse(responseCode = "200", description = "Health check completed")
    @ApiResponse(responseCode = "404", description = "Target not found")
    public ResponseEntity<HealthCheckResult> checkTarget(@PathVariable UUID id,
                                                         @RequestParam(defaultValue = "false") boolean force) {
        HealthCheckResult result = service.checkTarget(id, force);
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/health/results")
    @Operation(summary = "Get all health check results")
    @ApiResponse(responseCode = "200", description = "Map of target ID to latest result")
    public ResponseEntity<Map<UUID, HealthCheckResult>> getLastResults() {
        return ResponseEntity.ok(service.getLastResults());
    }

    @GetMapping("/health/summary")
    @Operation(summary = "Get health status summary")
    @ApiResponse(responseCode = "200", description = "Count of targets by status")
    public ResponseEntity<HealthSummaryResponse> getSummary() {
        return ResponseEntity.ok(service.getSummary());
    }
}

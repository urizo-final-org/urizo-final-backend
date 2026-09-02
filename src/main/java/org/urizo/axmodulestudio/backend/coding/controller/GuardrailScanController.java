package org.urizo.axmodulestudio.backend.coding.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.urizo.axmodulestudio.backend.coding.dto.GuardrailScanContract;
import org.urizo.axmodulestudio.backend.coding.service.GuardrailScanService;

/**
 * The guardrail folder scan, for the administrator screen that chooses what the model may change.
 *
 * <p>Reading the folders means waiting for the runner, so the scan is requested and then read back.
 * A scan that is still queued reports its state rather than failing.
 */
@RestController
@Validated
@RequestMapping("/api/admin/coding/guardrail/scans")
@ConditionalOnProperty(prefix = "ax.coding.model-turn-bridge", name = "enabled", havingValue = "true")
public class GuardrailScanController {

    private final GuardrailScanService scans;

    GuardrailScanController(GuardrailScanService scans) {
        this.scans = scans;
    }

    @PostMapping
    public ResponseEntity<GuardrailScanContract.ScanAccepted> request(
            @Valid @RequestBody GuardrailScanContract.ScanRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(scans.request(request.repository()));
    }

    @GetMapping("/{scanId}")
    public GuardrailScanContract.ScanResult result(
            @PathVariable UUID scanId,
            @RequestParam String repository) {
        return scans.result(scanId, repository);
    }
}

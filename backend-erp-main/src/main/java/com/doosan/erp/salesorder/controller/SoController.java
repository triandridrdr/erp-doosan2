package com.doosan.erp.salesorder.controller;

import com.doosan.erp.common.dto.ApiResponse;
import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.dto.SoHeaderResponse;
import com.doosan.erp.salesorder.entity.SoWorkflowStatus;
import com.doosan.erp.salesorder.service.SoHeaderService;
import com.doosan.erp.salesorder.service.SoScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sales-orders")
@RequiredArgsConstructor
@Tag(name = "Sales Order", description = "Normalized Sales Order Management API")
public class SoController {

    private final SoScanService scanService;
    private final SoHeaderService headerService;

    // ─── SAVE DRAFT (Create or Merge) ────────────────────────────────────────────

    @PostMapping("/draft")
    @Operation(summary = "Save or update a scanned document draft",
            description = "Creates or merges SO Header and saves normalized scan data based on documentType. " +
                    "Supports: supplementary, purchase-order, size-per-colour-breakdown, total-country-breakdown")
    public ResponseEntity<ApiResponse<SaveDraftResponse>> saveDraft(@RequestBody Map<String, Object> payload) {
        String documentType = "";
        Object dt = payload.get("documentType");
        if (dt != null) documentType = dt.toString();

        SaveDraftResponse res = scanService.saveDraft(documentType, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(res));
    }

    // ─── LIST ALL HEADERS ────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all Sales Order headers")
    public ResponseEntity<ApiResponse<List<SoHeaderResponse>>> listAll() {
        return ResponseEntity.ok(ApiResponse.success(headerService.listAll()));
    }

    // ─── GET BY ID ───────────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @Operation(summary = "Get Sales Order header by ID")
    public ResponseEntity<ApiResponse<SoHeaderResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(headerService.getById(id)));
    }

    // ─── GET BY SO NUMBER ────────────────────────────────────────────────────────

    @GetMapping("/by-so-number/{soNumber}")
    @Operation(summary = "Get Sales Order header by SO number")
    public ResponseEntity<ApiResponse<SoHeaderResponse>> getBySoNumber(@PathVariable String soNumber) {
        return ResponseEntity.ok(ApiResponse.success(headerService.getBySoNumber(soNumber)));
    }

    // ─── UPDATE WORKFLOW STATUS ──────────────────────────────────────────────────

    @PatchMapping("/{id}/workflow-status")
    @Operation(summary = "Update workflow status",
            description = "Transitions: DRAFT_OCR → OCR_REVIEW → PRE_SO → SO_APPROVED → PRODUCTION → CLOSED")
    public ResponseEntity<ApiResponse<SoHeaderResponse>> updateWorkflowStatus(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        SoWorkflowStatus newStatus = SoWorkflowStatus.valueOf(status);
        return ResponseEntity.ok(ApiResponse.success(headerService.updateWorkflowStatus(id, newStatus)));
    }

    // ─── DELETE (SOFT) ───────────────────────────────────────────────────────────

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft delete Sales Order")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        headerService.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

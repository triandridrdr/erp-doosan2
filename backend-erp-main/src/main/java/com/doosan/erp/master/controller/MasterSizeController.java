package com.doosan.erp.master.controller;

import com.doosan.erp.common.dto.ApiResponse;
import com.doosan.erp.master.dto.MasterSizeDto;
import com.doosan.erp.master.dto.MasterSizeUpsertRequest;
import com.doosan.erp.master.service.MasterSizeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST API for the master size lookup table.
 *
 * Flow:
 *   GET  /api/v1/master-sizes              -> list active (for dropdown)
 *   GET  /api/v1/master-sizes?all=true     -> list including inactive (admin)
 *   POST /api/v1/master-sizes              -> idempotent upsert by normalized label
 *   PUT  /api/v1/master-sizes/{id}         -> edit
 *   DELETE /api/v1/master-sizes/{id}       -> soft delete + deactivate
 */
@RestController
@RequestMapping("/api/v1/master-sizes")
@RequiredArgsConstructor
@Tag(name = "Master - Size", description = "Master table of clothing sizes for dropdowns and OCR mapping")
public class MasterSizeController {

    private final MasterSizeService service;

    @GetMapping
    @Operation(summary = "List master sizes",
            description = "By default returns only active, non-deleted sizes ordered by sortOrder. Pass all=true to include inactive entries.")
    public ResponseEntity<ApiResponse<List<MasterSizeDto>>> list(
            @RequestParam(value = "all", required = false, defaultValue = "false") boolean all
    ) {
        List<MasterSizeDto> rows = all ? service.listAll() : service.listActive();
        return ResponseEntity.ok(ApiResponse.success(rows));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a master size by id")
    public ResponseEntity<ApiResponse<MasterSizeDto>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getById(id)));
    }

    @PostMapping
    @Operation(summary = "Upsert a master size by label",
            description = "Idempotent: if a row with the same normalized label exists, it is returned (reactivated if it was disabled). Safe to call whenever the UI discovers a new size.")
    public ResponseEntity<ApiResponse<MasterSizeDto>> upsert(@Valid @RequestBody MasterSizeUpsertRequest request) {
        MasterSizeDto dto = service.upsertByLabel(request);
        return ResponseEntity.ok(ApiResponse.success(dto, "Master size ensured"));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a master size")
    public ResponseEntity<ApiResponse<MasterSizeDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody MasterSizeUpsertRequest request) {
        MasterSizeDto dto = service.update(id, request);
        return ResponseEntity.ok(ApiResponse.success(dto, "Master size updated"));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft delete a master size",
            description = "Sets active=false and marks the row as soft-deleted. Existing references are preserved.")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.ok(ApiResponse.success(null, "Master size deleted"));
    }
}

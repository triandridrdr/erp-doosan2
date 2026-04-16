package com.doosan.erp.salesprototype.controller;

import com.doosan.erp.common.dto.ApiResponse;
import com.doosan.erp.salesprototype.dto.SalesOrderPrototypeResponse;
import com.doosan.erp.salesprototype.service.SalesOrderPrototypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sales-order-prototypes")
@RequiredArgsConstructor
@Tag(name = "Sales - Order Prototype", description = "Sales Order Prototype API")
public class SalesOrderPrototypeController {

    private final SalesOrderPrototypeService service;

    @PostMapping
    @Operation(summary = "Create sales order prototype", description = "Save OCR New draft as JSON into sales_order_prototype")
    public ResponseEntity<ApiResponse<SalesOrderPrototypeResponse>> create(@RequestBody Map<String, Object> payload) {
        String analyzedFileName = "";
        Object v = payload.get("analyzedFileName");
        if (v != null) analyzedFileName = v.toString();

        SalesOrderPrototypeResponse res = service.create(analyzedFileName, payload);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(res));
    }

    @GetMapping
    @Operation(summary = "List sales order prototypes")
    public ResponseEntity<ApiResponse<List<SalesOrderPrototypeResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success(service.list()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get sales order prototype")
    public ResponseEntity<ApiResponse<SalesOrderPrototypeResponse>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.get(id)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update sales order prototype", description = "Update stored JSON payload for a prototype")
    public ResponseEntity<ApiResponse<SalesOrderPrototypeResponse>> update(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        SalesOrderPrototypeResponse res = service.update(id, payload);
        return ResponseEntity.ok(ApiResponse.success(res));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete sales order prototype")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}

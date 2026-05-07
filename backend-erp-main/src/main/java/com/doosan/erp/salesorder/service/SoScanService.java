package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SaveDraftResponse;
import com.doosan.erp.salesorder.entity.*;
import com.doosan.erp.salesorder.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SoScanService {

    private final SoHeaderRepository headerRepo;
    private final SoScanSupplementaryRepository scanSuppRepo;
    private final SoScanPoRepository scanPoRepo;
    private final SoScanSizeBreakdownRepository scanSbRepo;
    private final SoScanCountryBreakdownRepository scanCbRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public SaveDraftResponse saveDraft(String documentType, Map<String, Object> payload) {
        String soNumber = extractSoNumber(payload);
        if (soNumber == null || soNumber.isBlank()) {
            throw new IllegalArgumentException("SO Number is required in formFields");
        }

        String fileName = str(payload.get("analyzedFileName"));

        // 1. Find or create SO Header
        SoHeader header = headerRepo.findBySoNumberAndDeletedFalse(soNumber)
                .orElseGet(() -> {
                    SoHeader h = new SoHeader();
                    h.setSoNumber(soNumber);
                    h.setWorkflowStatus(SoWorkflowStatus.DRAFT_OCR);
                    return h;
                });

        // 2. Merge header fields
        mergeHeaderFields(header, payload);
        header = headerRepo.save(header);

        // 3. Dispatch by document type
        return switch (documentType) {
            case "supplementary" -> saveSupplementary(header, fileName, payload);
            case "purchase-order" -> savePurchaseOrder(header, fileName, payload);
            case "size-per-colour-breakdown" -> saveSizeBreakdown(header, fileName, payload);
            case "total-country-breakdown" -> saveCountryBreakdown(header, fileName, payload);
            default -> throw new IllegalArgumentException("Unknown documentType: " + documentType);
        };
    }

    // ─── SUPPLEMENTARY ───────────────────────────────────────────────────────────

    private SaveDraftResponse saveSupplementary(SoHeader header, String fileName, Map<String, Object> payload) {
        // Soft-delete previous scans
        int nextRevision = softDeletePrevScansSupp(header.getId());

        // Create new scan
        SoScanSupplementary scan = new SoScanSupplementary();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(toJson(payload.get("raw")));

        // BOM items
        List<Map<String, Object>> bomRows = getListOfMaps(payload, "bomDraftRows");
        for (int i = 0; i < bomRows.size(); i++) {
            Map<String, Object> row = bomRows.get(i);
            SoSupplementaryBom bom = new SoSupplementaryBom();
            bom.setScan(scan);
            bom.setSoHeader(header);
            bom.setSortOrder(i);
            bom.setPosition(str(row.get("position")));
            bom.setPlacement(str(row.get("placement")));
            bom.setType(str(row.get("type")));
            bom.setDescription(str(row.get("description")));
            bom.setMaterialAppearance(str(row.get("materialAppearance")));
            bom.setComposition(str(row.get("composition")));
            bom.setConstruction(str(row.get("construction")));
            bom.setConsumption(str(row.get("consumption")));
            bom.setWeight(str(row.get("weight")));
            bom.setComponentTreatments(str(row.get("componentTreatments")));
            bom.setMaterialSupplier(str(row.get("materialSupplier")));
            bom.setSupplierArticle(str(row.get("supplierArticle")));
            bom.setBookingId(str(row.get("bookingId")));
            bom.setDemandId(str(row.get("demandId")));
            scan.getBomItems().add(bom);
        }

        scan = scanSuppRepo.save(scan);
        log.info("[SUPP_SAVE] SO={} scanId={} rev={} bom={}", header.getSoNumber(), scan.getId(), nextRevision, bomRows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("supplementary")
                .revision(nextRevision)
                .message("Supplementary draft saved successfully")
                .build();
    }

    // ─── PURCHASE ORDER ──────────────────────────────────────────────────────────

    private SaveDraftResponse savePurchaseOrder(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansPo(header.getId());

        SoScanPo scan = new SoScanPo();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(toJson(payload.get("raw")));

        // BOM items (simple 6-col variant from PO)
        List<Map<String, Object>> bomRows = getListOfMaps(payload, "bomDraftRows");
        // PO uses simple BOM stored as SoPoItem-adjacent data — we store in supplementary_bom with scan_id=null approach
        // Actually for PO we store quantity-per-article as SoPoItem

        // Quantity per Article → SoPoItem
        List<Map<String, Object>> sizeRows = getListOfMaps(payload, "salesOrderDetailSizeBreakdown");
        // These are stored in the size breakdown scan, but from PO context we persist them as PO items if present

        // Time of Delivery
        List<Map<String, Object>> todRows = getListOfMaps(payload, "purchaseOrderTimeOfDelivery");
        // Invoice Avg Price
        List<Map<String, Object>> iapRows = getListOfMaps(payload, "purchaseOrderInvoiceAvgPrice");

        // PO Items from quantityPerArticle in raw data
        Object rawObj = payload.get("raw");
        List<Map<String, Object>> qpaRows = List.of();
        if (rawObj instanceof Map<?, ?> rawMap) {
            qpaRows = getListOfMapsFromObj(rawMap.get("purchaseOrderQuantityPerArticle"));
        }

        for (int i = 0; i < qpaRows.size(); i++) {
            Map<String, Object> row = qpaRows.get(i);
            SoPoItem item = new SoPoItem();
            item.setScan(scan);
            item.setSoHeader(header);
            item.setSortOrder(i);
            item.setPageNumber(toInt(row.get("page")));
            item.setArticleNo(str(row.get("articleNo")));
            item.setHmColourCode(str(row.get("hmColourCode")));
            item.setPtArticleNumber(str(row.get("ptArticleNumber")));
            item.setColour(str(row.get("colour")));
            item.setOptionNo(str(row.get("optionNo")));
            item.setCost(str(row.get("cost")));
            item.setQtyArticle(str(row.get("qtyArticle")));
            scan.getItems().add(item);
        }

        for (int i = 0; i < todRows.size(); i++) {
            Map<String, Object> row = todRows.get(i);
            SoPoTimeOfDelivery tod = new SoPoTimeOfDelivery();
            tod.setScan(scan);
            tod.setSoHeader(header);
            tod.setSortOrder(i);
            tod.setPageNumber(toInt(row.get("page")));
            tod.setTimeOfDelivery(str(row.get("timeOfDelivery")));
            tod.setPlanningMarkets(str(row.get("planningMarkets")));
            tod.setQuantity(str(row.get("quantity")));
            tod.setPercentTotalQty(str(row.get("percentTotalQty")));
            scan.getTimeOfDeliveries().add(tod);
        }

        for (int i = 0; i < iapRows.size(); i++) {
            Map<String, Object> row = iapRows.get(i);
            SoPoInvoiceAvgPrice iap = new SoPoInvoiceAvgPrice();
            iap.setScan(scan);
            iap.setSoHeader(header);
            iap.setSortOrder(i);
            iap.setPageNumber(toInt(row.get("page")));
            iap.setInvoiceAvgPrice(str(row.get("invoiceAveragePrice")));
            iap.setCountry(str(row.get("country")));
            scan.getInvoiceAvgPrices().add(iap);
        }

        scan = scanPoRepo.save(scan);
        log.info("[PO_SAVE] SO={} scanId={} rev={} items={} tod={} iap={}",
                header.getSoNumber(), scan.getId(), nextRevision,
                scan.getItems().size(), scan.getTimeOfDeliveries().size(), scan.getInvoiceAvgPrices().size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("purchase-order")
                .revision(nextRevision)
                .message("Purchase Order draft saved successfully")
                .build();
    }

    // ─── SIZE BREAKDOWN ──────────────────────────────────────────────────────────

    private SaveDraftResponse saveSizeBreakdown(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansSb(header.getId());

        SoScanSizeBreakdown scan = new SoScanSizeBreakdown();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(toJson(payload.get("raw")));

        List<Map<String, Object>> rows = getListOfMaps(payload, "salesOrderDetailSizeBreakdown");
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            String country = str(row.get("countryOfDestination"));
            String type = str(row.get("type"));
            String color = str(row.get("color"));
            String size = str(row.get("size"));
            String qty = str(row.get("qty"));
            String total = str(row.get("total"));
            String noOfAsst = str(row.get("noOfAsst"));

            // Create breakdown group per row (flat structure from frontend)
            SoSizeBreakdown bd = new SoSizeBreakdown();
            bd.setScan(scan);
            bd.setSoHeader(header);
            bd.setCountryOfDestination(country.isBlank() ? "N/A" : country);
            bd.setType(type.isBlank() ? "N/A" : type);
            bd.setColor(color);
            bd.setNoOfAsst(noOfAsst);
            bd.setTotal(total);
            bd.setSortOrder(i);

            if (!size.isBlank()) {
                SoSizeBreakdownDetail detail = new SoSizeBreakdownDetail();
                detail.setBreakdown(bd);
                detail.setSizeLabel(size);
                detail.setQuantity(parseIntSafe(qty));
                detail.setSortOrder(0);
                bd.getDetails().add(detail);
            }

            scan.getBreakdowns().add(bd);
        }

        scan = scanSbRepo.save(scan);
        log.info("[SB_SAVE] SO={} scanId={} rev={} rows={}", header.getSoNumber(), scan.getId(), nextRevision, rows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("size-per-colour-breakdown")
                .revision(nextRevision)
                .message("Size Breakdown draft saved successfully")
                .build();
    }

    // ─── COUNTRY BREAKDOWN ───────────────────────────────────────────────────────

    private SaveDraftResponse saveCountryBreakdown(SoHeader header, String fileName, Map<String, Object> payload) {
        int nextRevision = softDeletePrevScansCb(header.getId());

        SoScanCountryBreakdown scan = new SoScanCountryBreakdown();
        scan.setSoHeader(header);
        scan.setFileName(fileName);
        scan.setRevision(nextRevision);
        scan.setOcrRawJsonb(toJson(payload.get("raw")));

        // Country breakdown rows
        List<Map<String, Object>> cbRows = getListOfMaps(payload, "totalCountryBreakdown");
        for (int i = 0; i < cbRows.size(); i++) {
            Map<String, Object> row = cbRows.get(i);
            SoCountryBreakdown cb = new SoCountryBreakdown();
            cb.setScan(scan);
            cb.setSoHeader(header);
            cb.setSortOrder(i);
            cb.setCountry(str(row.get("country")));
            cb.setPmCode(str(row.get("pmCode")));
            cb.setTotal(str(row.get("total")));
            scan.getCountryBreakdowns().add(cb);
        }

        // Colour/Size breakdown rows
        List<Map<String, Object>> csRows = getListOfMaps(payload, "section2cColourSizeBreakdown");
        for (int i = 0; i < csRows.size(); i++) {
            Map<String, Object> row = csRows.get(i);
            SoColourSizeBreakdown csb = new SoColourSizeBreakdown();
            csb.setScan(scan);
            csb.setSoHeader(header);
            csb.setSortOrder(i);
            csb.setArticle(str(row.get("article")));
            csb.setSizeLabel(str(row.get("size")));
            csb.setQuantity(str(row.get("qty")));
            scan.getColourSizeBreakdowns().add(csb);
        }

        scan = scanCbRepo.save(scan);
        log.info("[CB_SAVE] SO={} scanId={} rev={} countries={} colourSize={}",
                header.getSoNumber(), scan.getId(), nextRevision, cbRows.size(), csRows.size());

        return SaveDraftResponse.builder()
                .soHeaderId(header.getId())
                .soNumber(header.getSoNumber())
                .scanId(scan.getId())
                .documentType("total-country-breakdown")
                .revision(nextRevision)
                .message("Country Breakdown draft saved successfully")
                .build();
    }

    // ─── HELPER: Merge Header Fields ─────────────────────────────────────────────

    private void mergeHeaderFields(SoHeader header, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ff = (Map<String, Object>) payload.get("formFields");
        if (ff == null) return;

        mergeField(header::getOrderDate, header::setOrderDate, str(ff.get("Order Date")), str(ff.get("Date (ISO)")));
        mergeField(header::getSeason, header::setSeason, str(ff.get("Season")));
        mergeField(header::getSupplierCode, header::setSupplierCode, str(ff.get("Supplier Code")));
        mergeField(header::getSupplierName, header::setSupplierName, str(ff.get("Supplier")));
        mergeField(header::getProductNo, header::setProductNo, str(ff.get("Product No")), str(ff.get("Article / Product No")));
        mergeField(header::getProductName, header::setProductName, str(ff.get("Product Name")));
        mergeField(header::getProductDesc, header::setProductDesc, str(ff.get("Product Desc")), str(ff.get("Product Description")));
        mergeField(header::getProductType, header::setProductType, str(ff.get("Product Type")));
        mergeField(header::getOptionNo, header::setOptionNo, str(ff.get("Option No")));
        mergeField(header::getDevelopmentNo, header::setDevelopmentNo, str(ff.get("Development No")));
        mergeField(header::getCustomerGroup, header::setCustomerGroup, str(ff.get("Customer Group")), str(ff.get("Customs Customer Group")));
        mergeField(header::getTypeOfConstruction, header::setTypeOfConstruction, str(ff.get("Type of Construct")), str(ff.get("Type of Construction")));
        mergeField(header::getCountryOfProduction, header::setCountryOfProduction, str(ff.get("Country of Production")));
        mergeField(header::getCountryOfOrigin, header::setCountryOfOrigin, str(ff.get("Country of Origin")));
        mergeField(header::getCountryOfDelivery, header::setCountryOfDelivery, str(ff.get("Country of Bakery")), str(ff.get("Country of Delivery")));
        mergeField(header::getTermsOfPayment, header::setTermsOfPayment, str(ff.get("Term of Payment")), str(ff.get("Terms of Payment")));
        mergeField(header::getTermsOfDelivery, header::setTermsOfDelivery, str(ff.get("Terms of Delivery")));
        mergeField(header::getNoOfPieces, header::setNoOfPieces, str(ff.get("No of Pieces")));
        mergeField(header::getSalesMode, header::setSalesMode, str(ff.get("Sales Models")), str(ff.get("Sales Mode")));
        mergeField(header::getPtProdNo, header::setPtProdNo, str(ff.get("PT Prod No")));
    }

    private void mergeField(java.util.function.Supplier<String> getter,
                            java.util.function.Consumer<String> setter,
                            String... candidates) {
        String current = getter.get();
        if (current != null && !current.isBlank()) return;
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                setter.accept(c);
                return;
            }
        }
    }

    // ─── HELPER: Soft Delete Previous Scans ──────────────────────────────────────

    private int softDeletePrevScansSupp(Long headerId) {
        List<SoScanSupplementary> prev = scanSuppRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanSupplementary s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanSuppRepo.saveAll(prev);
        return maxRev + 1;
    }

    private int softDeletePrevScansPo(Long headerId) {
        List<SoScanPo> prev = scanPoRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanPo s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanPoRepo.saveAll(prev);
        return maxRev + 1;
    }

    private int softDeletePrevScansSb(Long headerId) {
        List<SoScanSizeBreakdown> prev = scanSbRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanSizeBreakdown s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanSbRepo.saveAll(prev);
        return maxRev + 1;
    }

    private int softDeletePrevScansCb(Long headerId) {
        List<SoScanCountryBreakdown> prev = scanCbRepo.findBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
        int maxRev = 0;
        for (SoScanCountryBreakdown s : prev) {
            maxRev = Math.max(maxRev, s.getRevision());
            s.softDelete();
        }
        if (!prev.isEmpty()) scanCbRepo.saveAll(prev);
        return maxRev + 1;
    }

    // ─── HELPER: Extract SO Number ───────────────────────────────────────────────

    private String extractSoNumber(Map<String, Object> payload) {
        Object ffObj = payload.get("formFields");
        if (ffObj instanceof Map<?, ?> ff) {
            Object so = ff.get("SO Number");
            if (so == null) so = ff.get("Order No");
            if (so != null) {
                String v = so.toString().trim();
                if (!v.isEmpty()) return v;
            }
        }
        return null;
    }

    // ─── HELPER: Utilities ───────────────────────────────────────────────────────

    private String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    private Integer toInt(Object o) {
        if (o == null) return null;
        String s = o.toString().replaceAll("[^0-9]", "");
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListOfMaps(Map<String, Object> payload, String key) {
        Object obj = payload.get(key);
        if (obj instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListOfMapsFromObj(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }
}

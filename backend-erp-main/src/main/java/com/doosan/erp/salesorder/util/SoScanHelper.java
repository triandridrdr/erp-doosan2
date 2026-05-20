package com.doosan.erp.salesorder.util;

import com.doosan.erp.master.dto.MasterSizeUpsertRequest;
import com.doosan.erp.master.service.MasterSizeService;
import com.doosan.erp.salesorder.entity.SoHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
@RequiredArgsConstructor
@Slf4j
public class SoScanHelper {

    private final MasterSizeService masterSizeService;
    private final ObjectMapper objectMapper;

    public String extractSoNumber(Map<String, Object> payload) {
        Object ffObj = payload.get("formFields");
        if (ffObj instanceof Map<?, ?> ff) {
            for (String key : List.of("SO Number", "Order No", "Purchase Order No", "SO")) {
                Object so = ff.get(key);
                if (so != null && !so.toString().trim().isEmpty()) {
                    return so.toString().trim();
                }
            }
        }
        Object direct = payload.get("salesOrderNumber");
        if (direct != null && !direct.toString().trim().isEmpty()) {
            return direct.toString().trim();
        }
        Object fnObj = payload.get("analyzedFileName");
        if (fnObj != null) {
            String fn = fnObj.toString().trim();
            int idx = fn.indexOf('_');
            if (idx > 0) {
                String prefix = fn.substring(0, idx);
                if (prefix.matches("\\d+(-\\d+)?")) {
                    int dashIdx = prefix.indexOf('-');
                    return dashIdx > 0 ? prefix.substring(0, dashIdx) : prefix;
                }
            }
        }
        return null;
    }

    public void mergeHeaderFields(SoHeader header, Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> ff = (Map<String, Object>) payload.get("formFields");
        if (ff == null) return;

        mergeOrderDate(header, str(ff.get("Order Date")), str(ff.get("Date (ISO)")), str(ff.get("Date")));
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
        mergeField(header::getTimeOfDelivery, header::setTimeOfDelivery, str(ff.get("Time of Delivery")));
        mergeField(header::getNoOfPieces, header::setNoOfPieces, str(ff.get("No of Pieces")));
        mergeField(header::getSalesMode, header::setSalesMode, str(ff.get("Sales Models")), str(ff.get("Sales Mode")));
        mergeField(header::getPtProdNo, header::setPtProdNo, str(ff.get("PT Prod No")));
    }

    public void logPotentialHeaderLengthIssues(SoHeader header) {
        logIfTooLong("so_number", header.getSoNumber(), 64);
        logIfTooLong("workflow_status", header.getWorkflowStatus() == null ? null : header.getWorkflowStatus().name(), 32);
        logIfTooLong("season", header.getSeason(), 255);
        logIfTooLong("supplier_code", header.getSupplierCode(), 255);
        logIfTooLong("supplier_name", header.getSupplierName(), 255);
        logIfTooLong("product_no", header.getProductNo(), 255);
        logIfTooLong("product_name", header.getProductName(), 255);
        logIfTooLong("product_type", header.getProductType(), 255);
        logIfTooLong("option_no", header.getOptionNo(), 255);
        logIfTooLong("development_no", header.getDevelopmentNo(), 255);
        logIfTooLong("customer_group", header.getCustomerGroup(), 255);
        logIfTooLong("type_of_construction", header.getTypeOfConstruction(), 255);
        logIfTooLong("country_of_production", header.getCountryOfProduction(), 255);
        logIfTooLong("country_of_origin", header.getCountryOfOrigin(), 255);
        logIfTooLong("country_of_delivery", header.getCountryOfDelivery(), 255);
        logIfTooLong("terms_of_payment", header.getTermsOfPayment(), 255);
        logIfTooLong("terms_of_delivery", header.getTermsOfDelivery(), 255);
        logIfTooLong("time_of_delivery", header.getTimeOfDelivery(), 255);
        logIfTooLong("no_of_pieces", header.getNoOfPieces(), 32);
        logIfTooLong("sales_mode", header.getSalesMode(), 255);
        logIfTooLong("pt_prod_no", header.getPtProdNo(), 255);
    }

    public void ensureMasterSizes(Map<String, Object> payload) {
        Set<String> labels = new LinkedHashSet<>();
        collectSizeLabels(labels, getListOfMaps(payload, "salesOrderDetailSizeBreakdown"));
        collectSizeLabels(labels, getListOfMaps(payload, "section2cColourSizeBreakdown"));
        for (String label : labels) {
            MasterSizeUpsertRequest request = new MasterSizeUpsertRequest();
            request.setLabel(label);
            try {
                masterSizeService.upsertByLabel(request);
            } catch (Exception e) {
                log.warn("[MASTER-SIZE][ENSURE] failed label='{}': {}", label, e.getMessage());
            }
        }
    }

    public Map<String, Object> buildCountryBreakdownMerged(Map<String, Object> payload) {
        Object cb = payload.get("totalCountryBreakdown");
        Object cs = payload.get("section2cColourSizeBreakdown");
        if (cb == null && cs == null) return null;
        return Map.of(
                "totalCountryBreakdown", cb != null ? cb : List.of(),
                "section2cColourSizeBreakdown", cs != null ? cs : List.of()
        );
    }

    private void mergeOrderDate(SoHeader header, String... candidates) {
        if (header.getOrderDate() != null) return;
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                LocalDate parsed = parseDateSafe(c);
                if (parsed != null) {
                    header.setOrderDate(parsed);
                    return;
                }
            }
        }
    }

    private LocalDate parseDateSafe(String s) {
        if (s == null || s.isBlank()) return null;
        DateTimeFormatter[] formats = {
                DateTimeFormatter.ISO_LOCAL_DATE,
                DateTimeFormatter.ofPattern("dd/MM/yyyy"),
                DateTimeFormatter.ofPattern("MM/dd/yyyy"),
                DateTimeFormatter.ofPattern("dd-MM-yyyy"),
                DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        };
        for (DateTimeFormatter fmt : formats) {
            try {
                return LocalDate.parse(s.trim(), fmt);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private void mergeField(Supplier<String> getter,
                            Consumer<String> setter,
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

    private void logIfTooLong(String column, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            log.warn("[SO_HEADER][LENGTH] column={} length={} max={} preview='{}'",
                    column, value.length(), maxLength, value.substring(0, Math.min(value.length(), 120)));
        }
    }

    private void collectSizeLabels(Set<String> out, List<Map<String, Object>> rows) {
        for (Map<String, Object> row : rows) {
            String size = str(row.get("size"));
            if (!size.isBlank()) out.add(size);
            for (String key : row.keySet()) {
                if (isSizeMetaKey(key)) continue;
                String label = str(key);
                if (!label.isBlank()) out.add(label);
            }
        }
    }

    private boolean isSizeMetaKey(String key) {
        if (key == null) return true;
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "type", "countryofdestination", "destinationcountry", "articleno", "article",
                    "color", "colour", "total", "noofasst", "size", "qty" -> true;
            default -> false;
        };
    }

    public String str(Object o) {
        return o == null ? "" : o.toString().trim();
    }

    public Integer toInt(Object o) {
        if (o == null) return null;
        String s = o.toString().replaceAll("[^0-9]", "");
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public int parseIntSafe(String s) {
        if (s == null || s.isBlank()) return 0;
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public String toJson(Object o) {
        if (o == null) return null;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getListOfMaps(Map<String, Object> payload, String key) {
        Object obj = payload.get(key);
        if (obj instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getListOfMapsFromObj(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<Map<String, Object>>) (List<?>) list;
        }
        return List.of();
    }

    public List<?> getListRaw(Map<String, Object> payload, String key) {
        Object obj = payload.get(key);
        if (obj instanceof List<?> list) {
            return list;
        }
        return List.of();
    }
}

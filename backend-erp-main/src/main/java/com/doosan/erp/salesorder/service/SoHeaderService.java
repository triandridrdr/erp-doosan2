package com.doosan.erp.salesorder.service;

import com.doosan.erp.salesorder.dto.SoHeaderResponse;
import com.doosan.erp.salesorder.entity.*;
import com.doosan.erp.salesorder.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SoHeaderService {

    private final SoHeaderRepository headerRepo;
    private final SoScanSupplementaryRepository scanSuppRepo;
    private final SoScanPoRepository scanPoRepo;
    private final SoScanSizeBreakdownRepository scanSbRepo;
    private final SoScanCountryBreakdownRepository scanCbRepo;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SoHeaderResponse> listAll() {
        return headerRepo.findByDeletedFalseOrderByCreatedAtDesc()
                .stream()
                .map(SoHeaderResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public SoHeaderResponse getById(Long id) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        return SoHeaderResponse.from(header);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getReviewById(Long id) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("id", header.getId());
        review.put("salesOrderNumber", header.getSoNumber());
        review.put("analyzedFileName", latestFileName(header.getId()));
        review.put("createdAt", header.getCreatedAt());
        review.put("createdBy", header.getCreatedBy());
        review.put("payloadJson", toJson(buildPayload(header)));
        return review;
    }

    @Transactional(readOnly = true)
    public SoHeaderResponse getBySoNumber(String soNumber) {
        SoHeader header = headerRepo.findBySoNumberAndDeletedFalse(soNumber)
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + soNumber));
        return SoHeaderResponse.from(header);
    }

    @Transactional(readOnly = true)
    public boolean scanExistsBySoNumberAndDocumentType(String soNumber, String documentType) {
        return headerRepo.findBySoNumberAndDeletedFalse(soNumber)
                .map(header -> switch (documentType) {
                    case "purchase-order" -> latestPurchaseOrder(header.getId()).isPresent();
                    case "supplementary" -> latestSupplementary(header.getId()).isPresent();
                    case "size-per-colour-breakdown" -> latestSizeBreakdown(header.getId()).isPresent();
                    case "total-country-breakdown" -> latestCountryBreakdown(header.getId()).isPresent();
                    default -> false;
                })
                .orElse(false);
    }

    @Transactional
    public SoHeaderResponse updateWorkflowStatus(Long id, SoWorkflowStatus newStatus) {
        SoHeader header = headerRepo.findById(id)
                .filter(h -> !h.getDeleted())
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        header.setWorkflowStatus(newStatus);
        header = headerRepo.save(header);
        log.info("[WORKFLOW] SO={} status changed to {}", header.getSoNumber(), newStatus);
        return SoHeaderResponse.from(header);
    }

    @Transactional
    public void hardDelete(Long id) {
        SoHeader header = headerRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("SO Header not found: " + id));
        String soNumber = header.getSoNumber();
        headerRepo.delete(header);
        log.info("[DELETE] SO={} hard deleted", soNumber);
    }

    private Map<String, Object> buildPayload(SoHeader h) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> ff = new LinkedHashMap<>();
        ff.put("SO Number", h.getSoNumber());
        ff.put("Order No", h.getSoNumber());
        ff.put("Date (ISO)", h.getOrderDate());
        ff.put("Order Date", h.getOrderDate());
        ff.put("Season", h.getSeason());
        ff.put("Supplier Code", h.getSupplierCode());
        ff.put("Supplier", h.getSupplierName());
        ff.put("Article / Product No", h.getProductNo());
        ff.put("Product No", h.getProductNo());
        ff.put("Product Name", h.getProductName());
        ff.put("Product Desc", h.getProductDesc());
        ff.put("Product Type", h.getProductType());
        ff.put("Option No", h.getOptionNo());
        ff.put("Customs Customer Group", h.getCustomerGroup());
        ff.put("Customer Group", h.getCustomerGroup());
        ff.put("Type of Construction", h.getTypeOfConstruction());
        ff.put("Type of Construct", h.getTypeOfConstruction());
        ff.put("Development No", h.getDevelopmentNo());
        ff.put("Terms of Delivery", h.getTermsOfDelivery());
        ff.put("Time of Delivery", h.getTimeOfDelivery());
        ff.put("Country of Production", h.getCountryOfProduction());
        ff.put("Country of Bakery", h.getCountryOfDelivery());
        ff.put("Country of Origin", h.getCountryOfOrigin());
        ff.put("Term of Payment", h.getTermsOfPayment());
        ff.put("No of Pieces", h.getNoOfPieces());
        ff.put("Sales Models", h.getSalesMode());
        ff.put("PT Prod No", h.getPtProdNo());
        payload.put("formFields", ff);

        latestSupplementary(h.getId()).ifPresent(scan -> payload.put("bomDraftRows", scan.getBomItems().stream()
                .sorted(Comparator.comparing(SoSupplementaryBom::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                .map(r -> mapOf(
                        "position", r.getPosition(),
                        "placement", r.getPlacement(),
                        "type", r.getType(),
                        "description", r.getDescription(),
                        "composition", r.getComposition(),
                        "materialSupplier", r.getMaterialSupplier()
                ))
                .toList()));

        latestSupplementary(h.getId()).ifPresent(scan -> {
            payload.put("bomProdUnitsRows", scan.getProdUnits().stream()
                    .sorted(Comparator.comparing(SoSupplementaryBomProdUnit::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "position", r.getPosition(),
                            "placement", r.getPlacement(),
                            "type", r.getType(),
                            "materialSupplier", r.getMaterialSupplier(),
                            "composition", r.getComposition(),
                            "weight", r.getWeight(),
                            "productionUnitProcessingCapability", r.getProductionUnitProcessingCapability()
                    ))
                    .toList());
            payload.put("bomYarnSourceTableRows", scan.getYarnSources().stream()
                    .sorted(Comparator.comparing(SoSupplementaryYarnSource::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> fromJson(r.getRowData()))
                    .toList());
            payload.put("productArticleTableRows", scan.getProductArticles().stream()
                    .sorted(Comparator.comparing(SoSupplementaryProductArticle::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> fromJson(r.getRowData()))
                    .toList());
            payload.put("miscellaneousTableRows", scan.getMiscellaneous().stream()
                    .sorted(Comparator.comparing(SoSupplementaryMiscellaneous::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> fromJson(r.getRowData()))
                    .toList());
        });

        latestSizeBreakdown(h.getId()).ifPresent(scan -> payload.put("salesOrderDetailSizeBreakdown", scan.getBreakdowns().stream()
                .sorted(Comparator.comparing(SoSizeBreakdown::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                .flatMap(b -> b.getDetails().stream()
                        .sorted(Comparator.comparing(SoSizeBreakdownDetail::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                        .map(d -> mapOf(
                                "countryOfDestination", b.getCountryOfDestination(),
                                "type", b.getType(),
                                "color", b.getColor(),
                                "size", d.getSizeLabel(),
                                "qty", d.getQuantity(),
                                "total", b.getTotal(),
                                "noOfAsst", b.getNoOfAsst()
                        )))
                .toList()));

        latestCountryBreakdown(h.getId()).ifPresent(scan -> {
            payload.put("totalCountryBreakdown", scan.getCountryBreakdowns().stream()
                    .sorted(Comparator.comparing(SoCountryBreakdown::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "country", r.getCountry(),
                            "pmCode", r.getPmCode(),
                            "total", r.getTotal()
                    ))
                    .toList());
            payload.put("section2cColourSizeBreakdown", scan.getColourSizeBreakdowns().stream()
                    .sorted(Comparator.comparing(SoColourSizeBreakdown::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "article", r.getArticle(),
                            "size", r.getSizeLabel(),
                            "qty", r.getQuantity()
                    ))
                    .toList());
        });

        latestPurchaseOrder(h.getId()).ifPresent(scan -> {
            payload.put("quantityPerArticleRows", scan.getItems().stream()
                    .sorted(Comparator.comparing(SoPoItem::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "page", r.getPageNumber(),
                            "articleNo", r.getArticleNo(),
                            "hmColourCode", r.getHmColourCode(),
                            "ptArticleNumber", r.getPtArticleNumber(),
                            "colour", r.getColour(),
                            "optionNo", r.getOptionNo(),
                            "cost", r.getCost(),
                            "qtyArticle", r.getQtyArticle(),
                            "graphicalAppearance", r.getGraphicalAppearance()
                    ))
                    .toList());
            payload.put("timeOfDeliveryRows", scan.getTimeOfDeliveries().stream()
                    .sorted(Comparator.comparing(SoPoTimeOfDelivery::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "page", r.getPageNumber(),
                            "timeOfDelivery", r.getTimeOfDelivery(),
                            "planningMarkets", r.getPlanningMarkets(),
                            "quantity", r.getQuantity(),
                            "percentTotalQty", r.getPercentTotalQty()
                    ))
                    .toList());
            payload.put("invoiceAvgPriceRows", scan.getInvoiceAvgPrices().stream()
                    .sorted(Comparator.comparing(SoPoInvoiceAvgPrice::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "page", r.getPageNumber(),
                            "invoiceAveragePrice", r.getInvoiceAvgPrice(),
                            "country", r.getCountry()
                    ))
                    .toList());
            payload.put("salesSampleRows", scan.getSalesSamples().stream()
                    .sorted(Comparator.comparing(SoPoSalesSample::getSortOrder, Comparator.nullsLast(Integer::compareTo)))
                    .map(r -> mapOf(
                            "page", r.getPageNumber(),
                            "articleNo", r.getArticleNo(),
                            "hmColourCode", r.getHmColourCode(),
                            "ptArticleNumber", r.getPtArticleNumber(),
                            "colour", r.getColour(),
                            "size", r.getSize(),
                            "qty", r.getQty(),
                            "timeOfDelivery", r.getTimeOfDelivery(),
                            "destinationStudio", r.getDestinationStudio(),
                            "salesSampleTerms", r.getSalesSampleTerms(),
                            "destinationStudioAddress", r.getDestinationStudioAddress()
                    ))
                    .toList());
        });

        return payload;
    }

    private java.util.Optional<SoScanSupplementary> latestSupplementary(Long headerId) {
        return scanSuppRepo.findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
    }

    private java.util.Optional<SoScanPo> latestPurchaseOrder(Long headerId) {
        return scanPoRepo.findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
    }

    private java.util.Optional<SoScanSizeBreakdown> latestSizeBreakdown(Long headerId) {
        return scanSbRepo.findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
    }

    private java.util.Optional<SoScanCountryBreakdown> latestCountryBreakdown(Long headerId) {
        return scanCbRepo.findFirstBySoHeaderIdAndDeletedFalseOrderByRevisionDesc(headerId);
    }

    private String latestFileName(Long headerId) {
        return List.of(
                        latestPurchaseOrder(headerId).map(SoScanPo::getFileName).orElse(""),
                        latestSupplementary(headerId).map(SoScanSupplementary::getFileName).orElse(""),
                        latestSizeBreakdown(headerId).map(SoScanSizeBreakdown::getFileName).orElse(""),
                        latestCountryBreakdown(headerId).map(SoScanCountryBreakdown::getFileName).orElse("")
                ).stream()
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse("");
    }

    private Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toString(), kv[i + 1]);
        }
        return m;
    }

    private String toJson(Object v) {
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Object fromJson(String v) {
        try {
            return objectMapper.readValue(v, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}

package com.doosan.erp.salesorder.dto;

import com.doosan.erp.salesorder.entity.SoHeader;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class SoHeaderResponse {
    private Long id;
    private String soNumber;
    private String workflowStatus;
    private String orderDate;
    private String season;
    private String supplierCode;
    private String supplierName;
    private String productNo;
    private String productName;
    private String productDesc;
    private String productType;
    private String optionNo;
    private String developmentNo;
    private String customerGroup;
    private String typeOfConstruction;
    private String countryOfProduction;
    private String countryOfOrigin;
    private String countryOfDelivery;
    private String termsOfPayment;
    private String termsOfDelivery;
    private String noOfPieces;
    private String salesMode;
    private String ptProdNo;
    private Integer revision;
    private Boolean hasSupplementary;
    private Boolean hasPurchaseOrder;
    private Boolean hasSizeBreakdown;
    private Boolean hasCountryBreakdown;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private String createdBy;

    public static SoHeaderResponse from(SoHeader h) {
        return SoHeaderResponse.builder()
                .id(h.getId())
                .soNumber(h.getSoNumber())
                .workflowStatus(h.getWorkflowStatus().name())
                .orderDate(h.getOrderDate())
                .season(h.getSeason())
                .supplierCode(h.getSupplierCode())
                .supplierName(h.getSupplierName())
                .productNo(h.getProductNo())
                .productName(h.getProductName())
                .productDesc(h.getProductDesc())
                .productType(h.getProductType())
                .optionNo(h.getOptionNo())
                .developmentNo(h.getDevelopmentNo())
                .customerGroup(h.getCustomerGroup())
                .typeOfConstruction(h.getTypeOfConstruction())
                .countryOfProduction(h.getCountryOfProduction())
                .countryOfOrigin(h.getCountryOfOrigin())
                .countryOfDelivery(h.getCountryOfDelivery())
                .termsOfPayment(h.getTermsOfPayment())
                .termsOfDelivery(h.getTermsOfDelivery())
                .noOfPieces(h.getNoOfPieces())
                .salesMode(h.getSalesMode())
                .ptProdNo(h.getPtProdNo())
                .revision(h.getRevision())
                .hasSupplementary(!h.getSupplementaryScans().isEmpty())
                .hasPurchaseOrder(!h.getPoScans().isEmpty())
                .hasSizeBreakdown(!h.getSizeBreakdownScans().isEmpty())
                .hasCountryBreakdown(!h.getCountryBreakdownScans().isEmpty())
                .createdAt(h.getCreatedAt())
                .updatedAt(h.getUpdatedAt())
                .createdBy(h.getCreatedBy())
                .build();
    }
}

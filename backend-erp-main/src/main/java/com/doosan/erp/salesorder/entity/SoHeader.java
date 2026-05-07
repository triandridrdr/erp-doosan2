package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "so_header")
@Getter
@Setter
public class SoHeader extends BaseEntity {

    @Column(name = "so_number", nullable = false, unique = true, length = 64)
    private String soNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "workflow_status", nullable = false, length = 32)
    private SoWorkflowStatus workflowStatus = SoWorkflowStatus.DRAFT_OCR;

    @Column(name = "order_date", length = 255)
    private String orderDate;

    @Column(name = "season", length = 255)
    private String season;

    @Column(name = "supplier_code", length = 255)
    private String supplierCode;

    @Column(name = "supplier_name", length = 255)
    private String supplierName;

    @Column(name = "product_no", length = 255)
    private String productNo;

    @Column(name = "product_name", length = 255)
    private String productName;

    @Column(name = "product_desc", columnDefinition = "TEXT")
    private String productDesc;

    @Column(name = "product_type", length = 255)
    private String productType;

    @Column(name = "option_no", length = 255)
    private String optionNo;

    @Column(name = "development_no", length = 255)
    private String developmentNo;

    @Column(name = "customer_group", length = 255)
    private String customerGroup;

    @Column(name = "type_of_construction", length = 255)
    private String typeOfConstruction;

    @Column(name = "country_of_production", length = 255)
    private String countryOfProduction;

    @Column(name = "country_of_origin", length = 255)
    private String countryOfOrigin;

    @Column(name = "country_of_delivery", length = 255)
    private String countryOfDelivery;

    @Column(name = "terms_of_payment", length = 255)
    private String termsOfPayment;

    @Column(name = "terms_of_delivery", columnDefinition = "TEXT")
    private String termsOfDelivery;

    @Column(name = "no_of_pieces", length = 32)
    private String noOfPieces;

    @Column(name = "sales_mode", length = 255)
    private String salesMode;

    @Column(name = "pt_prod_no", length = 255)
    private String ptProdNo;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanSupplementary> supplementaryScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanPo> poScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanSizeBreakdown> sizeBreakdownScans = new ArrayList<>();

    @OneToMany(mappedBy = "soHeader", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoScanCountryBreakdown> countryBreakdownScans = new ArrayList<>();
}

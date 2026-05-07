package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "so_scan_po")
@Getter
@Setter
public class SoScanPo extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "file_hash", length = 128)
    private String fileHash;

    @Column(name = "revision", nullable = false)
    private Integer revision = 1;

    @Column(name = "scan_status", nullable = false, length = 32)
    private String scanStatus = "COMPLETED";

    @Column(name = "ocr_raw_jsonb", columnDefinition = "TEXT")
    @Lob
    private String ocrRawJsonb;

    @Column(name = "ocr_confidence")
    private BigDecimal ocrConfidence;

    @Column(name = "page_count")
    private Integer pageCount;

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoTimeOfDelivery> timeOfDeliveries = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoInvoiceAvgPrice> invoiceAvgPrices = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoTermsOfDelivery> termsOfDeliveries = new ArrayList<>();

    @OneToMany(mappedBy = "scan", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoPoSalesSample> salesSamples = new ArrayList<>();
}

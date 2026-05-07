package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_po_sales_sample")
@Getter
@Setter
public class SoPoSalesSample extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanPo scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "page_number")
    private Integer pageNumber;

    @Column(name = "article_no", length = 64)
    private String articleNo;

    @Column(name = "hm_colour_code", length = 64)
    private String hmColourCode;

    @Column(name = "pt_article_number", length = 64)
    private String ptArticleNumber;

    @Column(name = "colour", length = 64)
    private String colour;

    @Column(name = "size", length = 32)
    private String size;

    @Column(name = "qty", length = 32)
    private String qty;

    @Column(name = "time_of_delivery", length = 128)
    private String timeOfDelivery;

    @Column(name = "destination_studio", length = 256)
    private String destinationStudio;

    @Column(name = "sales_sample_terms", columnDefinition = "TEXT")
    private String salesSampleTerms;

    @Column(name = "destination_studio_address", columnDefinition = "TEXT")
    private String destinationStudioAddress;
}

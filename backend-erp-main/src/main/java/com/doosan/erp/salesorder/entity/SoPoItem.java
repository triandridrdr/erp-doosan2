package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_po_item")
@Getter
@Setter
public class SoPoItem extends BaseEntity {

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

    @Column(name = "option_no", length = 64)
    private String optionNo;

    @Column(name = "cost", length = 32)
    private String cost;

    @Column(name = "qty_article", length = 32)
    private String qtyArticle;
}

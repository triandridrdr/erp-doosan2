package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_colour_size_breakdown")
@Getter
@Setter
public class SoColourSizeBreakdown extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanCountryBreakdown scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "article", length = 128)
    private String article;

    @Column(name = "size_label", nullable = false, length = 32)
    private String sizeLabel;

    @Column(name = "quantity", length = 32)
    private String quantity;
}

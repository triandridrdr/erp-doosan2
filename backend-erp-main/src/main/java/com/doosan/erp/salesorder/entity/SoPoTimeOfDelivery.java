package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_po_time_of_delivery")
@Getter
@Setter
public class SoPoTimeOfDelivery extends BaseEntity {

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

    @Column(name = "time_of_delivery", length = 256)
    private String timeOfDelivery;

    @Column(name = "planning_markets", length = 256)
    private String planningMarkets;

    @Column(name = "quantity", length = 32)
    private String quantity;

    @Column(name = "percent_total_qty", length = 16)
    private String percentTotalQty;
}

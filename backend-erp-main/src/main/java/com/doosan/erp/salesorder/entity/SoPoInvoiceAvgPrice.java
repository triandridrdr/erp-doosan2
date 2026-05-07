package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_po_invoice_avg_price")
@Getter
@Setter
public class SoPoInvoiceAvgPrice extends BaseEntity {

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

    @Column(name = "invoice_avg_price", length = 64)
    private String invoiceAvgPrice;

    @Column(name = "country", length = 128)
    private String country;
}

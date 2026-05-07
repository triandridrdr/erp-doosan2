package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_po_terms_of_delivery")
@Getter
@Setter
public class SoPoTermsOfDelivery extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanPo scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "terms_of_delivery", columnDefinition = "TEXT")
    private String termsOfDelivery;
}

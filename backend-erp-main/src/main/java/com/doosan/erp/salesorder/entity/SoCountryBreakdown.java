package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_country_breakdown")
@Getter
@Setter
public class SoCountryBreakdown extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanCountryBreakdown scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "country", nullable = false, length = 128)
    private String country;

    @Column(name = "pm_code", length = 32)
    private String pmCode;

    @Column(name = "total", length = 32)
    private String total;
}

package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_supplementary_miscellaneous")
@Getter
@Setter
public class SoSupplementaryMiscellaneous extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanSupplementary scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "row_data", columnDefinition = "TEXT", nullable = false)
    @Lob
    private String rowData;
}

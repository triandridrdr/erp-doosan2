package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_supplementary_bom_prod_unit")
@Getter
@Setter
public class SoSupplementaryBomProdUnit extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanSupplementary scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "position", length = 32)
    private String position;

    @Column(name = "placement", length = 128)
    private String placement;

    @Column(name = "type", length = 64)
    private String type;

    @Column(name = "material_supplier", length = 256)
    private String materialSupplier;

    @Column(name = "composition", columnDefinition = "TEXT")
    private String composition;

    @Column(name = "weight", length = 64)
    private String weight;

    @Column(name = "production_unit_processing_capability", columnDefinition = "TEXT")
    private String productionUnitProcessingCapability;
}

package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_supplementary_bom")
@Getter
@Setter
public class SoSupplementaryBom extends BaseEntity {

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

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "material_appearance", length = 256)
    private String materialAppearance;

    @Column(name = "composition", columnDefinition = "TEXT")
    private String composition;

    @Column(name = "construction", length = 256)
    private String construction;

    @Column(name = "consumption", length = 64)
    private String consumption;

    @Column(name = "weight", length = 64)
    private String weight;

    @Column(name = "component_treatments", columnDefinition = "TEXT")
    private String componentTreatments;

    @Column(name = "material_supplier", length = 256)
    private String materialSupplier;

    @Column(name = "supplier_article", length = 128)
    private String supplierArticle;

    @Column(name = "booking_id", length = 64)
    private String bookingId;

    @Column(name = "demand_id", length = 64)
    private String demandId;
}

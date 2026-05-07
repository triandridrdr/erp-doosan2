package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "so_size_breakdown")
@Getter
@Setter
public class SoSizeBreakdown extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scan_id", nullable = false)
    private SoScanSizeBreakdown scan;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "so_header_id", nullable = false)
    private SoHeader soHeader;

    @Column(name = "country_of_destination", nullable = false, length = 128)
    private String countryOfDestination;

    @Column(name = "type", nullable = false, length = 32)
    private String type;

    @Column(name = "color", length = 64)
    private String color;

    @Column(name = "no_of_asst", length = 32)
    private String noOfAsst;

    @Column(name = "total", length = 32)
    private String total;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @OneToMany(mappedBy = "breakdown", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SoSizeBreakdownDetail> details = new ArrayList<>();
}

package com.doosan.erp.salesorder.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "so_size_breakdown_detail")
@Getter
@Setter
public class SoSizeBreakdownDetail extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "breakdown_id", nullable = false)
    private SoSizeBreakdown breakdown;

    @Column(name = "size_label", nullable = false, length = 16)
    private String sizeLabel;

    @Column(name = "quantity", nullable = false)
    private Integer quantity = 0;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;
}

package com.doosan.erp.master.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Master table for clothing sizes used across the ERP UI (autocomplete
 * dropdowns in Sales Order Detail, Size/Colour Breakdown, etc.).
 *
 * The label column stores the value as shown to the user (e.g. "XS",
 * "0-1M (50)*", "1½-2Y (92)*"). The normalizedLabel column is the key
 * used for uniqueness/lookups so that variants like "xs", "XS ", "XS*"
 * don't create duplicate rows.
 */
@Entity
@Table(
        name = "master_sizes",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_master_sizes_normalized", columnNames = "normalized_label")
        },
        indexes = {
                @Index(name = "idx_master_sizes_active_sort", columnList = "active, sort_order")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class MasterSize extends BaseEntity {

    /** Human-readable label (preserve casing/punctuation as seen on the PDF). */
    @Column(name = "label", nullable = false, length = 100)
    private String label;

    /** Uppercase, whitespace/asterisk-stripped form used for uniqueness. */
    @Column(name = "normalized_label", nullable = false, length = 100)
    private String normalizedLabel;

    /** Display order in dropdowns (smaller = earlier). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** Soft disable without deleting (kept for historical references). */
    @Column(name = "active", nullable = false)
    private Boolean active = true;
}

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
        name = "mst_size",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_mst_size_company_code", columnNames = {"company_id", "size_code"}),
                @UniqueConstraint(name = "uq_mst_size_company_normalized", columnNames = {"company_id", "normalized_label"})
        },
        indexes = {
                @Index(name = "idx_mst_size_active_sort", columnList = "company_id, is_active, deleted, sort_order")
        }
)
@Getter
@Setter
@NoArgsConstructor
public class MasterSize extends BaseEntity {

    @Column(name = "company_id", nullable = false, length = 10)
    private String companyId = "DSS";

    @Column(name = "size_code", nullable = false, length = 40)
    private String sizeCode;

    /** Human-readable label (preserve casing/punctuation as seen on the PDF). */
    @Column(name = "size_name", nullable = false, length = 100)
    private String label;

    /** Uppercase, whitespace/asterisk-stripped form used for uniqueness. */
    @Column(name = "normalized_label", nullable = false, length = 100)
    private String normalizedLabel;

    /** Display order in dropdowns (smaller = earlier). */
    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    /** Soft disable without deleting (kept for historical references). */
    @Column(name = "is_active", nullable = false)
    private Boolean active = true;

    @Column(name = "size_national_code", length = 10)
    private String sizeNationalCode = "GLOBAL";

    @Column(name = "size_group", length = 10)
    private String sizeGroup = "OCR";

    @Column(name = "memo", columnDefinition = "TEXT")
    private String memo;
}

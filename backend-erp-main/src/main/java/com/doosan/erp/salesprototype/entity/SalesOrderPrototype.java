package com.doosan.erp.salesprototype.entity;

import com.doosan.erp.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "sales_order_prototype")
@Getter
@Setter
public class SalesOrderPrototype extends BaseEntity {

    @Column(name = "sales_order_number", length = 64)
    private String salesOrderNumber;

    @Column(name = "analyzed_file_name", length = 255)
    private String analyzedFileName;

    @Lob
    @Column(name = "payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String payloadJson;

    // Document type tracking
    @Column(name = "purchase_order_uploaded")
    private Boolean purchaseOrderUploaded = false;

    @Column(name = "supplementary_uploaded")
    private Boolean supplementaryUploaded = false;

    @Column(name = "size_per_colour_uploaded")
    private Boolean sizePerColourUploaded = false;

    @Column(name = "total_country_uploaded")
    private Boolean totalCountryUploaded = false;

    // Separate JSON storage per document type
    @Lob
    @Column(name = "purchase_order_json", columnDefinition = "LONGTEXT")
    private String purchaseOrderJson;

    @Lob
    @Column(name = "supplementary_json", columnDefinition = "LONGTEXT")
    private String supplementaryJson;

    @Lob
    @Column(name = "size_per_colour_json", columnDefinition = "LONGTEXT")
    private String sizePerColourJson;

    @Lob
    @Column(name = "total_country_json", columnDefinition = "LONGTEXT")
    private String totalCountryJson;
}

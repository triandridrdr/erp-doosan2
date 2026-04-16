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
}

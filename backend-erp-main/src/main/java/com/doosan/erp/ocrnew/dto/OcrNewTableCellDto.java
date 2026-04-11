package com.doosan.erp.ocrnew.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrNewTableCellDto {

    private Integer rowIndex;

    private Integer columnIndex;

    private String text;

    private OcrNewBoundingBoxDto boundingBox;

    private Float confidence;
}

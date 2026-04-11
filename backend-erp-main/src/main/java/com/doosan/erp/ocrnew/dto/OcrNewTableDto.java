package com.doosan.erp.ocrnew.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrNewTableDto {

    private Integer page;

    private Integer index;

    private Integer rowCount;

    private Integer columnCount;

    private List<OcrNewTableCellDto> cells;

    private List<List<String>> rows;
}

package com.doosan.erp.master.dto;

import com.doosan.erp.master.entity.MasterSize;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MasterSizeDto {

    private Long id;
    private String label;
    private String normalizedLabel;
    private Integer sortOrder;
    private Boolean active;

    public static MasterSizeDto from(MasterSize entity) {
        if (entity == null) return null;
        return MasterSizeDto.builder()
                .id(entity.getId())
                .label(entity.getLabel())
                .normalizedLabel(entity.getNormalizedLabel())
                .sortOrder(entity.getSortOrder())
                .active(entity.getActive())
                .build();
    }
}

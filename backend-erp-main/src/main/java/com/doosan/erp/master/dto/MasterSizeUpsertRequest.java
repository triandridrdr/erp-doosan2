package com.doosan.erp.master.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for creating or upserting a master size entry.
 * Only {@link #label} is required; the server derives the normalized form.
 */
@Getter
@Setter
@NoArgsConstructor
public class MasterSizeUpsertRequest {

    @NotBlank
    @Size(max = 100)
    private String label;

    /** Optional explicit sort position; server fills a reasonable default if null. */
    private Integer sortOrder;

    private Boolean active;
}

package com.doosan.erp.ocrnew.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class OcrNewWord {

    private final String text;

    private final int page;

    private final int left;

    private final int top;

    private final int right;

    private final int bottom;

    private final float confidence;
}

package com.doosan.erp.ocrnew.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
public class OcrNewLine {

    private final int page;

    private final String text;

    private final int left;

    private final int top;

    private final int right;

    private final int bottom;

    private final float confidence;

    private final List<OcrNewWord> words;
}

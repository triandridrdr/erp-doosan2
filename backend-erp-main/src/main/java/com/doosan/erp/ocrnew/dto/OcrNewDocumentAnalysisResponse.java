package com.doosan.erp.ocrnew.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OcrNewDocumentAnalysisResponse {

    private String extractedText;

    private List<OcrNewLineDto> lines;

    private List<OcrNewTableDto> tables;

    private List<OcrNewKeyValuePairDto> keyValuePairs;

    private Map<String, String> formFields;

    private List<Map<String, String>> salesOrderDetailSizeBreakdown;

    private List<Map<String, String>> totalCountryBreakdown;

    /**
     * Colour / Size breakdown sub-table (typically present on TotalCountryBreakdown PDFs).
     * Each row is a map of: { article, "<sizeKey>": qty, ..., total }.
     * The first row is the article line, an optional second row aggregates the column totals.
     */
    private List<Map<String, String>> colourSizeBreakdown;

    private Float averageConfidence;

    private Integer pageCount;

    /**
     * Purchase Order: Time of Delivery table
     * Columns: timeOfDelivery, planningMarkets, quantity, percentTotalQty
     */
    private List<Map<String, String>> purchaseOrderTimeOfDelivery;

    /**
     * Purchase Order: Quantity per Article table
     * Columns: articleNo, hmColourCode, ptArticleNumber, colour, optionNo, cost, qtyArticle
     */
    private List<Map<String, String>> purchaseOrderQuantityPerArticle;

    /**
     * Purchase Order: Invoice Average Price table
     * Columns: invoiceAveragePrice, country
     */
    private List<Map<String, String>> purchaseOrderInvoiceAvgPrice;

    /**
     * Purchase Order: Terms of Delivery content per page.
     * Row keys: page, termsOfDelivery
     */
    private List<Map<String, String>> purchaseOrderTermsOfDelivery;
}

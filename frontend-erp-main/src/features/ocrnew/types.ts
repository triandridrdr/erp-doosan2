export interface OcrNewBoundingBoxDto {
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface OcrNewLineDto {
  page: number;
  text: string;
  boundingBox: OcrNewBoundingBoxDto;
  confidence: number;
}

export interface OcrNewKeyValuePairDto {
  page: number;
  key: string;
  value: string;
  confidence: number;
}

export interface OcrNewTableCellDto {
  rowIndex: number;
  columnIndex: number;
  text: string;
  boundingBox: OcrNewBoundingBoxDto;
  confidence: number;
}

export interface OcrNewTableDto {
  page: number;
  index: number;
  rowCount: number;
  columnCount: number;
  cells: OcrNewTableCellDto[];
  rows: string[][];
}

export interface OcrNewDocumentAnalysisResponseData {
  extractedText: string;
  lines: OcrNewLineDto[];
  tables: OcrNewTableDto[];
  keyValuePairs: OcrNewKeyValuePairDto[];
  formFields: Record<string, string>;
  salesOrderDetailSizeBreakdown?: Array<Record<string, string>>;
  totalCountryBreakdown?: Array<Record<string, string>>;
  /**
   * Colour / Size breakdown sub-table extracted from a TotalCountryBreakdown PDF.
   * One entry per article row; size column labels (e.g. "XS*", "M/P*") become map keys.
   */
  colourSizeBreakdown?: Array<Record<string, string>>;
  /**
   * Purchase Order: Time of Delivery table
   * Columns: timeOfDelivery, planningMarkets, quantity, percentTotalQty
   */
  purchaseOrderTimeOfDelivery?: Array<Record<string, string>>;
  /**
   * Purchase Order: Quantity per Article table
   * Columns: articleNo, hmColourCode, ptArticleNumber, colour, optionNo, cost, qtyArticle
   */
  purchaseOrderQuantityPerArticle?: Array<Record<string, string>>;
  /**
   * Purchase Order: Invoice Average Price table
   * Columns: invoiceAveragePrice, country
   */
  purchaseOrderInvoiceAvgPrice?: Array<Record<string, string>>;
  averageConfidence: number;
  pageCount: number;
}

export interface OcrNewDocumentAnalysisResponse {
  success: boolean;
  data: OcrNewDocumentAnalysisResponseData;
  message: string;
  timestamp: string;
}

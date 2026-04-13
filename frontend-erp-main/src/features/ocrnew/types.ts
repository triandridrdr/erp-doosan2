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
  averageConfidence: number;
  pageCount: number;
}

export interface OcrNewDocumentAnalysisResponse {
  success: boolean;
  data: OcrNewDocumentAnalysisResponseData;
  message: string;
  timestamp: string;
}

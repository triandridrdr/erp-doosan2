import type { OcrNewDocumentAnalysisResponseData } from '../ocrnew/types';
import { salesOrderApi } from '../salesOrder/api';

const SALES_ORDER_NO_KEYS = ['SO Number', 'Sales Order No', 'Sales Order Number', 'Order No'];

export function extractSalesOrderNumber(data: OcrNewDocumentAnalysisResponseData | null | undefined) {
  const formFields = data?.formFields ?? {};
  for (const key of SALES_ORDER_NO_KEYS) {
    const value = (formFields[key] ?? '').toString().trim();
    if (value) return value;
  }
  return '';
}

export async function salesOrderNumberExists(data: OcrNewDocumentAnalysisResponseData | null | undefined, documentType: string) {
  const soNumber = extractSalesOrderNumber(data);
  if (!soNumber) return false;

  try {
    const res = await salesOrderApi.exists(soNumber, documentType);
    return Boolean(res?.data);
  } catch {
    return false;
  }
}

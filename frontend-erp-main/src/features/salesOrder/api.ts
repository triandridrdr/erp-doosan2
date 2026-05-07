import { client } from '../../api/client';
import type { ApiResponse } from '../../types';

export interface SaveDraftResponse {
  soHeaderId: number;
  soNumber: string;
  scanId: number;
  documentType: string;
  revision: number;
  message: string;
}

export interface SoHeaderResponse {
  id: number;
  soNumber: string;
  workflowStatus: string;
  orderDate?: string;
  season?: string;
  supplierCode?: string;
  supplierName?: string;
  productNo?: string;
  productName?: string;
  productDesc?: string;
  productType?: string;
  optionNo?: string;
  developmentNo?: string;
  customerGroup?: string;
  typeOfConstruction?: string;
  countryOfProduction?: string;
  countryOfOrigin?: string;
  countryOfDelivery?: string;
  termsOfPayment?: string;
  termsOfDelivery?: string;
  noOfPieces?: string;
  salesMode?: string;
  ptProdNo?: string;
  revision: number;
  hasSupplementary: boolean;
  hasPurchaseOrder: boolean;
  hasSizeBreakdown: boolean;
  hasCountryBreakdown: boolean;
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
}

export type SaveDraftRequest = Record<string, unknown>;

export const salesOrderApi = {
  saveDraft: async (payload: SaveDraftRequest) => {
    const response = await client.post<ApiResponse<SaveDraftResponse>>('/api/v1/sales-orders/draft', payload);
    return response.data;
  },

  listAll: async () => {
    const response = await client.get<ApiResponse<SoHeaderResponse[]>>('/api/v1/sales-orders');
    return response.data;
  },

  getById: async (id: number) => {
    const response = await client.get<ApiResponse<SoHeaderResponse>>(`/api/v1/sales-orders/${id}`);
    return response.data;
  },

  getBySoNumber: async (soNumber: string) => {
    const response = await client.get<ApiResponse<SoHeaderResponse>>(`/api/v1/sales-orders/by-so-number/${soNumber}`);
    return response.data;
  },

  updateWorkflowStatus: async (id: number, status: string) => {
    const response = await client.patch<ApiResponse<SoHeaderResponse>>(`/api/v1/sales-orders/${id}/workflow-status`, { status });
    return response.data;
  },

  delete: async (id: number) => {
    const response = await client.delete<ApiResponse<null>>(`/api/v1/sales-orders/${id}`);
    return response.data;
  },
};

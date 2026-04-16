import { client } from '../../api/client';
import type { ApiResponse } from '../../types';

export interface SalesOrderPrototype {
  id: number;
  salesOrderNumber?: string;
  analyzedFileName?: string;
  payloadJson: string;
  createdAt?: string;
  createdBy?: string;
}

export type CreateSalesOrderPrototypeRequest = Record<string, unknown>;

export const salesOrderPrototypeApi = {
  create: async (payload: CreateSalesOrderPrototypeRequest) => {
    const response = await client.post<ApiResponse<SalesOrderPrototype>>('/api/v1/sales-order-prototypes', payload);
    return response.data;
  },
  update: async (id: number, payload: CreateSalesOrderPrototypeRequest) => {
    const response = await client.put<ApiResponse<SalesOrderPrototype>>(`/api/v1/sales-order-prototypes/${id}`, payload);
    return response.data;
  },
  delete: async (id: number) => {
    const response = await client.delete<ApiResponse<null>>(`/api/v1/sales-order-prototypes/${id}`);
    return response.data;
  },
  getAll: async () => {
    const response = await client.get<ApiResponse<SalesOrderPrototype[]>>('/api/v1/sales-order-prototypes');
    return response.data;
  },
  getOne: async (id: number) => {
    const response = await client.get<ApiResponse<SalesOrderPrototype>>(`/api/v1/sales-order-prototypes/${id}`);
    return response.data;
  },
};

import { client } from '../../api/client';
import type { ApiResponse } from '../../types';

export interface MasterSizeDto {
  id: number;
  label: string;
  normalizedLabel: string;
  sortOrder: number;
  active: boolean;
}

export interface MasterSizeUpsertRequest {
  label: string;
  sortOrder?: number;
  active?: boolean;
}

export const masterSizeApi = {
  list: async (opts?: { includeInactive?: boolean }) => {
    const params = opts?.includeInactive ? { all: 'true' } : undefined;
    const response = await client.get<ApiResponse<MasterSizeDto[]>>('/api/v1/master-sizes', { params });
    return response.data;
  },

  getById: async (id: number) => {
    const response = await client.get<ApiResponse<MasterSizeDto>>(`/api/v1/master-sizes/${id}`);
    return response.data;
  },

  upsert: async (request: MasterSizeUpsertRequest) => {
    const response = await client.post<ApiResponse<MasterSizeDto>>('/api/v1/master-sizes', request);
    return response.data;
  },

  update: async (id: number, request: MasterSizeUpsertRequest) => {
    const response = await client.put<ApiResponse<MasterSizeDto>>(`/api/v1/master-sizes/${id}`, request);
    return response.data;
  },

  delete: async (id: number) => {
    const response = await client.delete<ApiResponse<null>>(`/api/v1/master-sizes/${id}`);
    return response.data;
  },
};

/**
 * Canonicalize a raw size label so two variants like `"xs "` and `"XS*"`
 * map to the same key. MUST mirror `MasterSizeService.normalizeLabel` on
 * the backend so idempotent upserts work correctly.
 */
export function normalizeSizeLabel(raw: string | null | undefined): string {
  if (raw == null) return '';
  return String(raw).trim().toUpperCase().replace(/\s+/g, '').replace(/\*/g, '');
}

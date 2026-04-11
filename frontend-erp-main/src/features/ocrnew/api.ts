import { client } from '../../api/client';
import type { OcrNewDocumentAnalysisResponse } from './types';

export const ocrNewApi = {
  analyze: async (file: File) => {
    const formData = new FormData();
    formData.append('file', file);

    const response = await client.post<OcrNewDocumentAnalysisResponse>('/api/v1/ocr-new/analyze', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });

    return response.data;
  },
};

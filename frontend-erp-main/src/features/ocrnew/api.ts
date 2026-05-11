import { client } from '../../api/client';
import type { OcrNewDocumentAnalysisResponse, OcrNewJobStatusResponse } from './types';

export const ocrNewApi = {
  analyze: async (file: File, opts?: { debug?: boolean }) => {
    const formData = new FormData();
    formData.append('file', file);

    const debug = opts?.debug === true;
    const response = await client.post<OcrNewDocumentAnalysisResponse>(`/api/v1/ocr-new/analyze?debug=${debug ? 'true' : 'false'}` , formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
      },
    });

    return response.data;
  },

  submitJob: async (file: File, opts?: { debug?: boolean }) => {
    const formData = new FormData();
    formData.append('file', file);

    const debug = opts?.debug === true;

    const response = await client.post<{ success: boolean; data: string; message: string; timestamp: string }>(
      `/api/v1/ocr-new/jobs?debug=${debug ? 'true' : 'false'}`,
      formData,
      {
        headers: {
          'Content-Type': 'multipart/form-data',
        },
      },
    );

    return response.data;
  },

  getJob: async (jobId: string) => {
    const response = await client.get<OcrNewJobStatusResponse>(`/api/v1/ocr-new/jobs/${jobId}`);
    return response.data;
  },
};

import { useMutation } from '@tanstack/react-query';
import { AlertCircle, Loader2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { ocrNewApi } from './api';
import type { OcrNewDocumentAnalysisResponseData } from './types';

const SALES_ORDER_HEADER_FIELDS = [
  'SO Number',
  'Date (ISO)',
  'Season',
  'Buyer Code',
  'Supplier',
  'Article / Product No',
  'Product Name',
  'Product Type',
  'Customs Customer Group',
  'Type of Construction',
] as const;

export function OcrNewPage() {
  const [selectedFile, setSelectedFile] = useState<File | null>(null);
  const [data, setData] = useState<OcrNewDocumentAnalysisResponseData | null>(null);
  const [error, setError] = useState<string | null>(null);

  const [salesOrderHeaderDraft, setSalesOrderHeaderDraft] = useState<Record<string, string>>({});

  const analyzeMutation = useMutation({
    mutationFn: (file: File) => ocrNewApi.analyze(file),
    onSuccess: (res) => {
      setData(res.data);
      setError(null);

      const ff = res.data?.formFields ?? {};
      const next: Record<string, string> = {};
      for (const f of SALES_ORDER_HEADER_FIELDS) {
        next[f] = ff[f] ?? '';
      }
      setSalesOrderHeaderDraft(next);
    },
    onError: (e: Error) => {
      setError(e.message);
      setData(null);
    },
  });

  const isPending = analyzeMutation.isPending;

  const formFieldsEntries = useMemo(() => {
    const obj = data?.formFields ?? {};
    return Object.entries(obj).sort((a, b) => a[0].localeCompare(b[0]));
  }, [data]);

  const hasHeaderDraft = useMemo(() => {
    return SALES_ORDER_HEADER_FIELDS.some((f) => (salesOrderHeaderDraft[f] ?? '').trim().length > 0);
  }, [salesOrderHeaderDraft]);

  return (
    <div className='space-y-6'>
      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 1 – SALES ORDER HEADER (DRAFT)</div>
          </div>
          <div className='flex gap-2'>
            <Button type='button' variant='primary' disabled={!data}>
              Save Draft
            </Button>
            <Button type='button' variant='primary' disabled={!data}>
              Attach Style
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : !hasHeaderDraft ? (
            <div className='text-sm text-gray-500 italic'>No header fields detected.</div>
          ) : (
            <div className='overflow-auto'>
              <table className='min-w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Field</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Value</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Editable</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {SALES_ORDER_HEADER_FIELDS.map((field) => (
                    <tr key={field} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 text-sm text-gray-900 align-top whitespace-nowrap'>{field}</td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top w-full'>
                        <Input
                          value={salesOrderHeaderDraft[field] ?? ''}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderHeaderDraft((prev) => ({ ...prev, [field]: v }));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top whitespace-nowrap'>TRUE</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 p-6'>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <h3 className='text-lg font-bold text-gray-900'>OCR New</h3>
            <p className='text-sm text-gray-500 mt-1'>Offline OCR (no Textract). PDF will be rendered to PNG (300 DPI) then analyzed.</p>
          </div>
        </div>

        <div className='mt-6 grid grid-cols-1 md:grid-cols-2 gap-4 items-end'>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-2'>Upload PDF/Image</label>
            <Input
              type='file'
              accept='.pdf,image/png,image/jpeg,image/jpg'
              onChange={(e) => {
                const f = e.target.files?.[0] ?? null;
                setSelectedFile(f);
                setData(null);
                setError(null);
                setSalesOrderHeaderDraft({});
              }}
            />
          </div>
          <div className='flex gap-3 justify-start md:justify-end'>
            <Button
              type='button'
              disabled={!selectedFile || isPending}
              onClick={() => {
                if (!selectedFile) return;
                analyzeMutation.mutate(selectedFile);
              }}
            >
              {isPending ? (
                <span className='inline-flex items-center gap-2'>
                  <Loader2 className='w-4 h-4 animate-spin' />
                  Analyzing...
                </span>
              ) : (
                <span className='inline-flex items-center gap-2'>
                  <Upload className='w-4 h-4' />
                  Analyze
                </span>
              )}
            </Button>
          </div>
        </div>

        {error && (
          <div className='mt-4 flex items-start gap-2 rounded-xl border border-red-200 bg-red-50 p-4 text-red-700'>
            <AlertCircle className='w-5 h-5 mt-0.5' />
            <div className='text-sm'>{error}</div>
          </div>
        )}
      </div>

      <div className='grid grid-cols-1 lg:grid-cols-2 gap-6'>
        <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
          <div className='bg-gray-50 px-6 py-4 border-b border-gray-200'>
            <h4 className='font-semibold text-gray-900'>Form Fields</h4>
            <p className='text-xs text-gray-500 mt-1'>Key-value pairs extracted from OCR lines.</p>
          </div>
          <div className='p-6'>
            {!data ? (
              <div className='text-sm text-gray-500 italic'>No data.</div>
            ) : formFieldsEntries.length === 0 ? (
              <div className='text-sm text-gray-500 italic'>No fields detected.</div>
            ) : (
              <div className='overflow-auto max-h-[60vh]'>
                <table className='min-w-full border border-gray-200 rounded-lg overflow-hidden'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Field</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Value</th>
                    </tr>
                  </thead>
                  <tbody className='bg-white'>
                    {formFieldsEntries.map(([k, v]) => (
                      <tr key={k} className='border-b border-gray-100 last:border-b-0'>
                        <td className='px-3 py-2 text-sm text-gray-900 align-top whitespace-nowrap'>{k}</td>
                        <td className='px-3 py-2 text-sm text-gray-700 align-top'>{v}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>

        <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
          <div className='bg-gray-50 px-6 py-4 border-b border-gray-200'>
            <h4 className='font-semibold text-gray-900'>Tables</h4>
            <p className='text-xs text-gray-500 mt-1'>Heuristic table detection from word alignment.</p>
          </div>
          <div className='p-6'>
            {!data ? (
              <div className='text-sm text-gray-500 italic'>No data.</div>
            ) : (data.tables?.length ?? 0) === 0 ? (
              <div className='text-sm text-gray-500 italic'>No tables detected.</div>
            ) : (
              <div className='space-y-4 overflow-auto max-h-[60vh] pr-2'>
                {data.tables.map((t) => (
                  <div key={`${t.page}-${t.index}`} className='border border-gray-200 rounded-xl overflow-hidden'>
                    <div className='px-4 py-2 bg-gray-50 border-b border-gray-200 text-xs text-gray-600 flex items-center justify-between'>
                      <span>Page {t.page} - Table {t.index}</span>
                      <span>
                        {t.rowCount}x{t.columnCount}
                      </span>
                    </div>
                    <div className='overflow-auto'>
                      <table className='min-w-full'>
                        <tbody>
                          {t.rows.slice(0, 25).map((row, i) => (
                            <tr key={i} className='border-b border-gray-100 last:border-b-0'>
                              {row.map((cell, ci) => (
                                <td key={ci} className='px-3 py-2 text-sm text-gray-700 whitespace-nowrap'>
                                  {cell}
                                </td>
                              ))}
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='bg-gray-50 px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <h4 className='font-semibold text-gray-900'>Raw OCR Output</h4>
            <p className='text-xs text-gray-500 mt-1'>Extracted text + lines (first 200).</p>
          </div>
          {data && (
            <div className='text-xs text-gray-600'>Avg confidence: {Math.round((data.averageConfidence ?? 0) * 10) / 10}</div>
          )}
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : (
            <div className='space-y-4'>
              <pre className='text-xs font-mono bg-gray-50 border border-gray-200 rounded-xl p-4 overflow-auto max-h-[40vh] whitespace-pre-wrap'>
                {data.extractedText}
              </pre>
              <pre className='text-xs font-mono bg-gray-50 border border-gray-200 rounded-xl p-4 overflow-auto max-h-[40vh] whitespace-pre-wrap'>
                {JSON.stringify(
                  {
                    pageCount: data.pageCount,
                    keyValuePairs: data.keyValuePairs?.slice(0, 200) ?? [],
                    lines: data.lines?.slice(0, 200) ?? [],
                  },
                  null,
                  2,
                )}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

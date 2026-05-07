import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import { ocrNewApi } from '../ocrnew/api';
import type { OcrNewDocumentAnalysisResponseData } from '../ocrnew/types';
import { salesOrderPrototypeApi } from '../salesOrderPrototype/api';

const SALES_ORDER_HEADER_FIELDS = [
  'SO Number',
  'Date (ISO)',
  'Season',
  'Supplier Code',
  'Supplier',
  'Article / Product No',
  'Product Name',
  'Product Type',
  'Customs Customer Group',
  'Type of Construction',
  'Development No',
  'Terms of Delivery',
  'Time of Delivery',
] as const;

export function TotalCountryBreakdownScanPage() {
  const navigate = useNavigate();
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [activeFileIndex, setActiveFileIndex] = useState<number>(0);
  const [results, setResults] = useState<Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>>([]);
  const [data, setData] = useState<OcrNewDocumentAnalysisResponseData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successOpen, setSuccessOpen] = useState(false);
  const [successMessage, setSuccessMessage] = useState('Draft updated.');
  const [lastSavedId, setLastSavedId] = useState<number | null>(null);

  const [salesOrderHeaderDraft, setSalesOrderHeaderDraft] = useState<Record<string, string>>({});
  const [countryBreakdownDraftRows, setCountryBreakdownDraftRows] = useState<
    Array<{ country: string; countryOfDestination?: string; pmCode: string; total: string; editable: boolean }>
  >([]);

  const [section2cDraftRows, setSection2cDraftRows] = useState<Array<{ article: string; size: string; qty: string; editable: boolean }>>([]);

  const normalizeDigits = (v: string) => {
    const d = (v ?? '').toString().replace(/[^0-9]/g, '');
    return d.length ? d : '';
  };

  const hydrateDraftsFromData = (d: OcrNewDocumentAnalysisResponseData | null) => {
    const ff = d?.formFields ?? {};
    const next: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      next[f] = ff[f] ?? '';
    }
    setSalesOrderHeaderDraft(next);

    const rows = d?.totalCountryBreakdown ?? [];
    if (!Array.isArray(rows) || rows.length === 0) {
      setCountryBreakdownDraftRows([]);
    } else {
      const keys = Object.keys(rows[0] ?? {});
      const toVal = (m: Record<string, any>, k: string) => (m?.[k] ?? '').toString();
      const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
      const kCountry = findKey(['country']) ?? 'country';
      const kCountryOfDestination = findKey(['countryofdestination', 'destinationcountry']) ?? '';
      const kPm = findKey(['pmcode', 'pm', 'pm_code']) ?? 'pmCode';
      const kTotal = findKey(['total', 'tot']) ?? 'total';
      const out = (rows as any[]).map((m) => ({
        country: toVal(m, kCountry),
        countryOfDestination: kCountryOfDestination ? toVal(m, kCountryOfDestination) : '',
        pmCode: toVal(m, kPm),
        total: toVal(m, kTotal),
        editable: true,
      }));
      setCountryBreakdownDraftRows(out);
    }

    const s2c = d?.colourSizeBreakdown ?? [];
    if (!Array.isArray(s2c) || s2c.length === 0) {
      setSection2cDraftRows([]);
      return;
    }

    const exploded: Array<{ article: string; size: string; qty: string; editable: boolean }> = [];
    for (const m of s2c as any[]) {
      if (!m || typeof m !== 'object') continue;
      const article = ((m.article ?? m.Article ?? '') as any).toString();
      const mKeys = Object.keys(m ?? {});
      for (const k of mKeys) {
        const low = k.toLowerCase();
        if (low === 'article' || low === 'total' || low === 'page') continue;
        const qty = (m?.[k] ?? '').toString();
        if (!qty.trim()) continue;
        exploded.push({ article, size: k, qty, editable: true });
      }
    }
    setSection2cDraftRows(exploded);
  };

  const analyzeMutation = useMutation({
    mutationFn: async (files: File[]) => {
      const out: Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }> = [];
      for (const f of files) {
        const submit = await ocrNewApi.submitJob(f);
        const jobId = submit?.data;
        if (!jobId) throw new Error('No jobId returned');

        const pollUntilDone = async () => {
          const started = performance.now();
          for (;;) {
            const st = await ocrNewApi.getJob(jobId);
            const status = st?.data?.status;
            if (status === 'SUCCEEDED' || status === 'FAILED') return st;
            if (performance.now() - started > 15 * 60 * 1000) throw new Error('OCR job timeout');
            await new Promise((r) => setTimeout(r, 1200));
          }
        };

        const st = await pollUntilDone();
        if (st?.data?.status !== 'SUCCEEDED') {
          const msg = st?.data?.errorMessage ?? 'OCR job failed';
          throw new Error(msg);
        }

        const resData = st?.data?.result ?? null;
        if (resData) out.push({ fileName: f.name, data: resData });
      }
      return out;
    },
    onSuccess: (out) => {
      setResults(out);
      setError(null);
      setActiveFileIndex(0);
      const first = out?.[0]?.data ?? null;
      setData(first);
      window.scrollTo({ top: 0, behavior: 'smooth' });
      hydrateDraftsFromData(first);
    },
    onError: (e: Error) => {
      setError(e.message);
      setData(null);
      setResults([]);
      setSalesOrderHeaderDraft({});
      setCountryBreakdownDraftRows([]);
      setSection2cDraftRows([]);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },
  });

  const isPending = analyzeMutation.isPending;

  const saveDraftMutation = useMutation({
    mutationFn: async () => {
      if (!data) throw new Error('No data.');
      const payload = {
        documentType: 'total-country-breakdown',
        source: 'presales-total-country-breakdown',
        analyzedFileName: results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '',
        formFields: salesOrderHeaderDraft,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
        raw: data,
      };
      return salesOrderPrototypeApi.createOrMerge(payload);
    },
    onSuccess: (res) => {
      const soId = (res as any)?.data?.id;
      if (soId !== undefined && soId !== null) {
        setSuccessMessage(`Successfully created draft id "${soId}"`);
        setLastSavedId(Number(soId));
      } else {
        setSuccessMessage('Successfully created draft.');
        setLastSavedId(null);
      }
      setSuccessOpen(true);
    },
    onError: (e: Error) => {
      alert(`Failed to save draft: ${e.message}`);
    },
  });

  const hasHeaderDraft = useMemo(() => {
    return SALES_ORDER_HEADER_FIELDS.some((f) => (salesOrderHeaderDraft[f] ?? '').trim().length > 0);
  }, [salesOrderHeaderDraft]);

  const sourceFileName = useMemo(() => {
    return results?.[activeFileIndex]?.fileName ?? selectedFiles?.[activeFileIndex]?.name ?? '';
  }, [activeFileIndex, results, selectedFiles]);

  return (
    <div className='space-y-6'>
      <Modal isOpen={successOpen} onClose={() => setSuccessOpen(false)} title='Success!'>
        <div className='flex flex-col items-center text-center gap-4'>
          <div className='rounded-full bg-green-50 p-3'>
            <CheckCircle2 className='w-10 h-10 text-green-600' />
          </div>
          <p className='text-gray-700'>{successMessage}</p>
          <Button type='button' onClick={() => setSuccessOpen(false)}>
            OK
          </Button>
        </div>
      </Modal>

      <div className='bg-white rounded-2xl border border-gray-200 p-6'>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <h3 className='text-lg font-bold text-gray-900'>Total Country Breakdown</h3>
          </div>
        </div>

        <div className='mt-6 grid grid-cols-1 md:grid-cols-2 gap-4 items-end'>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-2'>Upload PDF/Image</label>
            <Input
              type='file'
              accept='.pdf,image/png,image/jpeg,image/jpg'
              multiple
              onChange={(e) => {
                const files = Array.from(e.target.files ?? []);
                setSelectedFiles(files);
                setActiveFileIndex(0);
                setData(null);
                setResults([]);
                setError(null);
                setSalesOrderHeaderDraft({});
                setCountryBreakdownDraftRows([]);
                setSection2cDraftRows([]);
              }}
            />
          </div>
          <div className='flex gap-3 justify-start md:justify-end'>
            <Button type='button' disabled={selectedFiles.length === 0 || isPending} onClick={() => analyzeMutation.mutate(selectedFiles)}>
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-sm font-semibold text-gray-900'>Sales Order Header (Draft)</div>
          <Button type='button' variant='primary' disabled={!data || saveDraftMutation.isPending} onClick={() => saveDraftMutation.mutate()}>
            Save Draft
          </Button>
        </div>
        <div className='p-6 space-y-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : !hasHeaderDraft ? (
            <div className='text-sm text-gray-500 italic'>No header fields detected.</div>
          ) : (
            <div className='grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-3'>
              {SALES_ORDER_HEADER_FIELDS.map((field) => (
                <div key={field} className='grid grid-cols-12 items-center gap-3'>
                  <div className='col-span-4 text-sm text-gray-700'>{field}</div>
                  <div className='col-span-8'>
                    <Input value={salesOrderHeaderDraft[field] ?? ''} onChange={(e) => setSalesOrderHeaderDraft((prev) => ({ ...prev, [field]: e.target.value }))} />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>TOTAL COUNTRY BREAKDOWN</div>
            {!!sourceFileName && <div className='text-xs text-gray-400 mt-0.5'>Source: {sourceFileName}</div>}
          </div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='danger'
              disabled={!data || countryBreakdownDraftRows.length === 0}
              onClick={() => {
                setCountryBreakdownDraftRows([]);
              }}
            >
              Delete table
            </Button>
            <Button
              type='button'
              variant='primary'
              disabled={!data}
              onClick={() => {
                setCountryBreakdownDraftRows((prev) => [...prev, { country: '', pmCode: '', total: '', editable: true }]);
              }}
            >
              Add row
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : countryBreakdownDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No country breakdown detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[900px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Country</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>PM Code</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Total</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {countryBreakdownDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input value={row.country} onChange={(e) => setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, country: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input value={row.pmCode} onChange={(e) => setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, pmCode: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) =>
                            setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(e.target.value) } : r)))
                          }
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setCountryBreakdownDraftRows((prev) => prev.filter((_, i) => i !== idx));
                          }}
                        >
                          Delete
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>COLOUR / SIZE BREAKDOWN</div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='danger'
              disabled={!data || section2cDraftRows.length === 0}
              onClick={() => {
                setSection2cDraftRows([]);
              }}
            >
              Delete table
            </Button>
            <Button
              type='button'
              variant='primary'
              disabled={!data}
              onClick={() => {
                setSection2cDraftRows((prev) => [...(prev ?? []), { article: '', size: '', qty: '', editable: true }]);
              }}
            >
              Add row
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : section2cDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No size breakdown detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[800px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Article</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {section2cDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100'>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.article} onChange={(e) => setSection2cDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, article: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <SizeAutocompleteInput
                          value={row.size}
                          onChange={(e) => {
                            const v = normalizeSizeKey(e.target.value);
                            setSection2cDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.qty)}
                          onChange={(e) => setSection2cDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(e.target.value) } : r)))}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setSection2cDraftRows((prev) => prev.filter((_, i) => i !== idx));
                          }}
                        >
                          Delete
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {lastSavedId !== null && (
        <div className='bg-white rounded-2xl border border-gray-200 p-6'>
          <div className='text-sm text-gray-700'>Draft ID: {lastSavedId}</div>
          <div className='mt-3 flex gap-2'>
            <Button type='button' onClick={() => navigate(`/sales-order-prototype/${lastSavedId}`)}>
              Open Draft
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}

const idFormatter = new Intl.NumberFormat('id-ID');

function formatIdThousands(input: string): string {
  const digits = (input ?? '').toString().replace(/\D+/g, '');
  if (!digits) return '';
  try {
    return idFormatter.format(Number(digits));
  } catch {
    return digits;
  }
}

function normalizeSizeKey(input: string): string {
  const s = (input ?? '').toString().trim();
  if (!s) return '';
  return s
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/\*/g, '');
}

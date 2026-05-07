import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, ChevronLeft, ChevronRight, Loader2, Upload } from 'lucide-react';
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

export function SizePerColourBreakdownScanPage() {
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
  const [bomDraftRows, setBomDraftRows] = useState<
    Array<{ position: string; placement: string; type: string; description: string; composition: string; materialSupplier: string }>
  >([]);

  const [salesOrderDetailDraftRows, setSalesOrderDetailDraftRows] = useState<
    Array<{
      countryOfDestination: string;
      type: string;
      color: string;
      size: string;
      qty: string;
      total: string;
      noOfAsst?: string;
      editable: boolean;
    }>
  >([]);

  const appendLog = (_msg: string) => {};

  const isBomDraftTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('position')) && h.some((x) => x.includes('placement'));
  };

  const pivotDetailRows = (backendDetail: Array<Record<string, any>>) => {
    const out: Array<{
      countryOfDestination: string;
      type: string;
      color: string;
      size: string;
      qty: string;
      total: string;
      noOfAsst?: string;
      editable: boolean;
    }> = [];

    const SIZE_KEYS = ['XS', 'S', 'M', 'L', 'XL'];
    for (const row of backendDetail ?? []) {
      const type = (row?.type ?? '').toString();
      const countryOfDestination = (row?.countryOfDestination ?? row?.destinationCountry ?? '').toString();
      const color = (row?.color ?? '').toString();
      const total = (row?.total ?? '').toString();
      const noOfAsst = (row?.noOfAsst ?? '').toString();

      let emittedAny = false;
      for (const k of SIZE_KEYS) {
        if (row?.[k] === undefined) continue;
        const qty = (row?.[k] ?? '').toString();
        out.push({ countryOfDestination, type, color, size: k, qty, total, noOfAsst, editable: true });
        emittedAny = true;
      }
      if (!emittedAny) {
        out.push({ countryOfDestination, type, color, size: '', qty: '', total, noOfAsst, editable: true });
      }
    }
    return out;
  };

  const hydrateDraftsFromData = (d: OcrNewDocumentAnalysisResponseData | null) => {
    const ff = d?.formFields ?? {};
    const next: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      next[f] = ff[f] ?? '';
    }
    setSalesOrderHeaderDraft(next);

    const bomTable = (d?.tables ?? []).find((t) => isBomDraftTable((t as any).rows));
    if ((bomTable as any)?.rows?.length) {
      const rows = (bomTable as any).rows.slice(1).map((r: any[]) => ({
        position: r?.[0] ?? '',
        placement: r?.[1] ?? '',
        type: r?.[2] ?? '',
        description: r?.[3] ?? '',
        composition: r?.[4] ?? '',
        materialSupplier: r?.[5] ?? '',
      }));
      setBomDraftRows(rows);
    } else {
      setBomDraftRows([]);
    }

    const backendDetail = d?.salesOrderDetailSizeBreakdown ?? [];
    if (backendDetail.length > 0) {
      setSalesOrderDetailDraftRows(pivotDetailRows(backendDetail as any));
    } else {
      setSalesOrderDetailDraftRows([]);
    }
  };

  const analyzeMutation = useMutation({
    mutationFn: async (files: File[]) => {
      const out: Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }> = [];
      for (const f of files) {
        appendLog(`START file=${f.name} sizeBytes=${f.size}`);
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
      setBomDraftRows([]);
      setSalesOrderDetailDraftRows([]);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },
  });

  const isPending = analyzeMutation.isPending;

  const saveDraftMutation = useMutation({
    mutationFn: async () => {
      if (!data) throw new Error('No data.');
      const payload = {
        documentType: 'size-per-colour-breakdown',
        source: 'presales-size-per-colour-breakdown',
        analyzedFileName: results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '',
        formFields: salesOrderHeaderDraft,
        bomDraftRows,
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
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

  const section2AssortmentEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => {
        const t = (row?.type ?? '').toString().trim().toLowerCase();
        if (t === 'total') return false;
        return t === 'assortment';
      });
  }, [salesOrderDetailDraftRows]);

  const section2SolidEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => {
        const t = (row?.type ?? '').toString().trim().toLowerCase();
        if (t === 'total') return false;
        return t === 'solid';
      });
  }, [salesOrderDetailDraftRows]);

  // --- Country pagination ---
  const [assortmentCountryPage, setAssortmentCountryPage] = useState(0);
  const [solidCountryPage, setSolidCountryPage] = useState(0);

  const assortmentCountries = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const { row } of section2AssortmentEntries) {
      const c = (row.countryOfDestination ?? '').trim();
      if (c && !seen.has(c)) { seen.add(c); list.push(c); }
    }
    return list;
  }, [section2AssortmentEntries]);

  const solidCountries = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const { row } of section2SolidEntries) {
      const c = (row.countryOfDestination ?? '').trim();
      if (c && !seen.has(c)) { seen.add(c); list.push(c); }
    }
    return list;
  }, [section2SolidEntries]);

  const assortmentActiveCountry = assortmentCountries[assortmentCountryPage] ?? '';
  const solidActiveCountry = solidCountries[solidCountryPage] ?? '';

  const assortmentFilteredEntries = useMemo(() => {
    if (!assortmentActiveCountry) return section2AssortmentEntries;
    return section2AssortmentEntries.filter(({ row }) => (row.countryOfDestination ?? '').trim() === assortmentActiveCountry);
  }, [section2AssortmentEntries, assortmentActiveCountry]);

  const solidFilteredEntries = useMemo(() => {
    if (!solidActiveCountry) return section2SolidEntries;
    return section2SolidEntries.filter(({ row }) => (row.countryOfDestination ?? '').trim() === solidActiveCountry);
  }, [section2SolidEntries, solidActiveCountry]);

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
            <h3 className='text-lg font-bold text-gray-900'>Size Per Colour Breakdown</h3>
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
                setBomDraftRows([]);
                setSalesOrderDetailDraftRows([]);
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
          <div className='text-xs font-semibold text-gray-500'>SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : section2AssortmentEntries.length === 0 && section2SolidEntries.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No detail table detected.</div>
          ) : (
            <div className='grid grid-cols-1 xl:grid-cols-2 gap-6'>
              <div className='border border-gray-200 rounded-xl overflow-hidden'>
                <div className='px-4 py-3 border-b border-gray-200 bg-gray-50 flex items-center justify-between gap-4'>
                  <div className='text-sm font-semibold text-gray-900'>Assortment</div>
                  <div className='flex items-center gap-2'>
                    <Button
                      type='button'
                      variant='danger'
                      disabled={!data || assortmentCountries.length === 0}
                      onClick={() => {
                        setSalesOrderDetailDraftRows((prev) =>
                          (prev ?? []).filter((r) => (r?.type ?? '').toString().trim().toLowerCase() !== 'assortment'),
                        );
                      }}
                    >
                      Delete table
                    </Button>
                    <Button
                      type='button'
                      variant='primary'
                      disabled={!data}
                      onClick={() =>
                        setSalesOrderDetailDraftRows((prev) => [
                          ...prev,
                          { countryOfDestination: assortmentActiveCountry || '', type: 'Assortment', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                        ])
                      }
                    >
                      Add row
                    </Button>
                  </div>
                </div>

                {section2AssortmentEntries.length === 0 ? (
                  <div className='p-4 text-sm text-gray-500 italic'>No Assortment rows.</div>
                ) : (
                  <>
                    {assortmentCountries.length > 1 && (
                      <div className='px-4 py-3 border-b border-gray-100 bg-white flex items-center gap-2'>
                        <button
                          type='button'
                          disabled={assortmentCountryPage === 0}
                          onClick={() => setAssortmentCountryPage((p) => Math.max(0, p - 1))}
                          className='inline-flex items-center justify-center w-8 h-8 rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors'
                        >
                          <ChevronLeft className='w-4 h-4' />
                        </button>
                        <div className='flex items-center gap-1 overflow-x-auto'>
                          {assortmentCountries.map((country, i) => (
                            <button
                              key={country}
                              type='button'
                              onClick={() => setAssortmentCountryPage(i)}
                              className={`px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap transition-all ${
                                i === assortmentCountryPage
                                  ? 'bg-blue-600 text-white shadow-sm'
                                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                              }`}
                            >
                              {country}
                            </button>
                          ))}
                        </div>
                        <button
                          type='button'
                          disabled={assortmentCountryPage >= assortmentCountries.length - 1}
                          onClick={() => setAssortmentCountryPage((p) => Math.min(assortmentCountries.length - 1, p + 1))}
                          className='inline-flex items-center justify-center w-8 h-8 rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors'
                        >
                          <ChevronRight className='w-4 h-4' />
                        </button>
                        <span className='ml-auto text-xs text-gray-400'>{assortmentCountryPage + 1} / {assortmentCountries.length}</span>
                      </div>
                    )}
                    <div className='w-full max-h-[60vh] overflow-auto'>
                      <table className='min-w-[980px] w-full border-separate border-spacing-0'>
                        <thead className='bg-white sticky top-0 z-10'>
                          <tr>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Color</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>No of Asst</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Actions</th>
                          </tr>
                        </thead>
                        <tbody className='bg-white'>
                          {assortmentFilteredEntries.map(({ row, idx }) => (
                            <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={row.countryOfDestination}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.color} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <SizeAutocompleteInput value={row.size} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.qty} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.noOfAsst ?? ''} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Button
                                  type='button'
                                  variant='danger'
                                  onClick={() => {
                                    setSalesOrderDetailDraftRows((prev) => prev.filter((_, i) => i !== idx));
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
                  </>
                )}
              </div>

              <div className='border border-gray-200 rounded-xl overflow-hidden'>
                <div className='px-4 py-3 border-b border-gray-200 bg-gray-50 flex items-center justify-between gap-4'>
                  <div className='text-sm font-semibold text-gray-900'>Solid</div>
                  <div className='flex items-center gap-2'>
                    <Button
                      type='button'
                      variant='danger'
                      disabled={!data || solidCountries.length === 0}
                      onClick={() => {
                        setSalesOrderDetailDraftRows((prev) =>
                          (prev ?? []).filter((r) => (r?.type ?? '').toString().trim().toLowerCase() !== 'solid'),
                        );
                      }}
                    >
                      Delete table
                    </Button>
                    <Button
                      type='button'
                      variant='primary'
                      disabled={!data}
                      onClick={() =>
                        setSalesOrderDetailDraftRows((prev) => [
                          ...prev,
                          { countryOfDestination: solidActiveCountry || '', type: 'Solid', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                        ])
                      }
                    >
                      Add row
                    </Button>
                  </div>
                </div>

                {section2SolidEntries.length === 0 ? (
                  <div className='p-4 text-sm text-gray-500 italic'>No Solid rows.</div>
                ) : (
                  <>
                    {solidCountries.length > 1 && (
                      <div className='px-4 py-3 border-b border-gray-100 bg-white flex items-center gap-2'>
                        <button
                          type='button'
                          disabled={solidCountryPage === 0}
                          onClick={() => setSolidCountryPage((p) => Math.max(0, p - 1))}
                          className='inline-flex items-center justify-center w-8 h-8 rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors'
                        >
                          <ChevronLeft className='w-4 h-4' />
                        </button>
                        <div className='flex items-center gap-1 overflow-x-auto'>
                          {solidCountries.map((country, i) => (
                            <button
                              key={country}
                              type='button'
                              onClick={() => setSolidCountryPage(i)}
                              className={`px-3 py-1.5 rounded-lg text-xs font-medium whitespace-nowrap transition-all ${
                                i === solidCountryPage
                                  ? 'bg-blue-600 text-white shadow-sm'
                                  : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                              }`}
                            >
                              {country}
                            </button>
                          ))}
                        </div>
                        <button
                          type='button'
                          disabled={solidCountryPage >= solidCountries.length - 1}
                          onClick={() => setSolidCountryPage((p) => Math.min(solidCountries.length - 1, p + 1))}
                          className='inline-flex items-center justify-center w-8 h-8 rounded-lg border border-gray-200 text-gray-500 hover:bg-gray-50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors'
                        >
                          <ChevronRight className='w-4 h-4' />
                        </button>
                        <span className='ml-auto text-xs text-gray-400'>{solidCountryPage + 1} / {solidCountries.length}</span>
                      </div>
                    )}
                    <div className='w-full max-h-[60vh] overflow-auto'>
                      <table className='min-w-[980px] w-full border-separate border-spacing-0'>
                        <thead className='bg-white sticky top-0 z-10'>
                          <tr>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Color</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>No of Asst</th>
                            <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Actions</th>
                          </tr>
                        </thead>
                        <tbody className='bg-white'>
                          {solidFilteredEntries.map(({ row, idx }) => (
                            <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={row.countryOfDestination}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.color} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <SizeAutocompleteInput value={row.size} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.qty} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input value={row.noOfAsst ?? ''} onChange={(e) => setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: e.target.value } : r)))} />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Button
                                  type='button'
                                  variant='danger'
                                  onClick={() => {
                                    setSalesOrderDetailDraftRows((prev) => prev.filter((_, i) => i !== idx));
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
                  </>
                )}
              </div>
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

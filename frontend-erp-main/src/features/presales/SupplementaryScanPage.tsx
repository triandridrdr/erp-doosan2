import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as XLSX from 'xlsx';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import { salesOrderPrototypeApi } from '../salesOrderPrototype/api';
import { ocrNewApi } from '../ocrnew/api';
import type { OcrNewDocumentAnalysisResponseData } from '../ocrnew/types';

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

export function SupplementaryScanPage() {
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
    Array<{
      position: string;
      placement: string;
      type: string;
      description: string;
      composition: string;
      materialSupplier: string;
    }>
  >([]);

  const [countryBreakdownDraftRows] = useState<
    Array<{
      country: string;
      countryOfDestination?: string;
      pmCode: string;
      total: string;
      editable: boolean;
    }>
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

  const [section2cDraftRows] = useState<
    Array<{
      article: string;
      size: string;
      qty: string;
      editable: boolean;
    }>
  >([]);

  const [section2cTotalDraftRows] = useState<
    Array<{
      article: string;
      total: string;
      editable: boolean;
    }>
  >([]);

  const appendLog = (_msg: string) => {};

  const logBackendSection2DetailRows = (fileName: string, d: OcrNewDocumentAnalysisResponseData | null | undefined) => {
    const rows = (d?.salesOrderDetailSizeBreakdown ?? []) as any[];
    if (!Array.isArray(rows) || rows.length === 0) {
      appendLog(`[BACKEND->FE] file=${fileName} salesOrderDetailSizeBreakdown=[]`);
      return;
    }

    const mexico = rows.filter((r) => {
      const c = (r?.countryOfDestination ?? r?.destinationCountry ?? '').toString().toLowerCase();
      return c.includes('mexico');
    });

    const sample = (mexico.length > 0 ? mexico : rows).slice(0, 20);
    appendLog(
      `[BACKEND->FE] file=${fileName} salesOrderDetailSizeBreakdown_count=${rows.length} sample_count=${sample.length} mexico_count=${mexico.length}`,
    );
    for (const r of sample) {
      try {
        appendLog(`[BACKEND->FE] row=${JSON.stringify(r)}`);
      } catch {
        appendLog(`[BACKEND->FE] row=[unserializable]`);
      }
    }
  };

  const normalizeDigits = (v: string) => {
    const d = (v ?? '').toString().replace(/[^0-9]/g, '');
    return d.length ? d : '';
  };

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

    const bomTable = (d?.tables ?? []).find((t) => isBomDraftTable(t.rows));
    if (bomTable?.rows?.length) {
      const rows = bomTable.rows.slice(1).map((r: any[]) => ({
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

  const hydrateDraftsFromResultsMerged = (out: Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>) => {
    const mergedHeader: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      let v = '';
      for (const r of out) {
        const cand = (r?.data?.formFields?.[f] ?? '').toString().trim();
        if (cand) {
          v = cand;
          break;
        }
      }
      mergedHeader[f] = v;
    }
    setSalesOrderHeaderDraft(mergedHeader);

    let bomRows: Array<{
      position: string;
      placement: string;
      type: string;
      description: string;
      composition: string;
      materialSupplier: string;
    }> = [];
    for (const r of out) {
      const bomTable = (r?.data?.tables ?? []).find((t) => isBomDraftTable((t as any).rows));
      if ((bomTable as any)?.rows?.length) {
        bomRows = (bomTable as any).rows.slice(1).map((row: any[]) => ({
          position: row?.[0] ?? '',
          placement: row?.[1] ?? '',
          type: row?.[2] ?? '',
          description: row?.[3] ?? '',
          composition: row?.[4] ?? '',
          materialSupplier: row?.[5] ?? '',
        }));
        break;
      }
    }
    setBomDraftRows(bomRows);

    let detailRows: Array<{
      countryOfDestination: string;
      type: string;
      color: string;
      size: string;
      qty: string;
      total: string;
      noOfAsst?: string;
      editable: boolean;
    }> = [];
    for (const r of out) {
      const backendDetail = r?.data?.salesOrderDetailSizeBreakdown ?? [];
      if (backendDetail.length > 0) {
        detailRows = pivotDetailRows(backendDetail as any);
        if (detailRows.length > 0) break;
      }
    }
    setSalesOrderDetailDraftRows(detailRows);
  };

  const analyzeMutation = useMutation({
    mutationFn: async (files: File[]) => {
      const out: Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }> = [];
      for (const f of files) {
        const t0 = performance.now();
        appendLog(`START file=${f.name} sizeBytes=${f.size}`);
        try {
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
          const dtMs = Math.round(performance.now() - t0);
          const pc = resData?.pageCount ?? 0;
          const tc = resData?.tables?.length ?? 0;
          const dc = (resData?.salesOrderDetailSizeBreakdown ?? []).length;
          appendLog(`OK file=${f.name} durationMs=${dtMs} pageCount=${pc} tableCount=${tc} detailRowCount=${dc}`);
          logBackendSection2DetailRows(f.name, resData ?? undefined);
          if (resData) {
            out.push({ fileName: f.name, data: resData });
          }
        } catch (e) {
          const dtMs = Math.round(performance.now() - t0);
          const msg = e instanceof Error ? e.message : String(e);
          appendLog(`ERROR file=${f.name} durationMs=${dtMs} message=${msg}`);
          throw e;
        }
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

      if (out.length <= 1) {
        hydrateDraftsFromData(first);
      } else {
        hydrateDraftsFromResultsMerged(out);
      }
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
        documentType: 'supplementary',
        source: 'presales-supplementary',
        analyzedFileName: results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '',
        formFields: salesOrderHeaderDraft,
        bomDraftRows,
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
        section2cColourSizeBreakdownTotal: section2cTotalDraftRows,
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

  const section2NonTotalEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => (row?.type ?? '').toString().trim().toLowerCase() !== 'total');
  }, [salesOrderDetailDraftRows]);

  const exportSection2SizeBreakdownToExcel = () => {
    const toNumOrNull = (v: unknown) => {
      const d = normalizeDigits((v ?? '').toString());
      if (!d) return null;
      const n = Number(d);
      return Number.isFinite(n) ? n : null;
    };

    const rows = section2NonTotalEntries.map(({ row }) => ({
      'Country of Destination': (row.countryOfDestination ?? '').toString(),
      Type: (row.type ?? '').toString(),
      Color: (row.color ?? '').toString(),
      Size: (row.size ?? '').toString(),
      Qty: toNumOrNull(row.qty),
      Total: toNumOrNull(row.total),
      'No of Asst': (row.noOfAsst ?? '').toString(),
    }));

    const wb = XLSX.utils.book_new();
    const ws = XLSX.utils.json_to_sheet(rows);
    XLSX.utils.book_append_sheet(wb, ws, 'SECTION 2');
    XLSX.writeFile(wb, `SECTION_2_SIZE_BREAKDOWN_${new Date().toISOString().slice(0, 10)}.xlsx`);
  };

  return (
    <div className='space-y-6'>
      <Modal isOpen={successOpen} onClose={() => setSuccessOpen(false)} title='Success!'>
        <div className='flex flex-col items-center text-center gap-4'>
          <div className='rounded-full bg-green-50 p-3'>
            <CheckCircle2 className='w-10 h-10 text-green-600' />
          </div>
          <p className='text-gray-700'>{successMessage}</p>
          <Button
            type='button'
            onClick={() => {
              setSuccessOpen(false);
            }}
          >
            OK
          </Button>
        </div>
      </Modal>
      <div className='bg-white rounded-2xl border border-gray-200 p-6'>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <h3 className='text-lg font-bold text-gray-900'>Supplementary</h3>
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
            <Button
              type='button'
              disabled={selectedFiles.length === 0 || isPending}
              onClick={() => {
                if (selectedFiles.length === 0) return;
                analyzeMutation.mutate(selectedFiles);
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

        {results.length > 1 && (
          <div className='mt-4 flex flex-wrap gap-2'>
            {results.map((r) => (
              <span key={r.fileName} className='px-3 py-1 rounded-lg text-xs font-semibold bg-gray-100 text-gray-700'>
                {r.fileName}
              </span>
            ))}
          </div>
        )}
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 1 – SALES ORDER HEADER (DRAFT)</div>
          </div>
          <div className='flex gap-2'>
            <Button
              type='button'
              variant='primary'
              disabled={!data || saveDraftMutation.isPending}
              onClick={() => saveDraftMutation.mutate()}
            >
              Save Draft
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
          <div className='text-xs font-semibold text-gray-500'>SECTION 2 – SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='primary'
              disabled={!data}
              onClick={() => {
                setSalesOrderDetailDraftRows((prev) => [
                  ...prev,
                  { countryOfDestination: '', type: '', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                ]);
              }}
            >
              Add row
            </Button>
            <Button type='button' disabled={section2NonTotalEntries.length === 0} onClick={exportSection2SizeBreakdownToExcel}>
              Export Excel
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : section2NonTotalEntries.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No detail table detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1100px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Type</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Color</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Total</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>No of Asst</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {section2NonTotalEntries.map(({ row, idx }) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.countryOfDestination}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.type}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, type: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.color}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <SizeAutocompleteInput
                          value={row.size}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.qty}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.total}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                        <Input
                          value={row.noOfAsst ?? ''}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: v } : r)));
                          }}
                        />
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

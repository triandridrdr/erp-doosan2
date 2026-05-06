import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
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
      materialAppearance: string;
      composition: string;
      construction: string;
      consumption: string;
      weight: string;
      componentTreatments: string;
      materialSupplier: string;
      supplierArticle: string;
      bookingId: string;
      demandId: string;
    }>
  >([]);

  const [bomProdUnitsRows, setBomProdUnitsRows] = useState<
    Array<{
      position: string;
      placement: string;
      type: string;
      materialSupplier: string;
      composition: string;
      weight: string;
      productionUnitProcessingCapability: string;
    }>
  >([]);

  const [bomYarnSourceTableRows, setBomYarnSourceTableRows] = useState<string[][]>([]);
  const [productArticleTableRows, setProductArticleTableRows] = useState<string[][]>([]);
  const [miscellaneousTableRows, setMiscellaneousTableRows] = useState<string[][]>([]);

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

  const isBomDraftTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('position')) && h.some((x) => x.includes('placement'));
  };

  const isBomProdUnitsTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('production')) && h.some((x) => x.includes('processing'));
  };

  const isBomYarnSourceDetailsTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('yarn')) && (h.some((x) => x.includes('fibre')) || h.some((x) => x.includes('fiber')));
  };

  const isProductArticleTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('article')) && h.some((x) => x.includes('colour'));
  };

  const isMiscellaneousTable = (rows?: unknown) => {
    if (!Array.isArray(rows) || rows.length < 2) return false;
    const header = (rows?.[0] ?? []) as any[];
    const h = header.map((x) => (x ?? '').toString().toLowerCase());
    return h.some((x) => x.includes('label')) && (h.some((x) => x.includes('code')) || h.some((x) => x.includes('group')));
  };

  const normalizeTableRows = (rows: any): string[][] => {
    if (!Array.isArray(rows)) return [];
    return (rows as any[]).map((r) => {
      if (!Array.isArray(r)) return [];
      return (r as any[]).map((c) => (c ?? '').toString());
    });
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
        materialAppearance: r?.[6] ?? '',
        composition: r?.[4] ?? '',
        construction: r?.[7] ?? '',
        consumption: r?.[8] ?? '',
        weight: r?.[9] ?? '',
        componentTreatments: r?.[10] ?? '',
        materialSupplier: r?.[5] ?? '',
        supplierArticle: r?.[11] ?? '',
        bookingId: r?.[12] ?? '',
        demandId: r?.[13] ?? '',
      }));
      setBomDraftRows(rows);
    } else {
      setBomDraftRows([]);
    }

    const prodUnitsTable = (d?.tables ?? []).find((t) => isBomProdUnitsTable((t as any).rows));
    if ((prodUnitsTable as any)?.rows?.length) {
      const rows = (prodUnitsTable as any).rows.slice(1).map((r: any[]) => ({
        position: r?.[0] ?? '',
        placement: r?.[1] ?? '',
        type: r?.[2] ?? '',
        materialSupplier: r?.[3] ?? '',
        composition: r?.[4] ?? '',
        weight: r?.[5] ?? '',
        productionUnitProcessingCapability: r?.[6] ?? '',
      }));
      setBomProdUnitsRows(rows);
    } else {
      setBomProdUnitsRows([]);
    }

    const yarnSourceTable = (d?.tables ?? []).find((t) => isBomYarnSourceDetailsTable((t as any).rows));
    if ((yarnSourceTable as any)?.rows?.length) {
      setBomYarnSourceTableRows(normalizeTableRows((yarnSourceTable as any).rows));
    } else {
      setBomYarnSourceTableRows([]);
    }

    const productArticleTable = (d?.tables ?? []).find((t) => isProductArticleTable((t as any).rows));
    if ((productArticleTable as any)?.rows?.length) {
      setProductArticleTableRows(normalizeTableRows((productArticleTable as any).rows));
    } else {
      setProductArticleTableRows([]);
    }

    const miscTable = (d?.tables ?? []).find((t) => isMiscellaneousTable((t as any).rows));
    if ((miscTable as any)?.rows?.length) {
      setMiscellaneousTableRows(normalizeTableRows((miscTable as any).rows));
    } else {
      setMiscellaneousTableRows([]);
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
      materialAppearance: string;
      composition: string;
      construction: string;
      consumption: string;
      weight: string;
      componentTreatments: string;
      materialSupplier: string;
      supplierArticle: string;
      bookingId: string;
      demandId: string;
    }> = [];
    for (const r of out) {
      const bomTable = (r?.data?.tables ?? []).find((t) => isBomDraftTable((t as any).rows));
      if ((bomTable as any)?.rows?.length) {
        bomRows = (bomTable as any).rows.slice(1).map((row: any[]) => ({
          position: row?.[0] ?? '',
          placement: row?.[1] ?? '',
          type: row?.[2] ?? '',
          description: row?.[3] ?? '',
          materialAppearance: row?.[6] ?? '',
          composition: row?.[4] ?? '',
          construction: row?.[7] ?? '',
          consumption: row?.[8] ?? '',
          weight: row?.[9] ?? '',
          componentTreatments: row?.[10] ?? '',
          materialSupplier: row?.[5] ?? '',
          supplierArticle: row?.[11] ?? '',
          bookingId: row?.[12] ?? '',
          demandId: row?.[13] ?? '',
        }));
        break;
      }
    }
    setBomDraftRows(bomRows);

    let prodUnitRows: Array<{
      position: string;
      placement: string;
      type: string;
      materialSupplier: string;
      composition: string;
      weight: string;
      productionUnitProcessingCapability: string;
    }> = [];
    for (const r of out) {
      const prodTable = (r?.data?.tables ?? []).find((t) => isBomProdUnitsTable((t as any).rows));
      if ((prodTable as any)?.rows?.length) {
        prodUnitRows = (prodTable as any).rows.slice(1).map((row: any[]) => ({
          position: row?.[0] ?? '',
          placement: row?.[1] ?? '',
          type: row?.[2] ?? '',
          materialSupplier: row?.[3] ?? '',
          composition: row?.[4] ?? '',
          weight: row?.[5] ?? '',
          productionUnitProcessingCapability: row?.[6] ?? '',
        }));
        break;
      }
    }
    setBomProdUnitsRows(prodUnitRows);

    let yarnSourceRows: string[][] = [];
    for (const r of out) {
      const table = (r?.data?.tables ?? []).find((t) => isBomYarnSourceDetailsTable((t as any).rows));
      if ((table as any)?.rows?.length) {
        yarnSourceRows = normalizeTableRows((table as any).rows);
        break;
      }
    }
    setBomYarnSourceTableRows(yarnSourceRows);

    let articleRows: string[][] = [];
    for (const r of out) {
      const table = (r?.data?.tables ?? []).find((t) => isProductArticleTable((t as any).rows));
      if ((table as any)?.rows?.length) {
        articleRows = normalizeTableRows((table as any).rows);
        break;
      }
    }
    setProductArticleTableRows(articleRows);

    let miscRows: string[][] = [];
    for (const r of out) {
      const table = (r?.data?.tables ?? []).find((t) => isMiscellaneousTable((t as any).rows));
      if ((table as any)?.rows?.length) {
        miscRows = normalizeTableRows((table as any).rows);
        break;
      }
    }
    setMiscellaneousTableRows(miscRows);

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
      setBomProdUnitsRows([]);
      setBomYarnSourceTableRows([]);
      setProductArticleTableRows([]);
      setMiscellaneousTableRows([]);
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
                    <Input
                      value={salesOrderHeaderDraft[field] ?? ''}
                      onChange={(e) => {
                        const v = e.target.value;
                        setSalesOrderHeaderDraft((prev) => ({ ...prev, [field]: v }));
                      }}
                    />
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>BILL OF MATERIALS (BOM DRAFT)</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data}
            onClick={() => {
              setBomDraftRows((prev) => [...prev, { position: '', placement: '', type: '', description: '', materialAppearance: '', composition: '', construction: '', consumption: '', weight: '', componentTreatments: '', materialSupplier: '', supplierArticle: '', bookingId: '', demandId: '' }]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : bomDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No BoM detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[2800px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Position</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Placement</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Type</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Description</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Material Appearance</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Composition</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Construction</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Consumption</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Weight</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Component Treatments</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Material Supplier</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Supplier Article</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Booking Id</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Demand ID</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.position}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, position: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.placement}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, placement: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.type}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, type: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.description}
                          rows={2}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, description: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.materialAppearance}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, materialAppearance: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.composition}
                          rows={2}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, composition: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.construction}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, construction: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.consumption}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, consumption: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.weight}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, weight: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.componentTreatments}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, componentTreatments: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.materialSupplier}
                          rows={2}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, materialSupplier: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.supplierArticle}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, supplierArticle: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.bookingId}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, bookingId: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.demandId}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, demandId: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setBomDraftRows((prev) => prev.filter((_, i) => i !== idx));
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
          <div className='text-xs font-semibold text-gray-500'>Bill of Material: Production Units and Processing Capabilities</div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : bomProdUnitsRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No BoM Production Units detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1700px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Position</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Placement</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Type</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Material Supplier</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Composition</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Weight</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Production Unit / Processing Capability</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomProdUnitsRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.position} disabled />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.placement} disabled />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.type} disabled />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.materialSupplier}
                          rows={2}
                          disabled
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.composition}
                          rows={2}
                          disabled
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.weight} disabled />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.productionUnitProcessingCapability}
                          rows={2}
                          disabled
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
          <div className='text-xs font-semibold text-gray-500'>Bill of Material: Yarn Source Details</div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : bomYarnSourceTableRows.length < 2 ? (
            <div className='text-sm text-gray-500 italic'>No BoM Yarn Source Details detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1400px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    {(bomYarnSourceTableRows?.[0] ?? []).map((h, i) => (
                      <th key={i} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomYarnSourceTableRows.slice(1).map((r, ridx) => (
                    <tr key={ridx} className='border-b border-gray-100 last:border-b-0'>
                      {r.map((c, cidx) => (
                        <td key={cidx} className='px-3 py-2 align-top'>
                          {String(c ?? '').length > 40 ? (
                            <textarea
                              className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                              value={c ?? ''}
                              rows={2}
                              disabled
                            />
                          ) : (
                            <Input value={c ?? ''} disabled />
                          )}
                        </td>
                      ))}
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
          <div className='text-xs font-semibold text-gray-500'>Product Article</div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : productArticleTableRows.length < 2 ? (
            <div className='text-sm text-gray-500 italic'>No Product Article detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1600px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    {(productArticleTableRows?.[0] ?? []).map((h, i) => (
                      <th key={i} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {productArticleTableRows.slice(1).map((r, ridx) => (
                    <tr key={ridx} className='border-b border-gray-100 last:border-b-0'>
                      {r.map((c, cidx) => (
                        <td key={cidx} className='px-3 py-2 align-top'>
                          {String(c ?? '').length > 40 ? (
                            <textarea
                              className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                              value={c ?? ''}
                              rows={2}
                              disabled
                            />
                          ) : (
                            <Input value={c ?? ''} disabled />
                          )}
                        </td>
                      ))}
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
          <div className='text-xs font-semibold text-gray-500'>Miscellaneous</div>
        </div>
        <div className='p-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : miscellaneousTableRows.length < 2 ? (
            <div className='text-sm text-gray-500 italic'>No Miscellaneous detected.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1400px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    {(miscellaneousTableRows?.[0] ?? []).map((h, i) => (
                      <th key={i} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {miscellaneousTableRows.slice(1).map((r, ridx) => (
                    <tr key={ridx} className='border-b border-gray-100 last:border-b-0'>
                      {r.map((c, cidx) => (
                        <td key={cidx} className='px-3 py-2 align-top'>
                          {String(c ?? '').length > 40 ? (
                            <textarea
                              className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                              value={c ?? ''}
                              rows={2}
                              disabled
                            />
                          ) : (
                            <Input value={c ?? ''} disabled />
                          )}
                        </td>
                      ))}
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

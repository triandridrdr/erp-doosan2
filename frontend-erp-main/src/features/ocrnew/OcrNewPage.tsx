import { useMutation } from '@tanstack/react-query';
import { AlertCircle, Loader2, Upload, CheckCircle2 } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import * as XLSX from 'xlsx';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import { Modal } from '../../components/ui/Modal';
import { ocrNewApi } from './api';
import type { OcrNewDocumentAnalysisResponseData } from './types';
import { salesOrderPrototypeApi } from '../salesOrderPrototype/api';

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
  const navigate = useNavigate();
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [activeFileIndex, setActiveFileIndex] = useState<number>(0);
  const [results, setResults] = useState<Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>>([]);
  const [data, setData] = useState<OcrNewDocumentAnalysisResponseData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [multiFileLogs, setMultiFileLogs] = useState<string[]>([]);
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

  // Draft rows for SECTION 2B – TOTAL COUNTRY BREAKDOWN (editable like Section 2)
  const [countryBreakdownDraftRows, setCountryBreakdownDraftRows] = useState<
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

  const [section2cDraftRows, setSection2cDraftRows] = useState<
    Array<{
      article: string;
      size: string;
      qty: string;
      editable: boolean;
    }>
  >([]);

  const [section2cTotalDraftRows, setSection2cTotalDraftRows] = useState<
    Array<{
      article: string;
      total: string;
      editable: boolean;
    }>
  >([]);
  const [section2cTotalDirty, setSection2cTotalDirty] = useState(false);

  const appendLog = (msg: string) => {
    const ts = new Date().toISOString();
    setMultiFileLogs((prev) => [...prev, `[${ts}] ${msg}`]);
  };

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

  const hydrateDraftsFromData = (d: OcrNewDocumentAnalysisResponseData | null) => {
    const ff = d?.formFields ?? {};
    const next: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      next[f] = ff[f] ?? '';
    }
    setSalesOrderHeaderDraft(next);

    const bomTable = (d?.tables ?? []).find((t) => isBomDraftTable(t.rows));
    if (bomTable?.rows?.length) {
      const rows = bomTable.rows.slice(1).map((r) => ({
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
      setSalesOrderDetailDraftRows(pivotDetailRows(backendDetail));
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
      const bomTable = (r?.data?.tables ?? []).find((t) => isBomDraftTable(t.rows));
      if (bomTable?.rows?.length) {
        bomRows = bomTable.rows.slice(1).map((row) => ({
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
        detailRows = pivotDetailRows(backendDetail);
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
          const res = await ocrNewApi.analyze(f);
          const dtMs = Math.round(performance.now() - t0);
          const pc = res?.data?.pageCount ?? 0;
          const tc = res?.data?.tables?.length ?? 0;
          const dc = (res?.data?.salesOrderDetailSizeBreakdown ?? []).length;
          appendLog(`OK file=${f.name} durationMs=${dtMs} pageCount=${pc} tableCount=${tc} detailRowCount=${dc}`);
          logBackendSection2DetailRows(f.name, res?.data);
          if (res?.data) {
            out.push({ fileName: f.name, data: res.data });
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
        source: 'ocr-new',
        analyzedFileName: results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '',
        formFields: salesOrderHeaderDraft,
        bomDraftRows,
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
        section2cColourSizeBreakdownTotal: section2cTotalDraftRows,
        raw: data,
      };
      return salesOrderPrototypeApi.create(payload);
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

  // Pick backend-provided Total Country Breakdown STRICTLY from TotalCountryBreakdown file
  const backendCountryBreakdown = useMemo(() => {
    const isTcbName = (name: string) => {
      const n = (name || '').toLowerCase();
      return n.includes('totalcountrybreakdown') || n.includes('total_country_breakdown') || (n.includes('total') && n.includes('country') && n.includes('breakdown'));
    };

    // Check current analyzed file first: only accept if filename looks like TCB
    const curFile = results[activeFileIndex]?.fileName ?? '';
    if (isTcbName(curFile)) {
      const curRows = data?.totalCountryBreakdown ?? [];
      if (curRows.length > 0) return { fileName: curFile, rows: curRows };
      return null; // Do NOT fallback to other files if TCB file has 0 rows
    }

    // Otherwise, find the uploaded file whose name looks like TCB
    const tcb = results.find((r) => isTcbName(r.fileName ?? ''));
    if (!tcb) return null;
    const rows = tcb?.data?.totalCountryBreakdown ?? [];
    if (rows.length === 0) return null; // Strict: only show when TCB has rows
    return { fileName: tcb.fileName ?? '', rows };
  }, [data, results, activeFileIndex]);

  const extractArticleNoColourLabel = (d: OcrNewDocumentAnalysisResponseData | null | undefined) => {
    const ff = d?.formFields ?? {};

    const pickKv = (keyAlts: string[]) => {
      const kvs = d?.keyValuePairs ?? [];
      for (const kv of kvs) {
        const k = (kv?.key ?? '').toString().trim().toLowerCase();
        if (!k) continue;
        if (keyAlts.some((a) => a.toLowerCase() === k)) {
          const v = (kv?.value ?? '').toString().trim();
          if (v) return v;
        }
      }
      return '';
    };

    const pickLine = (keyAlts: string[]) => {
      const lines = d?.lines ?? [];
      for (const ln of lines) {
        const t = (ln?.text ?? '').toString().trim();
        if (!t) continue;
        for (const a of keyAlts) {
          const re = new RegExp(`^\\s*${a.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*:\\s*(.+)\\s*$`, 'i');
          const m = t.match(re);
          if (m?.[1]) {
            const v = m[1].trim();
            if (v) return v;
          }
        }
      }
      return '';
    };

    const articleNo =
      (ff['Article No'] ?? ff['Article'] ?? '').toString().trim() ||
      pickKv(['Article No', 'Article']) ||
      pickLine(['Article No', 'Article']);

    const hmColourCode =
      (ff['H&M Colour Code'] ?? ff['H&M Colour'] ?? ff['H&M Color Code'] ?? '').toString().trim() ||
      pickKv(['H&M Colour Code', 'H&M Colour', 'H&M Color Code']) ||
      pickLine(['H&M Colour Code', 'H&M Colour', 'H&M Color Code']);

    return [articleNo, hmColourCode].filter((v) => v.length > 0).join(' ').trim();
  };

  const section2cArticleLabelFromTcb = useMemo(() => {
    if (!backendCountryBreakdown?.fileName) return '';
    const tcbRes = results.find((r) => (r?.fileName ?? '') === backendCountryBreakdown.fileName);
    return extractArticleNoColourLabel(tcbRes?.data);
  }, [backendCountryBreakdown?.fileName, results]);

  // Hydrate SECTION 2B draft when backendCountryBreakdown changes
  useEffect(() => {
    if (!backendCountryBreakdown) {
      setCountryBreakdownDraftRows([]);
      return;
    }
    const keys = Object.keys(backendCountryBreakdown.rows[0] ?? {});
    const toVal = (m: Record<string, any>, k: string) => (m?.[k] ?? '').toString();
    const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
    const kCountry = findKey(['country', 'destinationcountry', 'countryofdestination']) ?? 'country';
    const kPm = findKey(['pmcode', 'pm', 'pm_code']) ?? 'pmCode';
    const kTotal = findKey(['total', 'tot']) ?? 'total';
    const rows = backendCountryBreakdown.rows.map((m) => ({
      country: toVal(m, kCountry),
      countryOfDestination: '',
      pmCode: toVal(m, kPm),
      total: toVal(m, kTotal),
      editable: true,
    }));
    setCountryBreakdownDraftRows(rows);
  }, [backendCountryBreakdown]);

  const section2cSizeSummary = useMemo(() => {
    const draftRows = salesOrderDetailDraftRows ?? [];
    if (!Array.isArray(draftRows) || draftRows.length === 0) return null;

    const hasTotalType = draftRows.some((r) => (r?.type ?? '').toString().toLowerCase() === 'total');
    const rowsForSum = hasTotalType
      ? draftRows.filter((r) => (r?.type ?? '').toString().toLowerCase() !== 'total')
      : draftRows;

    const sizesSet = new Set<string>();
    for (const r of rowsForSum) {
      const sz = normalizeSizeKey((r?.size ?? '').toString());
      if (sz) sizesSet.add(sz);
    }
    if (sizesSet.size === 0) return null;

    const preferredOrder = ['XS', 'S', 'M', 'L', 'XL', 'XS/P', 'S/P', 'M/P', 'L/P', 'XL/P'];
    const normalizeKey = (s: string) => s.toUpperCase().replace(/\s+/g, '');
    const sizes = Array.from(sizesSet);
    sizes.sort((a, b) => {
      const na = normalizeKey(a);
      const nb = normalizeKey(b);
      const ia = preferredOrder.indexOf(na);
      const ib = preferredOrder.indexOf(nb);
      if (ia >= 0 && ib >= 0) return ia - ib;
      if (ia >= 0) return -1;
      if (ib >= 0) return 1;
      return na.localeCompare(nb);
    });

    const totalsBySize = new Map<string, number>();
    for (const sz of sizes) totalsBySize.set(sz, 0);

    for (const r of rowsForSum) {
      const sz = normalizeSizeKey((r?.size ?? '').toString());
      if (!sz) continue;
      const qtyDigits = normalizeDigits((r?.qty ?? '').toString());
      if (!qtyDigits) continue;
      const cur = totalsBySize.get(sz) ?? 0;
      totalsBySize.set(sz, cur + Number(qtyDigits));
    }

    const sizeTotals = sizes.map((k) => ({ key: k, total: totalsBySize.get(k) ?? 0 }));
    const grandTotal = sizeTotals.reduce((acc, x) => acc + (Number.isFinite(x.total) ? x.total : 0), 0);

    const ff = data?.formFields ?? {};
    const optionNo = (ff['Option No'] ?? ff['Option'] ?? '').toString().trim();
    const articleNo = (ff['Article / Product No'] ?? ff['Article No'] ?? ff['Article'] ?? '').toString().trim();
    const fromActive = extractArticleNoColourLabel(data);
    const fallbackLabel = fromActive || [optionNo].filter((v) => v.length > 0).join(' ') || articleNo || '-';
    const articleLabel = section2cArticleLabelFromTcb || fallbackLabel;

    return {
      articleLabel,
      sizeKeys: sizes,
      sizeTotals,
      grandTotal,
    };
  }, [salesOrderDetailDraftRows, data, section2cArticleLabelFromTcb]);

  const section2cTotalFrom2b = useMemo(() => {
    if (!backendCountryBreakdown) return null;
    const keys = Object.keys(backendCountryBreakdown.rows?.[0] ?? {});
    const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
    const kTotal = findKey(['total', 'tot']) ?? 'total';

    let sum = 0;
    for (const r of backendCountryBreakdown.rows ?? []) {
      const d = normalizeDigits((r?.[kTotal] ?? '').toString());
      if (d) sum += Number(d);
    }
    if (sum <= 0) return null;

    const ff = data?.formFields ?? {};
    const optionNo = (ff['Option No'] ?? ff['Option'] ?? '').toString().trim();
    const articleNo = (ff['Article / Product No'] ?? ff['Article No'] ?? ff['Article'] ?? '').toString().trim();
    const fromActive = extractArticleNoColourLabel(data);
    const fallbackLabel = fromActive || [optionNo].filter((v) => v.length > 0).join(' ') || articleNo || '-';
    const articleLabel = section2cArticleLabelFromTcb || fallbackLabel;

    return { articleLabel, total: sum.toString() };
  }, [backendCountryBreakdown, data, section2cArticleLabelFromTcb]);

  useEffect(() => {
    if (!section2cSizeSummary) {
      setSection2cDraftRows([]);
      return;
    }
    setSection2cDraftRows(
      section2cSizeSummary.sizeTotals.map((x) => ({
        article: section2cSizeSummary.articleLabel,
        size: x.key,
        qty: x.total.toString(),
        editable: true,
      })),
    );
  }, [section2cSizeSummary]);

  useEffect(() => {
    if (!section2cTotalFrom2b) {
      setSection2cTotalDraftRows([]);
      setSection2cTotalDirty(false);
      return;
    }
    if (section2cTotalDirty) return;
    setSection2cTotalDraftRows([
      {
        article: section2cTotalFrom2b.articleLabel,
        total: section2cTotalFrom2b.total,
        editable: true,
      },
    ]);
  }, [section2cTotalFrom2b, section2cTotalDirty]);

  const section2NonTotalEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => (row?.type ?? '').toString().trim().toLowerCase() !== 'total');
  }, [salesOrderDetailDraftRows]);

  const section2TotalByCountryRows = useMemo(() => {
    const totals = (salesOrderDetailDraftRows ?? []).filter(
      (r) => (r?.type ?? '').toString().trim().toLowerCase() === 'total',
    );
    if (totals.length === 0) return [];

    const byCountry = new Map<string, number>();
    const normKey = (v: string) => v.toString().trim();

    for (const r of totals) {
      const countryOfDestination = normKey((r?.countryOfDestination ?? '').toString());
      const qty = Number(normalizeDigits((r?.qty ?? '').toString()) || '0');
      byCountry.set(countryOfDestination, (byCountry.get(countryOfDestination) ?? 0) + qty);
    }

    return Array.from(byCountry.entries()).map(([countryOfDestination, total]) => ({ countryOfDestination, total }));
  }, [salesOrderDetailDraftRows]);

  const section2TotalCountryLookup = useMemo(() => {
    const m = new Map<string, string>();
    for (const r of section2TotalByCountryRows ?? []) {
      const c = (r?.countryOfDestination ?? '').toString().trim();
      if (!c) continue;
      m.set(c.toLowerCase(), c);

      const mCode = c.match(/\b([A-Z]{2})\b/);
      const code = (mCode?.[1] ?? '').toString().trim().toLowerCase();
      if (code) m.set(code, c);
    }
    return m;
  }, [section2TotalByCountryRows]);

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
      Editable: row.editable ? 'TRUE' : 'FALSE',
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
            <h3 className='text-lg font-bold text-gray-900'>OCR New</h3>
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
                setMultiFileLogs([]);
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
            {results.map((r, idx) => (
              <button
                key={r.fileName}
                type='button'
                className={
                  idx === activeFileIndex
                    ? 'px-3 py-1 rounded-lg text-xs font-semibold bg-indigo-600 text-white'
                    : 'px-3 py-1 rounded-lg text-xs font-semibold bg-gray-100 text-gray-700'
                }
                onClick={() => {
                  setActiveFileIndex(idx);
                  const nextData = results[idx]?.data ?? null;
                  setData(nextData);

                  hydrateDraftsFromData(nextData);
                }}
              >
                {r.fileName}
              </button>
            ))}
          </div>
        )}

        {multiFileLogs.length > 0 && (
          <div className='mt-4 rounded-2xl border border-gray-200 bg-gray-50 overflow-hidden'>
            <div className='px-4 py-2 border-b border-gray-200 text-xs font-semibold text-gray-600'>Logs</div>
            <pre className='p-4 text-[11px] leading-relaxed text-gray-800 whitespace-pre-wrap max-h-[240px] overflow-auto'>
              {multiFileLogs.join('\n')}
            </pre>
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 2 – SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='secondary'
              disabled={!data || section2NonTotalEntries.length === 0}
              onClick={exportSection2SizeBreakdownToExcel}
            >
              Convert to Excel
            </Button>
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Color</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Total</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>No of Asst</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Editable</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {section2NonTotalEntries.map(({ row, idx }) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.countryOfDestination}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: v } : r)),
                            );
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.type}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, type: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.color}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <SizeAutocompleteInput
                          value={row.size}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.qty)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(v) } : r)),
                            );
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(v) } : r)),
                            );
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top whitespace-nowrap'>
                        <Input
                          value={formatIdThousands(row.noOfAsst ?? '')}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, noOfAsst: normalizeDigits(v) } : r)),
                            );
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top whitespace-nowrap'>{row.editable ? 'TRUE' : 'FALSE'}</td>
                      <td className='px-3 py-2 align-top'>
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
          )}
        </div>
      </div>

      {/* SECTION 2B – TOTAL COUNTRY BREAKDOWN (from backend) */}
      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 2B – TOTAL COUNTRY BREAKDOWN</div>
            {backendCountryBreakdown && (
              <div className='text-xs text-gray-400 mt-0.5'>Source: {backendCountryBreakdown.fileName}</div>
            )}
          </div>
          <Button
            type='button'
            variant='primary'
            disabled={!backendCountryBreakdown}
            onClick={() => {
              setCountryBreakdownDraftRows((prev) => [
                ...prev,
                { country: '', pmCode: '', countryOfDestination: '', total: '', editable: true },
              ]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6'>
          {!backendCountryBreakdown ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : countryBreakdownDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No country breakdown detected.</div>
          ) : (
            <div className='w-full max-h-[50vh] overflow-auto'>
              <table className='min-w-[800px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>PM Code</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Total</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {countryBreakdownDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.country}
                          onChange={(e) => {
                            const v = e.target.value;
                            setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, country: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.pmCode}
                          onChange={(e) => {
                            const v = e.target.value;
                            setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, pmCode: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={
                            (row.countryOfDestination ?? '').toString() ||
                            section2TotalCountryLookup.get((row.country ?? '').toString().trim().toLowerCase()) ||
                            ''
                          }
                          onChange={(e) => {
                            const v = e.target.value;
                            setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
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
          <div className='text-xs font-semibold text-gray-500'>SECTION 2C – COLOUR / SIZE BREAKDOWN</div>
        </div>
        <div className='p-6 space-y-6'>
          {!section2cSizeSummary ? (
            <div className='text-sm text-gray-500 italic'>No size breakdown detected.</div>
          ) : (
            <div className='w-full max-h-[50vh] overflow-auto'>
              <table className='min-w-[800px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Article</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Editable</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {section2cDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.article}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSection2cDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, article: v } : r)));
                          }}
                        />
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
                          onChange={(e) => {
                            const v = e.target.value;
                            setSection2cDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top whitespace-nowrap'>{row.editable ? 'TRUE' : 'FALSE'}</td>
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
                  <tr className='border-b border-gray-100 last:border-b-0'>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'>Total:</td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'>{formatIdThousands((section2cTotalFrom2b?.total ?? section2cSizeSummary.grandTotal.toString()).toString())}</td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}

          {!section2cTotalFrom2b ? null : (
            <div className='w-full max-w-[420px] overflow-auto'>
              <table className='min-w-[360px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Article</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Total:</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Editable</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {section2cTotalDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.article}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSection2cTotalDirty(true);
                            setSection2cTotalDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, article: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSection2cTotalDirty(true);
                            setSection2cTotalDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top whitespace-nowrap'>{row.editable ? 'TRUE' : 'FALSE'}</td>
                    </tr>
                  ))}
                  <tr className='border-b border-gray-100 last:border-b-0'>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'>Total:</td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'>
                      {formatIdThousands((section2cTotalDraftRows?.[0]?.total ?? section2cTotalFrom2b.total).toString())}
                    </td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 3 – BILL OF MATERIALS (BOM DRAFT)</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data}
            onClick={() => {
              setBomDraftRows((prev) => [
                ...prev,
                { position: '', placement: '', type: '', description: '', composition: '', materialSupplier: '' },
              ]);
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
              <table className='min-w-[1400px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Position</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Placement</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Type</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Description</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Composition</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Material Supplier</th>
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
    </div>
  );
}

const DETAIL_SIZES = ['XS', 'S', 'M', 'L', 'XL', 'XS/P', 'S/P', 'M/P', 'L/P', 'XL/P'] as const;

function normalizeSizeKey(input: string): string {
  const s = (input ?? '').toString().trim();
  if (!s) return '';
  return s
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/\*/g, '');
}

function pickSizeValue(m: Record<string, any>, sizeKey: string): string {
  if (!m) return '';
  const target = normalizeSizeKey(sizeKey);
  const keys = Object.keys(m ?? {});
  for (const k of keys) {
    if (normalizeSizeKey(k) === target) {
      return (m?.[k] ?? '').toString();
    }
  }
  for (const k of keys) {
    const mk = k.match(/\(\s*([A-Za-z]+(?:\s*\/\s*P)?)\s*\)/);
    if (mk?.[1]) {
      const extracted = normalizeSizeKey(mk[1]);
      if (extracted === target) {
        return (m?.[k] ?? '').toString();
      }
    }
  }
  // Fallback for legacy keys that are already normalized (xs, xs/p)
  const low = target.toLowerCase();
  return (m?.[target] ?? m?.[low] ?? '').toString();
}

function pivotDetailRows(
  backendRows: Array<Record<string, any>>,
): Array<{
  countryOfDestination: string;
  type: string;
  color: string;
  size: string;
  qty: string;
  total: string;
  noOfAsst: string;
  editable: boolean;
}> {
  return backendRows
    .flatMap((m) => {
      // Already pivoted (has 'size' field and no separate size columns)
      if (m?.size !== undefined && m?.XS === undefined && m?.xs === undefined) {
        return [
          {
            countryOfDestination: (m?.countryOfDestination ?? m?.destinationCountry ?? '').toString(),
            type: (m?.type ?? '').toString(),
            color: (m?.color ?? m?.colour ?? '').toString(),
            size: normalizeSizeKey((m?.size ?? '').toString()),
            qty: (m?.qty ?? '').toString(),
            total: (m?.total ?? m?.Total ?? '').toString(),
            noOfAsst: (m?.noOfAsst ?? '').toString(),
            editable: true,
          },
        ];
      }
      // Pivot: one backend row with XS/S/M/L/XL -> 5 rows (one per size)
      const country = (m?.countryOfDestination ?? m?.destinationCountry ?? '').toString();
      const type = (m?.type ?? '').toString();
      const color = (m?.color ?? m?.colour ?? '').toString();
      const total = (m?.total ?? m?.Total ?? '').toString();
      const noOfAsst = (m?.noOfAsst ?? '').toString();
      return DETAIL_SIZES.map((sz) => ({
        countryOfDestination: country,
        type,
        color,
        size: sz,
        qty: pickSizeValue(m, sz),
        total,
        noOfAsst,
        editable: true,
      }));
    })
    .filter((r) =>
      [r.countryOfDestination, r.type, r.color, r.size, r.qty, r.total].some((v) => v.trim().length > 0),
    );
}

function normalizeDigits(input: string): string {
  if (!input) return '';
  // keep digits only
  return (input || '').replace(/\D+/g, '');
}

const idFormatter = new Intl.NumberFormat('id-ID');

function formatIdThousands(input: string): string {
  const digits = normalizeDigits(input || '');
  if (!digits) return '';
  try {
    return idFormatter.format(Number(digits));
  } catch {
    return digits;
  }
}

function isBomDraftTable(rows: Array<Array<string>> | undefined): boolean {
  if (!rows || rows.length === 0) return false;
  const header = rows[0] ?? [];
  const norm = (s: string | undefined) => (s ?? '').toLowerCase().replace(/\s+/g, ' ').trim();
  if (header.length < 6) return false;
  return (
    norm(header[0]) === 'position' &&
    norm(header[1]) === 'placement' &&
    norm(header[2]) === 'type' &&
    norm(header[3]) === 'description' &&
    norm(header[4]) === 'composition' &&
    norm(header[5]) === 'material supplier'
  );
}

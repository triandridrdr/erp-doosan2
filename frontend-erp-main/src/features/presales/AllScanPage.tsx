import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
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

export function AllScanPage() {
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
    Array<{ position: string; placement: string; type: string; description: string; composition: string; materialSupplier: string }>
  >([]);

  const [countryBreakdownDraftRows, setCountryBreakdownDraftRows] = useState<
    Array<{ country: string; countryOfDestination?: string; pmCode: string; total: string; editable: boolean }>
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

  const appendLog = (msg: string) => {
    const ts = new Date().toISOString();
    setMultiFileLogs((prev) => [...prev, `[${ts}] ${msg}`]);
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

    const rows2b = d?.totalCountryBreakdown ?? [];
    if (!Array.isArray(rows2b) || rows2b.length === 0) {
      setCountryBreakdownDraftRows([]);
    } else {
      const keys = Object.keys(rows2b[0] ?? {});
      const toVal = (m: Record<string, any>, k: string) => (m?.[k] ?? '').toString();
      const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
      const kCountry = findKey(['country', 'destinationcountry', 'countryofdestination']) ?? 'country';
      const kPm = findKey(['pmcode', 'pm', 'pm_code']) ?? 'pmCode';
      const kTotal = findKey(['total', 'tot']) ?? 'total';
      const out = (rows2b as any[]).map((m) => ({
        country: toVal(m, kCountry),
        countryOfDestination: '',
        pmCode: toVal(m, kPm),
        total: toVal(m, kTotal),
        editable: true,
      }));
      setCountryBreakdownDraftRows(out);
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

    let bomRows: Array<{ position: string; placement: string; type: string; description: string; composition: string; materialSupplier: string }> = [];
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

  const backendCountryBreakdown = useMemo(() => {
    const isTcbName = (name: string) => {
      const n = (name || '').toLowerCase();
      return (
        n.includes('totalcountrybreakdown') ||
        n.includes('total_country_breakdown') ||
        (n.includes('total') && n.includes('country') && n.includes('breakdown'))
      );
    };

    const curFile = results[activeFileIndex]?.fileName ?? '';
    if (isTcbName(curFile) && (data?.totalCountryBreakdown ?? []).length > 0) {
      return { fileName: curFile, rows: data?.totalCountryBreakdown ?? [] };
    }

    const tcb = results.find((r) => isTcbName(r.fileName ?? '') && (r?.data?.totalCountryBreakdown ?? []).length > 0);
    if (!tcb) return null;
    return { fileName: tcb.fileName ?? '', rows: tcb?.data?.totalCountryBreakdown ?? [] };
  }, [data, results, activeFileIndex]);

  const extractArticleNoColourLabel = (d: OcrNewDocumentAnalysisResponseData | null | undefined) => {
    if (!d) return '';
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

  const backendColourSizeBreakdown = useMemo(() => {
    const isTcbName = (name: string) => {
      const n = (name || '').toLowerCase();
      return (
        n.includes('totalcountrybreakdown') ||
        n.includes('total_country_breakdown') ||
        (n.includes('total') && n.includes('country') && n.includes('breakdown'))
      );
    };

    const curFile = results[activeFileIndex]?.fileName ?? '';
    let source: { fileName: string; rows: Array<Record<string, string>> } | null = null;
    if (isTcbName(curFile)) {
      const rows = data?.colourSizeBreakdown ?? [];
      if (rows.length > 0) source = { fileName: curFile, rows };
    }
    if (!source) {
      const tcb = results.find((r) => isTcbName(r.fileName ?? '') && (r?.data?.colourSizeBreakdown ?? []).length > 0);
      if (tcb) source = { fileName: tcb.fileName ?? '', rows: tcb?.data?.colourSizeBreakdown ?? [] };
    }
    if (!source || source.rows.length === 0) return null;

    const META_KEYS = new Set(['article', 'total']);
    const exploded: Array<{ article: string; size: string; qty: string; editable: boolean }> = [];
    let articleLabel = '';
    for (const row of source.rows) {
      const article = (row?.article ?? '').toString().trim();
      if (!articleLabel && article) articleLabel = article;
      for (const [key, val] of Object.entries(row ?? {})) {
        if (META_KEYS.has(key.toLowerCase())) continue;
        const qty = (val ?? '').toString().trim();
        if (!qty) continue;
        exploded.push({ article, size: key, qty, editable: true });
      }
    }
    if (exploded.length === 0) return null;
    return { fileName: source.fileName, rows: exploded, articleLabel };
  }, [data, results, activeFileIndex]);

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
    const normalizeKey = (s: string) => s.toUpperCase().replace(/\\s+/g, '');
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

    const ff = data?.formFields ?? {};
    const optionNo = (ff['Option No'] ?? ff['Option'] ?? '').toString().trim();
    const articleNo = (ff['Article / Product No'] ?? ff['Article No'] ?? ff['Article'] ?? '').toString().trim();
    const fromActive = extractArticleNoColourLabel(data);
    const fallbackLabel = fromActive || [optionNo].filter((v) => v.length > 0).join(' ') || articleNo || '-';
    const articleLabel = section2cArticleLabelFromTcb || fallbackLabel;

    return {
      articleLabel,
      sizeTotals,
    };
  }, [salesOrderDetailDraftRows, data, section2cArticleLabelFromTcb]);

  useEffect(() => {
    if (backendColourSizeBreakdown) {
      const articleLabel = section2cArticleLabelFromTcb || backendColourSizeBreakdown.articleLabel;
      setSection2cDraftRows(
        backendColourSizeBreakdown.rows.map((r) => ({
          article: articleLabel || r.article,
          size: r.size,
          qty: r.qty,
          editable: true,
        })),
      );
      return;
    }
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
  }, [backendColourSizeBreakdown, section2cSizeSummary, section2cArticleLabelFromTcb]);

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
          if (resData) out.push({ fileName: f.name, data: resData });
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
      setCountryBreakdownDraftRows([]);
      setSection2cDraftRows([]);
      setMultiFileLogs([]);
      window.scrollTo({ top: 0, behavior: 'smooth' });
    },
  });

  const isPending = analyzeMutation.isPending;

  const saveDraftMutation = useMutation({
    mutationFn: async () => {
      if (!data) throw new Error('No data.');
      const payload = {
        source: 'presales-all',
        analyzedFileName: results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '',
        formFields: salesOrderHeaderDraft,
        bomDraftRows,
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
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

  const section2NonTotalEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => (row?.type ?? '').toString().trim().toLowerCase() !== 'total');
  }, [salesOrderDetailDraftRows]);

  const section2TotalByCountryRows = useMemo(() => {
    const totals = (salesOrderDetailDraftRows ?? []).filter((r) => (r?.type ?? '').toString().trim().toLowerCase() === 'total');
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

      const allCodes = c.match(/\b[A-Z]{2}\b/g) ?? [];
      for (const code of allCodes) {
        const lc = code.toLowerCase();
        if (!m.has(lc)) m.set(lc, c);
      }

      const pmMatch = c.match(/\(([A-Z]{2}[A-Z0-9-]+)\)?/);
      if (pmMatch) {
        const pmCode = pmMatch[1].toUpperCase();
        m.set(pmCode.toLowerCase(), c);
        const normalized = pmCode.replace(/^(OL|PM)(?!-)/, '$1-');
        if (normalized !== pmCode) m.set(normalized.toLowerCase(), c);
      }
    }
    return m;
  }, [section2TotalByCountryRows]);

  useEffect(() => {
    if (results.length <= 1) return;
    setData(results[activeFileIndex]?.data ?? null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeFileIndex]);

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
            <h3 className='text-lg font-bold text-gray-900'>All</h3>
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
                setCountryBreakdownDraftRows([]);
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

        {results.length > 1 && (
          <div className='mt-4 flex flex-wrap gap-2'>
            {results.map((r) => (
              <button
                key={r.fileName}
                type='button'
                onClick={() => setActiveFileIndex(results.findIndex((x) => x.fileName === r.fileName))}
                className={
                  'px-3 py-1 rounded-lg text-xs font-semibold ' +
                  (results[activeFileIndex]?.fileName === r.fileName ? 'bg-primary text-white' : 'bg-gray-100 text-gray-700')
                }
              >
                {r.fileName}
              </button>
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
          <div className='text-xs font-semibold text-gray-500'>SECTION 2 – SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='primary'
              disabled={!data}
              onClick={() =>
                setSalesOrderDetailDraftRows((prev) => [
                  ...prev,
                  { countryOfDestination: '', type: '', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                ])
              }
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Color</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>No of Asst</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Actions</th>
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
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: v } : r)));
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
                          value={formatSizeDisplay(row.size)}
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
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top whitespace-nowrap'>
                        <Input
                          value={formatIdThousands(row.noOfAsst ?? '')}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 2B – TOTAL COUNTRY BREAKDOWN</div>
            {backendCountryBreakdown && <div className='text-xs text-gray-400 mt-0.5'>Source: {backendCountryBreakdown.fileName}</div>}
          </div>
          <Button
            type='button'
            variant='primary'
            disabled={!backendCountryBreakdown}
            onClick={() => {
              setCountryBreakdownDraftRows((prev) => [...prev, { country: '', pmCode: '', countryOfDestination: '', total: '', editable: true }]);
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
                            section2TotalCountryLookup.get((row.pmCode ?? '').toString().trim().toLowerCase()) ||
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
        <div className='p-6 space-y-6'>
          {!section2cSizeSummary && !backendColourSizeBreakdown && section2cDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No size breakdown detected.</div>
          ) : (
            <div className='w-full max-h-[50vh] overflow-auto'>
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 3 – BILL OF MATERIALS (BOM DRAFT)</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data}
            onClick={() => {
              setBomDraftRows((prev) => [...prev, { position: '', placement: '', type: '', description: '', composition: '', materialSupplier: '' }]);
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

function normalizeDigits(v: string) {
  const d = (v ?? '').toString().replace(/[^0-9]/g, '');
  return d.length ? d : '';
}

function formatIdThousands(input: string): string {
  const digits = normalizeDigits(input || '');
  if (!digits) return '';
  try {
    return idFormatter.format(Number(digits));
  } catch {
    return digits;
  }
}

function formatSizeDisplay(input: string): string {
  const s = (input ?? '').toString().trim();
  if (!s) return '';
  const norm = normalizeSizeKey(s);
  if ((norm === 'XS' || norm === 'S' || norm === 'M' || norm === 'L' || norm === 'XL') && !s.includes('(')) {
    return `${norm} (${norm})*`;
  }
  return s;
}

function normalizeSizeKey(input: string): string {
  const s = (input ?? '').toString().trim();
  if (!s) return '';
  return s
    .toUpperCase()
    .replace(/\s+/g, '')
    .replace(/\*/g, '');
}

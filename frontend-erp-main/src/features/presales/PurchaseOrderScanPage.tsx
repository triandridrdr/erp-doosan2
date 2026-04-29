import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
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

export function PurchaseOrderScanPage() {
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

  const normalizeDigits = (v: string) => {
    const d = (v ?? '').toString().replace(/[^0-9]/g, '');
    return d.length ? d : '';
  };

  const normalizeSizeKey = (s: string) => {
    const t = (s ?? '').toString().trim();
    if (!t) return '';
    return t.toUpperCase().replace(/\s+/g, '');
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
          const res = await ocrNewApi.analyze(f);
          const dtMs = Math.round(performance.now() - t0);
          const pc = (res as any)?.data?.pageCount ?? 0;
          const tc = (res as any)?.data?.tables?.length ?? 0;
          const dc = ((res as any)?.data?.salesOrderDetailSizeBreakdown ?? []).length;
          appendLog(`OK file=${f.name} durationMs=${dtMs} pageCount=${pc} tableCount=${tc} detailRowCount=${dc}`);
          logBackendSection2DetailRows(f.name, (res as any)?.data);
          if ((res as any)?.data) {
            out.push({ fileName: f.name, data: (res as any).data });
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
        source: 'presales-purchase-order',
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

  const backendCountryBreakdown = useMemo(() => {
    const isTcbName = (name: string) => {
      const n = (name || '').toLowerCase();
      return n.includes('totalcountrybreakdown') || n.includes('total_country_breakdown') || (n.includes('total') && n.includes('country') && n.includes('breakdown'));
    };

    const curFile = results[activeFileIndex]?.fileName ?? '';
    if (isTcbName(curFile)) {
      const curRows = data?.totalCountryBreakdown ?? [];
      if (curRows.length > 0) return { fileName: curFile, rows: curRows };
      return null;
    }

    const tcb = results.find((r) => isTcbName(r.fileName ?? ''));
    if (!tcb) return null;
    const rows = tcb?.data?.totalCountryBreakdown ?? [];
    if (rows.length === 0) return null;
    return { fileName: tcb.fileName ?? '', rows };
  }, [data, results, activeFileIndex]);

  const extractArticleNoColourLabel = (d: OcrNewDocumentAnalysisResponseData | null | undefined) => {
    const ff = d?.formFields ?? {};

    const pickKv = (keyAlts: string[]) => {
      const kvs = d?.keyValuePairs ?? [];
      for (const kv of kvs as any[]) {
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
      for (const ln of lines as any[]) {
        const t = (ln?.text ?? '').toString().trim();
        if (!t) continue;
        for (const a of keyAlts) {
          const re = new RegExp(`^\\s*${a.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')}\\s*:\\s*(.+)\\s*$`, 'i');
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
      return n.includes('totalcountrybreakdown') || n.includes('total_country_breakdown') || (n.includes('total') && n.includes('country') && n.includes('breakdown'));
    };

    const curFile = results[activeFileIndex]?.fileName ?? '';
    let source: { fileName: string; rows: Array<Record<string, string>> } | null = null;
    if (isTcbName(curFile)) {
      const rows = data?.colourSizeBreakdown ?? [];
      if (rows.length > 0) source = { fileName: curFile, rows: rows as any };
    }
    if (!source) {
      const tcb = results.find((r) => isTcbName(r.fileName ?? '') && ((r as any)?.data?.colourSizeBreakdown ?? []).length > 0);
      if (tcb) source = { fileName: tcb.fileName ?? '', rows: ((tcb as any)?.data?.colourSizeBreakdown ?? []) as any };
    }
    if (!source || source.rows.length === 0) return null;

    const META_KEYS = new Set(['article', 'total']);
    const exploded: Array<{ article: string; size: string; qty: string; editable: boolean }> = [];
    let grandTotal = 0;
    let articleLabel = '';
    for (const row of source.rows) {
      const article = (row?.article ?? '').toString().trim();
      if (!articleLabel && article) articleLabel = article;
      for (const [key, val] of Object.entries(row ?? {})) {
        if (META_KEYS.has(key.toLowerCase())) continue;
        const qty = (val ?? '').toString().trim();
        if (!qty) continue;
        exploded.push({ article, size: key, qty, editable: true });
        const n = Number(qty.replace(/[^0-9]/g, ''));
        if (Number.isFinite(n)) grandTotal += n;
      }
    }
    if (exploded.length === 0) return null;
    return { fileName: source.fileName, rows: exploded, articleLabel, grandTotal };
  }, [data, results, activeFileIndex]);

  useEffect(() => {
    if (!backendCountryBreakdown) {
      setCountryBreakdownDraftRows([]);
      return;
    }
    const keys = Object.keys((backendCountryBreakdown.rows as any)?.[0] ?? {});
    const toVal = (m: Record<string, any>, k: string) => (m?.[k] ?? '').toString();
    const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
    const kCountry = findKey(['country', 'destinationcountry', 'countryofdestination']) ?? 'country';
    const kPm = findKey(['pmcode', 'pm', 'pm_code']) ?? 'pmCode';
    const kTotal = findKey(['total', 'tot']) ?? 'total';
    const rows = (backendCountryBreakdown.rows as any).map((m: any) => ({
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
    const rowsForSum = hasTotalType ? draftRows.filter((r) => (r?.type ?? '').toString().toLowerCase() !== 'total') : draftRows;

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
    const keys = Object.keys((backendCountryBreakdown.rows as any)?.[0] ?? {});
    const findKey = (alts: string[]) => keys.find((k) => alts.includes(k.toLowerCase()));
    const kTotal = findKey(['total', 'tot']) ?? 'total';

    let sum = 0;
    for (const r of backendCountryBreakdown.rows as any[]) {
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

      const allCodes = c.match(/\\b[A-Z]{2}\\b/g) ?? [];
      for (const code of allCodes) {
        const lc = code.toLowerCase();
        if (!m.has(lc)) m.set(lc, c);
      }

      const pmMatch = c.match(/\\(([A-Z]{2}[A-Z0-9-]+)\\)?/);
      if (pmMatch) {
        const pmCode = pmMatch[1].toUpperCase();
        m.set(pmCode.toLowerCase(), c);
        const normalized = pmCode.replace(/^(OL|PM)(?!-)/, '$1-');
        if (normalized !== pmCode) m.set(normalized.toLowerCase(), c);
      }
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
            <h3 className='text-lg font-bold text-gray-900'>Purchase Order</h3>
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
                            setSalesOrderDetailDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: v } : r)),
                            );
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
                          onChange={(v) => {
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>DEBUG LOG</div>
          <div className='flex items-center gap-2'>
            <Button
              type='button'
              variant='primary'
              disabled={multiFileLogs.length === 0}
              onClick={() => {
                const blob = new Blob([multiFileLogs.join('\n')], { type: 'text/plain;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `presales_purchase_order_debug_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.log`;
                document.body.appendChild(a);
                a.click();
                a.remove();
                URL.revokeObjectURL(url);
              }}
            >
              Download Log
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {multiFileLogs.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No logs.</div>
          ) : (
            <pre className='text-xs whitespace-pre-wrap break-words bg-gray-50 border border-gray-200 rounded-lg p-3 max-h-[40vh] overflow-auto'>
              {multiFileLogs.join('\n')}
            </pre>
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

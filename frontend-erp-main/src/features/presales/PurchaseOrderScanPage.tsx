import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, Loader2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { salesOrderPrototypeApi } from '../salesOrderPrototype/api';
import { ocrNewApi } from '../ocrnew/api';
import type { OcrNewDocumentAnalysisResponseData } from '../ocrnew/types';

const pickFirstNonBlank = (vals: Array<unknown>) => {
  for (const v of vals) {
    const t = (v ?? '').toString().trim();
    if (t.length > 0) return t;
  }
  return '';
};

const hydratePoHeaderDraftFromFormFields = (ff: Record<string, any>) => {
  const next: Record<string, string> = {};
  next['Order No'] = pickFirstNonBlank([ff['Order No'], ff['SO Number'], ff['SO'], ff['Purchase Order No']]);
  next['PT Prod No'] = pickFirstNonBlank([ff['PT Prod No'], ff['PT Prod No:'], ff['PT Product No']]);
  next['Order Date'] = pickFirstNonBlank([ff['Date of Order'], ff['Order Date'], ff['Date (ISO)'], ff['Date']]);
  next['Supplier Code'] = pickFirstNonBlank([ff['Supplier Code']]);
  next['Option No'] = pickFirstNonBlank([ff['Option No'], ff['Option']]);
  next['Development No'] = pickFirstNonBlank([ff['Development No']]);
  next['Product No'] = pickFirstNonBlank([ff['Product No'], ff['Article / Product No'], ff['Article No']]);
  next['Product Name'] = pickFirstNonBlank([ff['Product Name']]);
  next['Product Desc'] = pickFirstNonBlank([ff['Product Description'], ff['Product Desc']]);
  next['Season'] = pickFirstNonBlank([ff['Season']]);
  next['Customer Group'] = pickFirstNonBlank([ff['Customs Customer Group'], ff['Customer Group']]);
  next['Type of Construct'] = pickFirstNonBlank([ff['Type of Construction'], ff['Type of Construct']]);

  next['Country of Production'] = pickFirstNonBlank([ff['Country of Production']]);
  next['Country of Bakery'] = pickFirstNonBlank([ff['Country of Delivery'], ff['Country of Bakery']]);
  next['Country of Origin'] = pickFirstNonBlank([ff['Country of Origin']]);
  next['Term of Payment'] = pickFirstNonBlank([ff['Terms of Payment'], ff['Term of Payment']]);
  next['No of Pieces'] = pickFirstNonBlank([ff['No of Pieces'], ff['No Pieces']]);
  next['Sales Models'] = pickFirstNonBlank([ff['Sales Mode'], ff['Sales Models']]);
  next['Terms of Delivery'] = pickFirstNonBlank([ff['Terms of Delivery']]);
  return next;
};

export function PurchaseOrderScanPage() {
  const navigate = useNavigate();
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [activeFileIndex, setActiveFileIndex] = useState<number>(0);
  const [results, setResults] = useState<Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>>([]);
  const [data, setData] = useState<OcrNewDocumentAnalysisResponseData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [successOpen, setSuccessOpen] = useState(false);
  const [successMessage, setSuccessMessage] = useState('Draft updated.');
  const [lastSavedId, setLastSavedId] = useState<number | null>(null);

  const pageCount = Math.max(1, Number(data?.pageCount ?? 1));
  const [activePage, setActivePage] = useState<number>(1);

  const [timeOfDeliveryRows, setTimeOfDeliveryRows] = useState<Array<Record<string, string>>>([]);
  const [quantityPerArticleRows, setQuantityPerArticleRows] = useState<Array<Record<string, string>>>([]);
  const [invoiceAvgPriceRows, setInvoiceAvgPriceRows] = useState<Array<Record<string, string>>>([]);

  const [termsOfDeliveryByPageDraft, setTermsOfDeliveryByPageDraft] = useState<Record<number, string>>({});

  const [salesSampleTermsByPageDraft, setSalesSampleTermsByPageDraft] = useState<Record<number, string>>({});

  const [salesSampleTimeOfDeliveryByPageDraft, setSalesSampleTimeOfDeliveryByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleDestinationStudioAddressByPageDraft, setSalesSampleDestinationStudioAddressByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleArticleRows, setSalesSampleArticleRows] = useState<Array<Record<string, string>>>([]);

  useEffect(() => {
    setActivePage((p) => Math.min(Math.max(1, p), pageCount));
  }, [pageCount]);

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
  void setSection2cTotalDraftRows;
  const [_section2cTotalDirty, _setSection2cTotalDirty] = useState(false);
  void _section2cTotalDirty;
  void _setSection2cTotalDirty;

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
    setSalesOrderHeaderDraft(hydratePoHeaderDraftFromFormFields(ff));

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

    // Purchase Order specific tables
    setTimeOfDeliveryRows(d?.purchaseOrderTimeOfDelivery ?? []);
    setQuantityPerArticleRows(d?.purchaseOrderQuantityPerArticle ?? []);
    setInvoiceAvgPriceRows(d?.purchaseOrderInvoiceAvgPrice ?? []);

    const nextTerms: Record<number, string> = {};
    for (const r of d?.purchaseOrderTermsOfDelivery ?? []) {
      const p = Number((r?.page ?? '').toString().trim());
      if (!Number.isFinite(p) || p <= 0) continue;
      const v = (r?.termsOfDelivery ?? '').toString();
      if (v.trim().length > 0) nextTerms[p] = v;
    }
    setTermsOfDeliveryByPageDraft(nextTerms);

    const nextSalesSampleTerms: Record<number, string> = {};
    for (const r of d?.salesSampleTermsByPage ?? []) {
      const p = Number((r?.page ?? '').toString().trim());
      if (!Number.isFinite(p) || p <= 0) continue;
      const v = (r?.salesSampleTerms ?? '').toString();
      if (v.trim().length > 0) nextSalesSampleTerms[p] = v;
    }
    setSalesSampleTermsByPageDraft(nextSalesSampleTerms);

    const nextSalesSampleTod: Record<number, string> = {};
    for (const r of d?.salesSampleTimeOfDeliveryByPage ?? []) {
      const p = Number((r?.page ?? '').toString().trim());
      if (!Number.isFinite(p) || p <= 0) continue;
      const v = (r?.timeOfDelivery ?? '').toString();
      if (v.trim().length > 0) nextSalesSampleTod[p] = v;
    }
    setSalesSampleTimeOfDeliveryByPageDraft(nextSalesSampleTod);

    const nextSalesSampleDest: Record<number, string> = {};
    for (const r of d?.salesSampleDestinationStudioAddressByPage ?? []) {
      const p = Number((r?.page ?? '').toString().trim());
      if (!Number.isFinite(p) || p <= 0) continue;
      const v = (r?.destinationStudioAddress ?? '').toString();
      if (v.trim().length > 0) nextSalesSampleDest[p] = v;
    }
    setSalesSampleDestinationStudioAddressByPageDraft(nextSalesSampleDest);

    setSalesSampleArticleRows(d?.salesSampleArticlesByPage ?? []);
  };

  const termsOfDeliveryForActivePage = useMemo(() => {
    return (termsOfDeliveryByPageDraft?.[activePage] ?? '').toString();
  }, [activePage, termsOfDeliveryByPageDraft]);

  const salesSampleTermsForActivePage = useMemo(() => {
    const direct = (salesSampleTermsByPageDraft?.[activePage] ?? '').toString();
    if (direct.trim().length > 0) return direct;
    for (const p of Object.keys(salesSampleTermsByPageDraft ?? {})) {
      const v = (salesSampleTermsByPageDraft as any)?.[p];
      const t = (v ?? '').toString();
      if (t.trim().length > 0) return t;
    }
    return '';
  }, [activePage, salesSampleTermsByPageDraft]);

  const salesSampleTimeOfDeliveryForActivePage = useMemo(() => {
    const direct = (salesSampleTimeOfDeliveryByPageDraft?.[activePage] ?? '').toString();
    if (direct.trim().length > 0) return direct;

    const pages = Object.keys(salesSampleTimeOfDeliveryByPageDraft ?? {})
      .map((p) => Number(p))
      .filter((p) => Number.isFinite(p) && p > 0)
      .sort((a, b) => b - a);
    for (const p of pages) {
      const v = (salesSampleTimeOfDeliveryByPageDraft as any)?.[p];
      const t = (v ?? '').toString();
      if (t.trim().length > 0) return t;
    }
    return '';
  }, [activePage, salesSampleTimeOfDeliveryByPageDraft]);

  const salesSampleDestinationStudioAddressForActivePage = useMemo(() => {
    const direct = (salesSampleDestinationStudioAddressByPageDraft?.[activePage] ?? '').toString();
    if (direct.trim().length > 0) return direct;

    const pages = Object.keys(salesSampleDestinationStudioAddressByPageDraft ?? {})
      .map((p) => Number(p))
      .filter((p) => Number.isFinite(p) && p > 0)
      .sort((a, b) => b - a);
    for (const p of pages) {
      const v = (salesSampleDestinationStudioAddressByPageDraft as any)?.[p];
      const t = (v ?? '').toString();
      if (t.trim().length > 0) return t;
    }
    return '';
  }, [activePage, salesSampleDestinationStudioAddressByPageDraft]);

  const salesSampleArticlesRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    const direct = (salesSampleArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === p);
    if (direct.length > 0) return direct;

    let maxPage = 0;
    for (const r of salesSampleArticleRows ?? []) {
      const rp = Number((r?.page ?? '').toString().trim());
      if (Number.isFinite(rp) && rp > maxPage) maxPage = rp;
    }
    if (maxPage <= 0) return [];
    return (salesSampleArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === String(maxPage));
  }, [activePage, salesSampleArticleRows]);

  const quantityPerArticleRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    return (quantityPerArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === p);
  }, [activePage, quantityPerArticleRows]);

  const invoiceAvgPriceRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    return (invoiceAvgPriceRows ?? []).filter((r) => {
      const rp = (r?.page ?? '1').toString();
      if (rp === p) return true;

      if (p === '1') {
        const c = (r?.country ?? '').toString();
        if (c.includes(',')) return true;
      }

      return false;
    });
  }, [activePage, invoiceAvgPriceRows]);

  const termsOfDeliveryForPage1 = useMemo(() => {
    return (termsOfDeliveryByPageDraft?.[1] ?? '').toString();
  }, [termsOfDeliveryByPageDraft]);

  const quantityPerArticleRowsForPage1 = useMemo(() => {
    return (quantityPerArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === '1');
  }, [quantityPerArticleRows]);

  const invoiceAvgPriceRowsForPage1 = useMemo(() => {
    return (invoiceAvgPriceRows ?? []).filter((r) => (r?.page ?? '1').toString() === '1');
  }, [invoiceAvgPriceRows]);

  const hydrateDraftsFromResultsMerged = (out: Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>) => {
    const mergedFf: Record<string, any> = {};
    for (const r of out) {
      const ff = r?.data?.formFields ?? {};
      for (const [k, v] of Object.entries(ff)) {
        const t = (v ?? '').toString().trim();
        if (t.length > 0 && (mergedFf[k] ?? '').toString().trim().length === 0) {
          mergedFf[k] = t;
        }
      }
    }
    setSalesOrderHeaderDraft(hydratePoHeaderDraftFromFormFields(mergedFf));

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
        if (resData) {
          out.push({ fileName: f.name, data: resData });
        }
      } catch (e) {
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
    setActivePage(1);

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
    setActivePage(1);

    window.scrollTo({ top: 0, behavior: 'smooth' });
  },
});

const isPending = analyzeMutation.isPending;

const saveDraftMutation = useMutation({
  mutationFn: async () => {
    if (!data) throw new Error('No data.');
    const payload = {
      documentType: 'purchase-order',
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

  const fileNameValue = results[activeFileIndex]?.fileName ?? selectedFiles[activeFileIndex]?.name ?? '';

  const headerFormFields = useMemo(() => {
    return [
      { key: 'Order No', label: 'Order No' },
      { key: 'PT Prod No', label: 'PT Prod No' },
      { key: 'Order Date', label: 'Order Date' },
      { key: 'Supplier Code', label: 'Supplier Code' },
      { key: 'Option No', label: 'Option No' },
      { key: 'Development No', label: 'Development No' },
      { key: 'Product No', label: 'Product No' },
      { key: 'Product Name', label: 'Product Name' },
      { key: 'Product Desc', label: 'Product Desc' },
      { key: 'Season', label: 'Season' },
      { key: 'Customer Group', label: 'Customer Group' },
      { key: 'Type of Construct', label: 'Type of Construct' },
    ];
  }, []);

  const poMetaFields = useMemo(() => {
    return [
      { key: 'Country of Production', label: 'Country of Production' },
      { key: 'Country of Bakery', label: 'Country of Bakery' },
      { key: 'Country of Origin', label: 'Country of Origin' },
      { key: 'Term of Payment', label: 'Term of Payment' },
      { key: 'No of Pieces', label: 'No of Pieces' },
      { key: 'Sales Models', label: 'Sales Models' },
    ];
  }, []);

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

        <div className='mt-6 grid grid-cols-1 md:grid-cols-12 gap-4 items-end'>
          <div className='md:col-span-4'>
            <label className='block text-sm font-medium text-gray-700 mb-2'>File Upload</label>
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
          <div className='md:col-span-6'>
            <label className='block text-sm font-medium text-gray-700 mb-2'>File Name</label>
            <Input value={fileNameValue} readOnly />
          </div>
          <div className='md:col-span-2 flex justify-start md:justify-end'>
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
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-sm font-semibold text-gray-900'>Sales Order Header (Draft)</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data || saveDraftMutation.isPending}
            onClick={() => saveDraftMutation.mutate()}
          >
            Save Draft
          </Button>
        </div>
        <div className='p-6 space-y-6'>
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : (
            <>
              <div className='grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-3'>
                {headerFormFields.map((f) => (
                  <div key={f.key} className='grid grid-cols-12 items-center gap-3'>
                    <div className='col-span-4 text-sm text-gray-700'>{f.label}</div>
                    <div className='col-span-8'>
                      <Input
                        value={salesOrderHeaderDraft[f.key] ?? ''}
                        onChange={(e) => {
                          const v = e.target.value;
                          setSalesOrderHeaderDraft((prev) => ({ ...prev, [f.key]: v }));
                        }}
                      />
                    </div>
                  </div>
                ))}
              </div>

              <div className='grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-3'>
                {poMetaFields.map((f) => (
                  <div key={f.key} className='grid grid-cols-12 items-center gap-3'>
                    <div className='col-span-4 text-sm text-gray-700'>{f.label}</div>
                    <div className='col-span-8'>
                      <Input
                        value={salesOrderHeaderDraft[f.key] ?? ''}
                        onChange={(e) => {
                          const v = e.target.value;
                          setSalesOrderHeaderDraft((prev) => ({ ...prev, [f.key]: v }));
                        }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {activePage !== 1 ? (
        <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
          <div className='p-6 space-y-6 bg-gray-50'>
            <div className='bg-white rounded-xl border border-gray-200 p-4'>
              <div className='text-sm font-semibold text-gray-900 mb-3'>Terms of Delivery</div>
              <textarea
                className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                value={termsOfDeliveryForPage1}
                readOnly
              />
            </div>

            <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
              <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                <div className='text-sm font-semibold text-gray-900'>Quantity per Article</div>
                <div className='flex items-center gap-2'>
                  <Button
                    type='button'
                    variant='primary'
                    disabled={!data}
                    onClick={() => {
                      setQuantityPerArticleRows((prev) => [
                        ...(prev ?? []),
                        { articleNo: '', hmColourCode: '', ptArticleNumber: '', colour: '', optionNo: '', cost: '', qtyArticle: '' },
                      ]);
                    }}
                  >
                    Add row
                  </Button>
                </div>
              </div>
              <div className='overflow-auto'>
                <table className='min-w-[900px] w-full'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Article No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>H&M Colour Code</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>PT Article Number</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Colour</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Option No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Cost</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty/Article</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {quantityPerArticleRowsForPage1.length > 0 ? (
                      quantityPerArticleRowsForPage1.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.articleNo ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.hmColourCode ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.ptArticleNumber ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.colour ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.optionNo ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.cost ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.qtyArticle ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Button
                              type='button'
                              variant='danger'
                              disabled={!data}
                              onClick={() => {
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                              }}
                            >
                              Delete
                            </Button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={8} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                          No data
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
              <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                <div className='text-sm font-semibold text-gray-900'>Invoice Average Price</div>
                <div className='flex items-center gap-2'>
                  <Button
                    type='button'
                    variant='primary'
                    disabled={!data}
                    onClick={() => {
                      setInvoiceAvgPriceRows((prev) => [...(prev ?? []), { invoiceAveragePrice: '', country: '' }]);
                    }}
                  >
                    Add row
                  </Button>
                </div>
              </div>
              <div className='overflow-auto'>
                <table className='min-w-full w-full'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Invoice Average Price</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Country</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {invoiceAvgPriceRowsForPage1.length > 0 ? (
                      invoiceAvgPriceRowsForPage1.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.invoiceAveragePrice ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.country ?? ''} onChange={() => {}} disabled />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Button
                              type='button'
                              variant='danger'
                              disabled={!data}
                              onClick={() => {
                                const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                setInvoiceAvgPriceRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                              }}
                            >
                              Delete
                            </Button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={3} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                          No data
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      {activePage === 1 ? (
        <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
          <div className='p-6 space-y-6 bg-gray-50'>
            <div className='bg-white rounded-xl border border-gray-200 p-4'>
              <div className='text-sm font-semibold text-gray-900 mb-3'>Terms of Delivery</div>
              <textarea
                className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                value={termsOfDeliveryForActivePage}
                onChange={(e) => {
                  const v = e.target.value;
                  setTermsOfDeliveryByPageDraft((prev) => ({ ...prev, [activePage]: v }));
                }}
              />
            </div>

            <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
              <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                <div className='text-sm font-semibold text-gray-900'>Time of Delivery</div>
                <div className='flex items-center gap-2'>
                  <Button
                    type='button'
                    variant='primary'
                    disabled={!data}
                    onClick={() => {
                      setTimeOfDeliveryRows((prev) => [...(prev ?? []), { timeOfDelivery: '', planningMarkets: '', quantity: '', percentTotalQty: '' }]);
                    }}
                  >
                    Add row
                  </Button>
                </div>
              </div>
              <div className='overflow-auto'>
                <table className='min-w-[900px] w-full'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Time of Delivery</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Planning Markets</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Quantity</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>% Total Qty</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {timeOfDeliveryRows.length > 0 ? (
                      timeOfDeliveryRows.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.timeOfDelivery ?? ''} onChange={() => {}} />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.planningMarkets ?? ''} onChange={() => {}} />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.quantity ?? ''} onChange={() => {}} />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input value={row.percentTotalQty ?? ''} onChange={() => {}} />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Button
                              type='button'
                              variant='danger'
                              disabled={!data}
                              onClick={() => {
                                setTimeOfDeliveryRows((prev) => (prev ?? []).filter((_, i) => i !== rIdx));
                              }}
                            >
                              Delete
                            </Button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={5} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                          No data
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
              <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                <div className='text-sm font-semibold text-gray-900'>Quantity per Article</div>
                <div className='flex items-center gap-2'>
                  <Button
                    type='button'
                    variant='primary'
                    disabled={!data}
                    onClick={() => {
                      setQuantityPerArticleRows((prev) => [
                        ...(prev ?? []),
                        {
                          page: String(activePage),
                          articleNo: '',
                          hmColourCode: '',
                          ptArticleNumber: '',
                          colour: '',
                          optionNo: '',
                          cost: '',
                          qtyArticle: '',
                        },
                      ]);
                    }}
                  >
                    Add row
                  </Button>
                </div>
              </div>
              <div className='overflow-auto'>
                <table className='min-w-[900px] w-full'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Article No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>H&M Colour Code</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>PT Article Number</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Colour</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Option No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Cost</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty/Article</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {quantityPerArticleRowsForActivePage.length > 0 ? (
                      quantityPerArticleRowsForActivePage.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.articleNo ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, articleNo: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.hmColourCode ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, hmColourCode: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.ptArticleNumber ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, ptArticleNumber: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.colour ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, colour: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.optionNo ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, optionNo: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.cost ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, cost: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.qtyArticle ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, qtyArticle: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Button
                              type='button'
                              variant='danger'
                              disabled={!data}
                              onClick={() => {
                                const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                setQuantityPerArticleRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                              }}
                            >
                              Delete
                            </Button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={8} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                          No data
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>

            <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
              <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                <div className='text-sm font-semibold text-gray-900'>Invoice Average Price</div>
                <div className='flex items-center gap-2'>
                  <Button
                    type='button'
                    variant='primary'
                    disabled={!data}
                    onClick={() => {
                      setInvoiceAvgPriceRows((prev) => [
                        ...(prev ?? []),
                        {
                          page: String(activePage),
                          invoiceAveragePrice: '',
                          country: '',
                        },
                      ]);
                    }}
                  >
                    Add row
                  </Button>
                </div>
              </div>
              <div className='overflow-auto'>
                <table className='min-w-full w-full'>
                  <thead className='bg-gray-50'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Invoice Average Price</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Country</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    {invoiceAvgPriceRowsForActivePage.length > 0 ? (
                      invoiceAvgPriceRowsForActivePage.map((row, rIdx) => (
                        <tr key={rIdx}>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.invoiceAveragePrice ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setInvoiceAvgPriceRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, invoiceAveragePrice: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Input
                              value={row.country ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                setInvoiceAvgPriceRows((prev) =>
                                  (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, country: v } : x))
                                );
                              }}
                            />
                          </td>
                          <td className='px-3 py-2 border-b border-gray-100'>
                            <Button
                              type='button'
                              variant='danger'
                              disabled={!data}
                              onClick={() => {
                                const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                setInvoiceAvgPriceRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                              }}
                            >
                              Delete
                            </Button>
                          </td>
                        </tr>
                      ))
                    ) : (
                      <tr>
                        <td colSpan={3} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                          No data
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      ) : null}

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-3 border-b border-gray-200 flex gap-2 overflow-auto'>
          {Array.from({ length: pageCount }).map((_, idx) => {
            const p = idx + 1;
            const label = `Page-${p}`;
            return (
            <button
              key={label}
              type='button'
              className={
                p === activePage
                  ? 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600 text-white'
                  : 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-gray-100 text-gray-700 hover:bg-gray-200'
              }
              onClick={() => setActivePage(p)}
            >
              {label}
            </button>
            );
          })}
        </div>
        <div className='p-6 space-y-6 bg-gray-50'>
          {activePage === 1 ? null : (
            <>
              <div className='bg-white rounded-xl border border-gray-200 p-4'>
                <div className='text-sm font-semibold text-gray-900 mb-3'>Terms of Delivery</div>
                <textarea
                  className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                  value={termsOfDeliveryForActivePage}
                  onChange={(e) => {
                    const v = e.target.value;
                    setTermsOfDeliveryByPageDraft((prev) => ({ ...prev, [activePage]: v }));
                  }}
                />
              </div>

              <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
                <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                  <div className='text-sm font-semibold text-gray-900'>Quantity per Article</div>
                  <div className='flex items-center gap-2'>
                    <Button
                      type='button'
                      variant='primary'
                      disabled={!data}
                      onClick={() => {
                        setQuantityPerArticleRows((prev) => [
                          ...(prev ?? []),
                          {
                            page: String(activePage),
                            articleNo: '',
                            hmColourCode: '',
                            ptArticleNumber: '',
                            colour: '',
                            optionNo: '',
                            cost: '',
                            qtyArticle: '',
                          },
                        ]);
                      }}
                    >
                      Add row
                    </Button>
                  </div>
                </div>
                <div className='overflow-auto'>
                  <table className='min-w-[900px] w-full'>
                    <thead className='bg-gray-50'>
                      <tr>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Article No</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>H&M Colour Code</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>PT Article Number</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Colour</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Option No</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Cost</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty/Article</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {quantityPerArticleRowsForActivePage.length > 0 ? (
                        quantityPerArticleRowsForActivePage.map((row, rIdx) => (
                          <tr key={rIdx}>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.articleNo ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, articleNo: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.hmColourCode ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, hmColourCode: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.ptArticleNumber ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, ptArticleNumber: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.colour ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, colour: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.optionNo ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, optionNo: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.cost ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, cost: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.qtyArticle ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, qtyArticle: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Button
                                type='button'
                                variant='danger'
                                disabled={!data}
                                onClick={() => {
                                  const originalIdx = (quantityPerArticleRows ?? []).findIndex((x) => x === row);
                                  const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                  setQuantityPerArticleRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                                }}
                              >
                                Delete
                              </Button>
                            </td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={8} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                            No data
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>

              <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
                <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
                  <div className='text-sm font-semibold text-gray-900'>Invoice Average Price</div>
                  <div className='flex items-center gap-2'>
                    <Button
                      type='button'
                      variant='primary'
                      disabled={!data}
                      onClick={() => {
                        setInvoiceAvgPriceRows((prev) => [
                          ...(prev ?? []),
                          {
                            page: String(activePage),
                            invoiceAveragePrice: '',
                            country: '',
                          },
                        ]);
                      }}
                    >
                      Add row
                    </Button>
                  </div>
                </div>
                <div className='overflow-auto'>
                  <table className='min-w-full w-full'>
                    <thead className='bg-gray-50'>
                      <tr>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Invoice Average Price</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Country</th>
                        <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                      </tr>
                    </thead>
                    <tbody>
                      {invoiceAvgPriceRowsForActivePage.length > 0 ? (
                        invoiceAvgPriceRowsForActivePage.map((row, rIdx) => (
                          <tr key={rIdx}>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.invoiceAveragePrice ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setInvoiceAvgPriceRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, invoiceAveragePrice: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Input
                                value={row.country ?? ''}
                                onChange={(e) => {
                                  const v = e.target.value;
                                  const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                  const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                                  setInvoiceAvgPriceRows((prev) =>
                                    (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, country: v } : x))
                                  );
                                }}
                              />
                            </td>
                            <td className='px-3 py-2 border-b border-gray-100'>
                              <Button
                                type='button'
                                variant='danger'
                                disabled={!data}
                                onClick={() => {
                                  const originalIdx = (invoiceAvgPriceRows ?? []).findIndex((x) => x === row);
                                  const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                                  setInvoiceAvgPriceRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                                }}
                              >
                                Delete
                              </Button>
                            </td>
                          </tr>
                        ))
                      ) : (
                        <tr>
                          <td colSpan={3} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                            No data
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </div>
            </>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 text-sm font-semibold text-gray-900'>Sales Sample</div>
        <div className='p-6 space-y-4'>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Sales Sample Terms</div>
            <textarea
              className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
              value={salesSampleTermsForActivePage}
              onChange={(e) => {
                const v = e.target.value;
                setSalesSampleTermsByPageDraft((prev) => ({ ...prev, [activePage]: v }));
              }}
            />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Destination Studio Address</div>
            <textarea
              className='w-full min-h-[84px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
              value={salesSampleDestinationStudioAddressForActivePage}
              onChange={(e) => {
                const v = e.target.value;
                setSalesSampleDestinationStudioAddressByPageDraft((prev) => ({ ...prev, [activePage]: v }));
              }}
            />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Time Of Delivery</div>
            <Input value={salesSampleTimeOfDeliveryForActivePage} onChange={() => {}} />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 overflow-hidden'>
            <div className='px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
              <div className='text-sm font-semibold text-gray-900'>Articles</div>
              <div className='flex items-center gap-2'>
                <Button
                  type='button'
                  variant='primary'
                  disabled={!data}
                  onClick={() => {
                    setSalesSampleArticleRows((prev) => [
                      ...(prev ?? []),
                      {
                        page: String(activePage),
                        articleNo: '',
                        hmColourCode: '',
                        ptArticleNumber: '',
                        colour: '',
                        size: '',
                        qty: '',
                        tod: '',
                        destinationStudio: '',
                      },
                    ]);
                  }}
                >
                  Add row
                </Button>
              </div>
            </div>
            <div className='overflow-auto'>
              <table className='min-w-[900px] w-full'>
                <thead className='bg-white'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Article No</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>H&M Colour Code</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>PT Article Number</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Colour</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Size</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>TOD</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Destination Studio</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {salesSampleArticlesRowsForActivePage.length > 0 ? (
                    salesSampleArticlesRowsForActivePage.map((row, rIdx) => (
                      <tr key={rIdx}>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.articleNo ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, articleNo: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.hmColourCode ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, hmColourCode: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.ptArticleNumber ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, ptArticleNumber: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.colour ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, colour: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.size ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, size: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.qty ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, qty: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.tod ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, tod: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Input
                            value={row.destinationStudio ?? ''}
                            onChange={(e) => {
                              const v = e.target.value;
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToUpdate = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) =>
                                (prev ?? []).map((x, i) => (i === idxToUpdate ? { ...x, destinationStudio: v } : x))
                              );
                            }}
                          />
                        </td>
                        <td className='px-3 py-2 border-b border-gray-100'>
                          <Button
                            type='button'
                            variant='danger'
                            disabled={!data}
                            onClick={() => {
                              const originalIdx = (salesSampleArticleRows ?? []).findIndex((x) => x === row);
                              const idxToRemove = originalIdx >= 0 ? originalIdx : rIdx;
                              setSalesSampleArticleRows((prev) => (prev ?? []).filter((_, i) => i !== idxToRemove));
                            }}
                          >
                            Delete
                          </Button>
                        </td>
                      </tr>
                    ))
                  ) : (
                    <tr>
                      <td colSpan={9} className='px-3 py-2 text-center text-sm text-gray-500 italic'>
                        No data
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
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

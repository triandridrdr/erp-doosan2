import { useMutation } from '@tanstack/react-query';
import { AlertCircle, CheckCircle2, ChevronLeft, ChevronRight, Loader2, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import { ocrNewApi } from '../ocrnew/api';
import type { OcrNewDocumentAnalysisResponseData } from '../ocrnew/types';
import { salesOrderPrototypeApi } from '../salesOrderPrototype/api';

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

const PURCHASE_ORDER_HEADER_FIELDS = [
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
] as const;

const PURCHASE_ORDER_META_FIELDS = [
  { key: 'Country of Production', label: 'Country of Production' },
  { key: 'Country of Bakery', label: 'Country of Bakery' },
  { key: 'Country of Origin', label: 'Country of Origin' },
  { key: 'Term of Payment', label: 'Term of Payment' },
  { key: 'No of Pieces', label: 'No of Pieces' },
  { key: 'Sales Models', label: 'Sales Models' },
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
    Array<{ position: string; placement: string; type: string; description: string; composition: string; materialSupplier: string }>
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

  const yarnSourceNoDetails = useMemo(() => {
    const v = (bomYarnSourceTableRows?.[1]?.[0] ?? '').toString().trim().toLowerCase();
    return v.length === 0 || v === 'no yarn details found';
  }, [bomYarnSourceTableRows]);

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
    const next: Record<string, string> = hydratePoHeaderDraftFromFormFields(ff);
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      if ((next[f] ?? '').toString().trim().length > 0) continue;
      next[f] = (ff[f] ?? '').toString();
    }
    setSalesOrderHeaderDraft(next);

    setActivePage(1);
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

    const next: Record<string, string> = hydratePoHeaderDraftFromFormFields(mergedFf);
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      if ((next[f] ?? '').toString().trim().length > 0) continue;
      next[f] = (mergedFf[f] ?? '').toString();
    }
    setSalesOrderHeaderDraft(next);

    setActivePage(1);
    const mergedTimeOfDelivery: any[] = [];
    const mergedQuantityPerArticle: any[] = [];
    const mergedInvoiceAvgPrice: any[] = [];
    const mergedSalesSampleArticles: any[] = [];
    const nextTerms: Record<number, string> = {};
    const nextSalesSampleTerms: Record<number, string> = {};
    const nextSalesSampleTod: Record<number, string> = {};
    const nextSalesSampleDest: Record<number, string> = {};

    for (const r of out) {
      const d = r?.data;
      if (!d) continue;

      mergedTimeOfDelivery.push(...(d?.purchaseOrderTimeOfDelivery ?? []));
      mergedQuantityPerArticle.push(...(d?.purchaseOrderQuantityPerArticle ?? []));
      mergedInvoiceAvgPrice.push(...(d?.purchaseOrderInvoiceAvgPrice ?? []));
      mergedSalesSampleArticles.push(...(d?.salesSampleArticlesByPage ?? []));

      for (const tod of d?.purchaseOrderTermsOfDelivery ?? []) {
        const p = Number((tod?.page ?? '').toString().trim());
        if (!Number.isFinite(p) || p <= 0) continue;
        const v = (tod?.termsOfDelivery ?? '').toString();
        if (v.trim().length > 0 && (nextTerms[p] ?? '').toString().trim().length === 0) nextTerms[p] = v;
      }

      for (const ss of d?.salesSampleTermsByPage ?? []) {
        const p = Number((ss?.page ?? '').toString().trim());
        if (!Number.isFinite(p) || p <= 0) continue;
        const v = (ss?.salesSampleTerms ?? '').toString();
        if (v.trim().length > 0 && (nextSalesSampleTerms[p] ?? '').toString().trim().length === 0) nextSalesSampleTerms[p] = v;
      }

      for (const ss of d?.salesSampleTimeOfDeliveryByPage ?? []) {
        const p = Number((ss?.page ?? '').toString().trim());
        if (!Number.isFinite(p) || p <= 0) continue;
        const v = (ss?.timeOfDelivery ?? '').toString();
        if (v.trim().length > 0 && (nextSalesSampleTod[p] ?? '').toString().trim().length === 0) nextSalesSampleTod[p] = v;
      }

      for (const ss of d?.salesSampleDestinationStudioAddressByPage ?? []) {
        const p = Number((ss?.page ?? '').toString().trim());
        if (!Number.isFinite(p) || p <= 0) continue;
        const v = (ss?.destinationStudioAddress ?? '').toString();
        if (v.trim().length > 0 && (nextSalesSampleDest[p] ?? '').toString().trim().length === 0) nextSalesSampleDest[p] = v;
      }
    }

    setTimeOfDeliveryRows(mergedTimeOfDelivery);
    setQuantityPerArticleRows(mergedQuantityPerArticle);
    setInvoiceAvgPriceRows(mergedInvoiceAvgPrice);

    setTermsOfDeliveryByPageDraft(nextTerms);

    setSalesSampleTermsByPageDraft(nextSalesSampleTerms);

    setSalesSampleTimeOfDeliveryByPageDraft(nextSalesSampleTod);

    setSalesSampleDestinationStudioAddressByPageDraft(nextSalesSampleDest);

    setSalesSampleArticleRows(mergedSalesSampleArticles);

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
      setActivePage(1);
      setSalesOrderHeaderDraft({});
      setBomDraftRows([]);
      setBomProdUnitsRows([]);
      setBomYarnSourceTableRows([]);
      setProductArticleTableRows([]);
      setMiscellaneousTableRows([]);
      setSalesOrderDetailDraftRows([]);
      setCountryBreakdownDraftRows([]);
      setSection2cDraftRows([]);
      setTimeOfDeliveryRows([]);
      setQuantityPerArticleRows([]);
      setInvoiceAvgPriceRows([]);
      setTermsOfDeliveryByPageDraft({});
      setSalesSampleTermsByPageDraft({});
      setSalesSampleTimeOfDeliveryByPageDraft({});
      setSalesSampleDestinationStudioAddressByPageDraft({});
      setSalesSampleArticleRows([]);
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

  const section2NonTotalEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => (row?.type ?? '').toString().trim().toLowerCase() !== 'total');
  }, [salesOrderDetailDraftRows]);

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

  // --- Country pagination (Section 2) ---
  const [assortmentCountryPage, setAssortmentCountryPage] = useState(0);
  const [solidCountryPage, setSolidCountryPage] = useState(0);

  const assortmentCountries = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const { row } of section2AssortmentEntries) {
      const c = (row.countryOfDestination ?? '').trim();
      if (c && !seen.has(c)) {
        seen.add(c);
        list.push(c);
      }
    }
    return list;
  }, [section2AssortmentEntries]);

  const solidCountries = useMemo(() => {
    const seen = new Set<string>();
    const list: string[] = [];
    for (const { row } of section2SolidEntries) {
      const c = (row.countryOfDestination ?? '').trim();
      if (c && !seen.has(c)) {
        seen.add(c);
        list.push(c);
      }
    }
    return list;
  }, [section2SolidEntries]);

  useEffect(() => {
    setAssortmentCountryPage((p) => Math.min(Math.max(0, p), Math.max(0, assortmentCountries.length - 1)));
  }, [assortmentCountries.length]);

  useEffect(() => {
    setSolidCountryPage((p) => Math.min(Math.max(0, p), Math.max(0, solidCountries.length - 1)));
  }, [solidCountries.length]);

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

  const section2TotalByCountryRows = useMemo(() => {
    const totals = (salesOrderDetailDraftRows ?? []).filter((r) => (r?.type ?? '').toString().toLowerCase() === 'total');
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

  const headerFormFields = useMemo(() => {
    return PURCHASE_ORDER_HEADER_FIELDS;
  }, []);

  const poMetaFields = useMemo(() => {
    return PURCHASE_ORDER_META_FIELDS;
  }, []);

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
      .map((x) => Number(x))
      .filter((x) => Number.isFinite(x))
      .sort((a, b) => a - b);
    for (const p of pages) {
      const t = (salesSampleTimeOfDeliveryByPageDraft?.[p] ?? '').toString();
      if (t.trim().length > 0) return t;
    }
    return '';
  }, [activePage, salesSampleTimeOfDeliveryByPageDraft]);

  const salesSampleDestinationStudioAddressForActivePage = useMemo(() => {
    const direct = (salesSampleDestinationStudioAddressByPageDraft?.[activePage] ?? '').toString();
    if (direct.trim().length > 0) return direct;

    const pages = Object.keys(salesSampleDestinationStudioAddressByPageDraft ?? {})
      .map((x) => Number(x))
      .filter((x) => Number.isFinite(x))
      .sort((a, b) => a - b);
    for (const p of pages) {
      const t = (salesSampleDestinationStudioAddressByPageDraft?.[p] ?? '').toString();
      if (t.trim().length > 0) return t;
    }
    return '';
  }, [activePage, salesSampleDestinationStudioAddressByPageDraft]);

  const salesSampleArticlesRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    const direct = (salesSampleArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === p);
    if (direct.length > 0) return direct;

    const pages = (salesSampleArticleRows ?? []).map((r) => Number((r?.page ?? '1').toString())).filter((x) => Number.isFinite(x));
    const maxPage = pages.length > 0 ? Math.max(...pages) : 0;
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

      if (p !== '1') return false;
      return rp.trim() === '';
    });
  }, [activePage, invoiceAvgPriceRows]);

  const termsOfDeliveryForPage1 = useMemo(() => {
    return (termsOfDeliveryByPageDraft?.[1] ?? '').toString();
  }, [termsOfDeliveryByPageDraft]);

  const quantityPerArticleRowsForPage1 = useMemo(() => {
    return (quantityPerArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === '1');
  }, [quantityPerArticleRows]);

  const invoiceAvgPriceRowsForPage1 = useMemo(() => {
    return (invoiceAvgPriceRows ?? []).filter((r) => {
      const rp = (r?.page ?? '1').toString();
      return rp === '1' || rp.trim() === '';
    });
  }, [invoiceAvgPriceRows]);

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
                setActivePage(1);
                setSalesOrderHeaderDraft({});
                setBomDraftRows([]);
                setBomProdUnitsRows([]);
                setBomYarnSourceTableRows([]);
                setProductArticleTableRows([]);
                setMiscellaneousTableRows([]);
                setSalesOrderDetailDraftRows([]);
                setCountryBreakdownDraftRows([]);
                setTimeOfDeliveryRows([]);
                setQuantityPerArticleRows([]);
                setInvoiceAvgPriceRows([]);
                setTermsOfDeliveryByPageDraft({});
                setSalesSampleTermsByPageDraft({});
                setSalesSampleTimeOfDeliveryByPageDraft({});
                setSalesSampleDestinationStudioAddressByPageDraft({});
                setSalesSampleArticleRows([]);
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

              {activePage !== 1 ? (
                <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
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
                <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
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
                              setTimeOfDeliveryRows((prev) => [
                                ...(prev ?? []),
                                { timeOfDelivery: '', planningMarkets: '', quantity: '', percentTotalQty: '' },
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

              <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
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

              <div className='bg-white rounded-xl border border-gray-200 overflow-hidden'>
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
                <div className='bg-white rounded-xl border border-gray-200 p-6'>
                  <div className='text-sm text-gray-700'>Draft ID: {lastSavedId}</div>
                  <div className='mt-3 flex gap-2'>
                    <Button type='button' onClick={() => navigate(`/sales-order-prototype/${lastSavedId}`)}>
                      Open Draft
                    </Button>
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 2 – SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
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
                      variant='primary'
                      disabled={!data}
                      onClick={() =>
                        setSalesOrderDetailDraftRows((prev) => [
                          ...prev,
                          {
                            countryOfDestination: assortmentActiveCountry || '',
                            type: 'Assortment',
                            color: '',
                            size: '',
                            qty: '',
                            total: '',
                            noOfAsst: '',
                            editable: true,
                          },
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
                                i === assortmentCountryPage ? 'bg-blue-600 text-white shadow-sm' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
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
                        <span className='ml-auto text-xs text-gray-400'>
                          {assortmentCountryPage + 1} / {assortmentCountries.length}
                        </span>
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
                                    setSalesOrderDetailDraftRows((prev) =>
                                      prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: e.target.value } : r))
                                    )
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={row.color}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <SizeAutocompleteInput
                                  value={formatSizeDisplay(row.size)}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={formatIdThousands(row.qty)}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(e.target.value) } : r)))
                                  }
                                  style={{ textAlign: 'left' }}
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={formatIdThousands(row.noOfAsst ?? '')}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: normalizeDigits(e.target.value) } : r)))
                                  }
                                  style={{ textAlign: 'left' }}
                                />
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
                      variant='primary'
                      disabled={!data}
                      onClick={() =>
                        setSalesOrderDetailDraftRows((prev) => [
                          ...prev,
                          {
                            countryOfDestination: solidActiveCountry || '',
                            type: 'Solid',
                            color: '',
                            size: '',
                            qty: '',
                            total: '',
                            noOfAsst: '',
                            editable: true,
                          },
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
                                i === solidCountryPage ? 'bg-blue-600 text-white shadow-sm' : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
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
                        <span className='ml-auto text-xs text-gray-400'>
                          {solidCountryPage + 1} / {solidCountries.length}
                        </span>
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
                                    setSalesOrderDetailDraftRows((prev) =>
                                      prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: e.target.value } : r))
                                    )
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={row.color}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, color: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <SizeAutocompleteInput
                                  value={formatSizeDisplay(row.size)}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, size: e.target.value } : r)))
                                  }
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={formatIdThousands(row.qty)}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(e.target.value) } : r)))
                                  }
                                  style={{ textAlign: 'left' }}
                                />
                              </td>
                              <td className='px-3 py-2 text-sm text-gray-700 align-top'>
                                <Input
                                  value={formatIdThousands(row.noOfAsst ?? '')}
                                  onChange={(e) =>
                                    setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, noOfAsst: normalizeDigits(e.target.value) } : r)))
                                  }
                                  style={{ textAlign: 'left' }}
                                />
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 2B – TOTAL COUNTRY BREAKDOWN</div>
            {backendCountryBreakdown && <div className='text-xs text-gray-400 mt-0.5'>Source: {backendCountryBreakdown.fileName}</div>}
          </div>
          <Button
            type='button'
            variant='primary'
            disabled={!data || !backendCountryBreakdown}
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

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>Bill of Material: Production Units and Processing Capabilities</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data}
            onClick={() => {
              setBomProdUnitsRows((prev) => [
                ...prev,
                { position: '', placement: '', type: '', materialSupplier: '', composition: '', weight: '', productionUnitProcessingCapability: '' },
              ]);
            }}
          >
            Add row
          </Button>
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomProdUnitsRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.position}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, position: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.placement}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, placement: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.type}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, type: v } : r)));
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
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, materialSupplier: v } : r)));
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
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, composition: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.weight}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, weight: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.productionUnitProcessingCapability}
                          rows={2}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomProdUnitsRows((prev) => prev.map((r, i) => (i === idx ? { ...r, productionUnitProcessingCapability: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setBomProdUnitsRows((prev) => prev.filter((_, i) => i !== idx));
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
          <div className='text-xs font-semibold text-gray-500'>Bill of Material: Yarn Source Details</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data || yarnSourceNoDetails || (bomYarnSourceTableRows?.[0] ?? []).length === 0}
            onClick={() => {
              const headerLen = (bomYarnSourceTableRows?.[0] ?? []).length;
              setBomYarnSourceTableRows((prev) => {
                const next = (prev ?? []).map((row) => [...row]);
                next.push(Array.from({ length: headerLen }, () => ''));
                return next;
              });
            }}
          >
            Add row
          </Button>
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomYarnSourceTableRows.slice(1).map((r, ridx) => (
                    <tr key={ridx} className='border-b border-gray-100 last:border-b-0'>
                      {r.map((c, cidx) => (
                        <td key={cidx} className='px-3 py-2 align-top'>
                          {yarnSourceNoDetails ? (
                            <div className='text-sm text-gray-700 whitespace-pre-wrap'>{c ?? ''}</div>
                          ) : String(c ?? '').length > 40 ? (
                            <textarea
                              className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                              value={c ?? ''}
                              rows={2}
                              onChange={(e) => {
                                const v = e.target.value;
                                setBomYarnSourceTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          ) : (
                            <Input
                              value={c ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                setBomYarnSourceTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          )}
                        </td>
                      ))}
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          disabled={yarnSourceNoDetails}
                          onClick={() => {
                            setBomYarnSourceTableRows((prev) => {
                              const next = (prev ?? []).map((row) => [...row]);
                              next.splice(ridx + 1, 1);
                              return next;
                            });
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
          <div className='text-xs font-semibold text-gray-500'>Product Article</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data || (productArticleTableRows?.[0] ?? []).length === 0}
            onClick={() => {
              const headerLen = (productArticleTableRows?.[0] ?? []).length;
              setProductArticleTableRows((prev) => {
                const next = (prev ?? []).map((row) => [...row]);
                next.push(Array.from({ length: headerLen }, () => ''));
                return next;
              });
            }}
          >
            Add row
          </Button>
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
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
                              onChange={(e) => {
                                const v = e.target.value;
                                setProductArticleTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          ) : (
                            <Input
                              value={c ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                setProductArticleTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          )}
                        </td>
                      ))}
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setProductArticleTableRows((prev) => {
                              const next = (prev ?? []).map((row) => [...row]);
                              next.splice(ridx + 1, 1);
                              return next;
                            });
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
          <div className='text-xs font-semibold text-gray-500'>Miscellaneous</div>
          <Button
            type='button'
            variant='primary'
            disabled={!data || (miscellaneousTableRows?.[0] ?? []).length === 0}
            onClick={() => {
              const headerLen = (miscellaneousTableRows?.[0] ?? []).length;
              setMiscellaneousTableRows((prev) => {
                const next = (prev ?? []).map((row) => [...row]);
                next.push(Array.from({ length: headerLen }, () => ''));
                return next;
              });
            }}
          >
            Add row
          </Button>
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
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
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
                              onChange={(e) => {
                                const v = e.target.value;
                                setMiscellaneousTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          ) : (
                            <Input
                              value={c ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                setMiscellaneousTableRows((prev) => {
                                  const next = (prev ?? []).map((row) => [...row]);
                                  const rr = ridx + 1;
                                  if (!next[rr]) next[rr] = [];
                                  next[rr][cidx] = v;
                                  return next;
                                });
                              }}
                            />
                          )}
                        </td>
                      ))}
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => {
                            setMiscellaneousTableRows((prev) => {
                              const next = (prev ?? []).map((row) => [...row]);
                              next.splice(ridx + 1, 1);
                              return next;
                            });
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

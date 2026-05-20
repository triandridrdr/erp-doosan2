import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { CheckCircle2 } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import {
  collectSizeLabelsFromRows,
  extractSizeKeysFromRow,
  useEnsureMasterSizesBatch,
} from '../masterSize/hooks';
import { salesOrderApi } from '../salesOrder/api';

const SALES_ORDER_HEADER_FIELDS = [
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
  { key: 'Country of Production', label: 'Country of Production' },
  { key: 'Country of Bakery', label: 'Country of Bakery' },
  { key: 'Country of Origin', label: 'Country of Origin' },
  { key: 'Term of Payment', label: 'Term of Payment' },
  { key: 'No of Pieces', label: 'No of Pieces' },
  { key: 'Sales Models', label: 'Sales Models' },
] as const;

type BomDraftRow = {
  position: string;
  placement: string;
  type: string;
  description: string;
  composition: string;
  materialSupplier: string;
};

type DetailDraftRow = {
  countryOfDestination: string;
  type: string;
  articleNo?: string;
  color: string;
  size: string;
  qty: string;
  total: string;
  noOfAsst?: string;
  editable: boolean;
};

type CountryBreakdownRow = {
  country: string;
  countryOfDestination?: string;
  pmCode: string;
  total: string;
  editable: boolean;
};

type Section2cDraftRow = {
  article: string;
  size: string;
  qty: string;
  editable: boolean;
};

type Section2cTotalDraftRow = {
  article: string;
  total: string;
  editable: boolean;
};

const DETAIL_SIZES = ['XS', 'S', 'M', 'L', 'XL'] as const;

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
  const low = target.toLowerCase();
  return (m?.[target] ?? m?.[low] ?? '').toString();
}

function pickArticleNo(m: Record<string, any>): string {
  const direct = m?.articleNo ?? m?.article ?? m?.articleNumber ?? m?.article_no ?? m?.article_no_ ?? m?.['Article No'] ?? m?.['Article No:'];
  if (direct !== undefined && direct !== null && direct.toString().trim()) return direct.toString();
  const keys = Object.keys(m ?? {});
  const normalizedTarget = 'articleno';
  for (const k of keys) {
    const normalizedKey = k.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (normalizedKey === normalizedTarget || normalizedKey === 'articlenumber') {
      return (m?.[k] ?? '').toString();
    }
  }
  return '';
}

function pivotDetailRows(
  backendRows: Array<Record<string, any>>,
): Array<DetailDraftRow> {
  return backendRows
    .flatMap((m) => {
      if (m?.size !== undefined && m?.XS === undefined && m?.xs === undefined) {
        return [
          {
            countryOfDestination: (m?.countryOfDestination ?? m?.destinationCountry ?? '').toString(),
            type: (m?.type ?? '').toString(),
            articleNo: pickArticleNo(m),
            color: (m?.color ?? m?.colour ?? '').toString(),
            size: (m?.size ?? '').toString(),
            qty: (m?.qty ?? '').toString(),
            total: (m?.total ?? m?.Total ?? '').toString(),
            noOfAsst: (m?.noOfAsst ?? '').toString(),
            editable: true,
          },
        ];
      }
      const country = (m?.countryOfDestination ?? m?.destinationCountry ?? '').toString();
      const type = (m?.type ?? '').toString();
      const articleNo = pickArticleNo(m);
      const color = (m?.color ?? m?.colour ?? '').toString();
      const total = (m?.total ?? m?.Total ?? '').toString();
      const noOfAsst = (m?.noOfAsst ?? '').toString();
      // Use whatever size columns the backend sent on this row so
      // non-standard labels (kids/baby formats) flow through untouched.
      const dynamicKeys = extractSizeKeysFromRow(m as Record<string, any>);
      const sizesToEmit = dynamicKeys.length > 0 ? dynamicKeys : (DETAIL_SIZES as readonly string[]);
      return sizesToEmit.map((sz) => ({
        countryOfDestination: country,
        type,
        articleNo,
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

function safeJsonParse(v: string): any {
  try {
    return JSON.parse(v);
  } catch {
    return null;
  }
}

function EditableObjectTable({
  title,
  rows,
  onRowsChange,
}: {
  title: string;
  rows: any[];
  onRowsChange: (rows: any[]) => void;
}) {
  const columns = useMemo(() => {
    const keys = new Set<string>();
    for (const row of rows ?? []) {
      if (Array.isArray(row)) {
        row.forEach((_, idx) => keys.add(String(idx)));
      } else if (row && typeof row === 'object') {
        Object.keys(row).forEach((k) => {
          if (k.toLowerCase() !== 'page') {
            keys.add(k);
          }
        });
      }
    }
    return Array.from(keys);
  }, [rows]);

  return (
    <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
      <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
        <div className='text-sm font-semibold text-gray-900'>{title}</div>
        <Button type='button' variant='primary' onClick={() => onRowsChange([...(rows ?? []), {}])}>
          Add row
        </Button>
      </div>
      <div className='p-6'>
        {(rows ?? []).length === 0 ? (
          <div className='text-sm text-gray-500 italic'>No data.</div>
        ) : (
          <div className='w-full max-h-[50vh] overflow-auto'>
            <table className='min-w-[900px] w-full border border-gray-200 rounded-lg overflow-hidden'>
              <thead className='bg-gray-50 sticky top-0 z-10'>
                <tr>
                  {columns.map((c) => (
                    <th key={c} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>
                      {c}
                    </th>
                  ))}
                  <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                </tr>
              </thead>
              <tbody className='bg-white'>
                {rows.map((row, idx) => (
                  <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                    {columns.map((c) => (
                      <td key={c} className='px-3 py-2 align-top'>
                        <Input
                          value={(Array.isArray(row) ? row[Number(c)] : row?.[c]) ?? ''}
                          onChange={(e) => {
                            const next = [...rows];
                            if (Array.isArray(next[idx])) {
                              const arr = [...next[idx]];
                              arr[Number(c)] = e.target.value;
                              next[idx] = arr;
                            } else {
                              next[idx] = { ...(next[idx] ?? {}), [c]: e.target.value };
                            }
                            onRowsChange(next);
                          }}
                        />
                      </td>
                    ))}
                    <td className='px-3 py-2 align-top'>
                      <Button type='button' variant='danger' onClick={() => onRowsChange(rows.filter((_, i) => i !== idx))}>
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
  );
}

export function SalesOrderDraftEditPage() {
  const params = useParams();
  const navigate = useNavigate();
  const id = Number(params.id);

  const [salesOrderHeaderDraft, setSalesOrderHeaderDraft] = useState<Record<string, string>>({});
  const [bomDraftRows, setBomDraftRows] = useState<BomDraftRow[]>([]);
  const [bomProdUnitsRows, setBomProdUnitsRows] = useState<Array<Record<string, string>>>([]);
  const [bomYarnSourceTableRows, setBomYarnSourceTableRows] = useState<any[]>([]);
  const [productArticleTableRows, setProductArticleTableRows] = useState<any[]>([]);
  const [miscellaneousTableRows, setMiscellaneousTableRows] = useState<any[]>([]);
  const [timeOfDeliveryRows, setTimeOfDeliveryRows] = useState<Array<Record<string, string>>>([]);
  const [quantityPerArticleRows, setQuantityPerArticleRows] = useState<Array<Record<string, string>>>([]);
  const [invoiceAvgPriceRows, setInvoiceAvgPriceRows] = useState<Array<Record<string, string>>>([]);
  const [termsOfDeliveryRows, setTermsOfDeliveryRows] = useState<Array<Record<string, string>>>([]);
  const [salesSampleRows, setSalesSampleRows] = useState<Array<Record<string, string>>>([]);
  const [activePage, setActivePage] = useState<number>(1);
  const [termsOfDeliveryByPageDraft, setTermsOfDeliveryByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleTermsByPageDraft, setSalesSampleTermsByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleTermsOfDeliveryByPageDraft, setSalesSampleTermsOfDeliveryByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleTimeOfDeliveryByPageDraft, setSalesSampleTimeOfDeliveryByPageDraft] = useState<Record<number, string>>({});
  const [salesSampleDestinationStudioAddressByPageDraft, setSalesSampleDestinationStudioAddressByPageDraft] = useState<Record<number, string>>({});
  const [salesOrderDetailDraftRows, setSalesOrderDetailDraftRows] = useState<DetailDraftRow[]>([]);
  const [countryBreakdownDraftRows, setCountryBreakdownDraftRows] = useState<CountryBreakdownRow[]>([]);
  const [section2cDraftRows, setSection2cDraftRows] = useState<Section2cDraftRow[]>([]);
  const [section2cTotalDraftRows, setSection2cTotalDraftRows] = useState<Section2cTotalDraftRow[]>([]);
  const [successOpen, setSuccessOpen] = useState(false);
  const [successMessage, setSuccessMessage] = useState('Successfully updated draft');
  const [activeCountryTabAssortment, setActiveCountryTabAssortment] = useState<string>('');
  const [activeCountryTabSolid, setActiveCountryTabSolid] = useState<string>('');

  const ensureMasterSizes = useEnsureMasterSizesBatch();

  const normalizeSizeKey = (s: string) => (s || '').toUpperCase().replace(/\s+/g, '').replace(/\*+/g, '').trim();

  const section2NonTotalEntries = useMemo(() => {
    return (salesOrderDetailDraftRows ?? [])
      .map((row, idx) => ({ row, idx }))
      .filter(({ row }) => (row?.type ?? '').toString().trim().toLowerCase() !== 'total');
  }, [salesOrderDetailDraftRows]);

  const section2TotalByCountryRows = useMemo(() => {
    const totals = (salesOrderDetailDraftRows ?? []).filter((r) => (r?.type ?? '').toString().trim().toLowerCase() === 'total');
    if (totals.length === 0) return [] as Array<{ countryOfDestination: string; total: number }>;

    const byCountry = new Map<string, number>();
    for (const r of totals) {
      const countryOfDestination = (r?.countryOfDestination ?? '').toString().trim();
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

  const uniqueCountriesAssortment = useMemo(() => {
    const countries: string[] = [];
    const seen = new Set<string>();
    for (const r of salesOrderDetailDraftRows ?? []) {
      if ((r?.type ?? '').toString().trim().toLowerCase() !== 'assortment') continue;
      const c = (r?.countryOfDestination ?? '').toString().trim();
      if (c && !seen.has(c)) {
        seen.add(c);
        countries.push(c);
      }
    }
    if (countries.length > 0 && !activeCountryTabAssortment) {
      setActiveCountryTabAssortment(countries[0]);
    }
    return countries;
  }, [salesOrderDetailDraftRows]);

  const uniqueCountriesSolid = useMemo(() => {
    const countries: string[] = [];
    const seen = new Set<string>();
    for (const r of salesOrderDetailDraftRows ?? []) {
      if ((r?.type ?? '').toString().trim().toLowerCase() !== 'solid') continue;
      const c = (r?.countryOfDestination ?? '').toString().trim();
      if (c && !seen.has(c)) {
        seen.add(c);
        countries.push(c);
      }
    }
    if (countries.length > 0 && !activeCountryTabSolid) {
      setActiveCountryTabSolid(countries[0]);
    }
    return countries;
  }, [salesOrderDetailDraftRows]);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['sales-order-review', id],
    enabled: Number.isFinite(id) && id > 0,
    queryFn: async () => {
      const res = await salesOrderApi.getReviewById(id);
      return res.data;
    },
  });

  const payload = useMemo(() => {
    const p = data?.payloadJson ? safeJsonParse(data.payloadJson) : null;
    return p ?? {};
  }, [data?.payloadJson]);

  const pageCount = useMemo(() => {
    const pages = [
      ...(termsOfDeliveryRows ?? []),
      ...(quantityPerArticleRows ?? []),
      ...(invoiceAvgPriceRows ?? []),
      ...(salesSampleRows ?? []),
      ...(Array.isArray(payload?.raw?.salesSampleArticlesByPage) ? payload.raw.salesSampleArticlesByPage : []),
    ]
      .map((r) => Number((r?.page ?? '1').toString()))
      .filter((n) => Number.isFinite(n) && n > 0);
    return Math.max(1, pages.length > 0 ? Math.max(...pages) : Number(payload?.raw?.pageCount ?? payload?.pageCount ?? 1));
  }, [termsOfDeliveryRows, quantityPerArticleRows, invoiceAvgPriceRows, salesSampleRows, payload]);

  useEffect(() => {
    setActivePage((p) => Math.min(Math.max(1, p), pageCount));
  }, [pageCount]);

  const termsOfDeliveryForActivePage = useMemo(() => (termsOfDeliveryByPageDraft?.[activePage] ?? '').toString(), [activePage, termsOfDeliveryByPageDraft]);

  const quantityPerArticleRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    return (quantityPerArticleRows ?? []).filter((r) => (r?.page ?? '1').toString() === p);
  }, [activePage, quantityPerArticleRows]);

  const invoiceAvgPriceRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    return (invoiceAvgPriceRows ?? []).filter((r) => {
      const rp = (r?.page ?? '1').toString();
      return rp === p || (p === '1' && rp.trim() === '');
    });
  }, [activePage, invoiceAvgPriceRows]);

  const salesSampleRowsForActivePage = useMemo(() => {
    const p = String(activePage);
    const direct = (salesSampleRows ?? []).filter((r) => (r?.page ?? '1').toString() === p);
    if (direct.length > 0) return direct;
    const rawDirect = (Array.isArray(payload?.raw?.salesSampleArticlesByPage) ? payload.raw.salesSampleArticlesByPage : []).filter((r: any) => (r?.page ?? '1').toString() === p);
    if (rawDirect.length > 0) return rawDirect;
    const rawPages = (Array.isArray(payload?.raw?.salesSampleArticlesByPage) ? payload.raw.salesSampleArticlesByPage : [])
      .map((r: any) => Number((r?.page ?? '1').toString()))
      .filter((x: number) => Number.isFinite(x));
    const maxPage = rawPages.length > 0 ? Math.max(...rawPages) : 0;
    if (maxPage > 0) return (Array.isArray(payload?.raw?.salesSampleArticlesByPage) ? payload.raw.salesSampleArticlesByPage : []).filter((r: any) => (r?.page ?? '1').toString() === String(maxPage));
    return [];
  }, [activePage, salesSampleRows, payload]);

  const salesSampleTermsForActivePage = useMemo(() => (salesSampleTermsByPageDraft?.[activePage] ?? '').toString(), [activePage, salesSampleTermsByPageDraft]);
  const salesSampleTermsOfDeliveryForActivePage = useMemo(() => (salesSampleTermsOfDeliveryByPageDraft?.[activePage] ?? '').toString(), [activePage, salesSampleTermsOfDeliveryByPageDraft]);
  const salesSampleTimeOfDeliveryForActivePage = useMemo(() => (salesSampleTimeOfDeliveryByPageDraft?.[activePage] ?? '').toString(), [activePage, salesSampleTimeOfDeliveryByPageDraft]);
  const salesSampleDestinationStudioAddressForActivePage = useMemo(
    () => (salesSampleDestinationStudioAddressByPageDraft?.[activePage] ?? '').toString(),
    [activePage, salesSampleDestinationStudioAddressByPageDraft],
  );

  const hydrateFromPayload = () => {
    const ff = (payload?.formFields ?? {}) as Record<string, string>;
    const rawFf = (payload?.raw?.formFields ?? {}) as Record<string, string>;
    const nextHeader: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      if (f.key === 'Order Date') {
        nextHeader[f.key] = (ff?.[f.key] ?? rawFf?.['Date of Order'] ?? rawFf?.['Order Date'] ?? rawFf?.['Date (ISO)'] ?? rawFf?.['Date'] ?? '').toString();
      } else {
        nextHeader[f.key] = (ff?.[f.key] ?? rawFf?.[f.key] ?? '').toString();
      }
    }
    setSalesOrderHeaderDraft(nextHeader);

    const bom = (payload?.bomDraftRows ?? []) as any[];
    if (Array.isArray(bom)) {
      setBomDraftRows(
        bom.map((r) => ({
          position: (r?.position ?? '').toString(),
          placement: (r?.placement ?? '').toString(),
          type: (r?.type ?? '').toString(),
          description: (r?.description ?? '').toString(),
          composition: (r?.composition ?? '').toString(),
          materialSupplier: (r?.materialSupplier ?? '').toString(),
        })),
      );
    } else {
      setBomDraftRows([]);
    }

    const detail = (payload?.salesOrderDetailSizeBreakdown ?? []) as any[];
    if (Array.isArray(detail) && detail.length > 0) {
      setSalesOrderDetailDraftRows(pivotDetailRows(detail));
      const discovered = collectSizeLabelsFromRows(detail);
      if (discovered.length > 0) ensureMasterSizes.mutate(discovered);
    } else {
      setSalesOrderDetailDraftRows([]);
    }

    const tcb = (payload?.totalCountryBreakdown ?? []) as any[];
    if (Array.isArray(tcb)) {
      setCountryBreakdownDraftRows(
        tcb.map((r) => ({
          country: (r?.country ?? r?.destinationCountry ?? '').toString(),
          countryOfDestination: (r?.countryOfDestination ?? r?.destinationCountry ?? '').toString(),
          pmCode: (r?.pmCode ?? r?.pm ?? '').toString(),
          total: (r?.total ?? r?.Total ?? '').toString(),
          editable: true,
        })),
      );
    } else {
      setCountryBreakdownDraftRows([]);
    }

    const s2c = (payload?.section2cColourSizeBreakdown ?? []) as any[];
    if (Array.isArray(s2c)) {
      setSection2cDraftRows(
        s2c.map((r) => ({
          article: (r?.article ?? '').toString(),
          size: (r?.size ?? '').toString(),
          qty: (r?.qty ?? '').toString(),
          editable: true,
        })),
      );
    } else {
      setSection2cDraftRows([]);
    }

    const s2cTotal = (payload?.section2cColourSizeBreakdownTotal ?? []) as any[];
    if (Array.isArray(s2cTotal)) {
      setSection2cTotalDraftRows(
        s2cTotal.map((r) => ({
          article: (r?.article ?? '').toString(),
          total: (r?.total ?? '').toString(),
          editable: true,
        })),
      );
    } else {
      setSection2cTotalDraftRows([]);
    }

    setBomProdUnitsRows(Array.isArray(payload?.bomProdUnitsRows) ? payload.bomProdUnitsRows : []);
    setBomYarnSourceTableRows(Array.isArray(payload?.bomYarnSourceTableRows) ? payload.bomYarnSourceTableRows : []);
    setProductArticleTableRows(Array.isArray(payload?.productArticleTableRows) ? payload.productArticleTableRows : []);
    setMiscellaneousTableRows(Array.isArray(payload?.miscellaneousTableRows) ? payload.miscellaneousTableRows : []);
    setTimeOfDeliveryRows(Array.isArray(payload?.timeOfDeliveryRows) ? payload.timeOfDeliveryRows : []);
    setQuantityPerArticleRows(Array.isArray(payload?.quantityPerArticleRows) ? payload.quantityPerArticleRows : []);
    setInvoiceAvgPriceRows(Array.isArray(payload?.invoiceAvgPriceRows) ? payload.invoiceAvgPriceRows : []);
    setTermsOfDeliveryRows(Array.isArray(payload?.termsOfDeliveryRows) ? payload.termsOfDeliveryRows : []);
    setSalesSampleRows(Array.isArray(payload?.salesSampleRows) ? payload.salesSampleRows : []);

    const termsByPage: Record<number, string> = {};
    for (const r of Array.isArray(payload?.termsOfDeliveryRows) ? payload.termsOfDeliveryRows : []) {
      const p = Number((r?.page ?? '1').toString());
      if (Number.isFinite(p)) termsByPage[p] = (r?.termsOfDelivery ?? '').toString();
    }
    for (const r of Array.isArray(payload?.raw?.termsOfDeliveryByPage) ? payload.raw.termsOfDeliveryByPage : []) {
      const p = Number((r?.page ?? '1').toString());
      const v = (r?.termsOfDelivery ?? '').toString();
      if (Number.isFinite(p) && (termsByPage[p] ?? '').trim().length === 0) termsByPage[p] = v;
    }
    setTermsOfDeliveryByPageDraft(termsByPage);

    const sampleTerms: Record<number, string> = {};
    const sampleTermsOfDelivery: Record<number, string> = {};
    const sampleTod: Record<number, string> = {};
    const sampleDest: Record<number, string> = {};
    for (const r of Array.isArray(payload?.salesSampleRows) ? payload.salesSampleRows : []) {
      const p = Number((r?.page ?? '1').toString());
      if (!Number.isFinite(p)) continue;
      if ((sampleTerms[p] ?? '').trim().length === 0) sampleTerms[p] = (r?.salesSampleTerms ?? '').toString();
      if ((sampleTermsOfDelivery[p] ?? '').trim().length === 0) sampleTermsOfDelivery[p] = (r?.termsOfDelivery ?? '').toString();
      if ((sampleTod[p] ?? '').trim().length === 0) sampleTod[p] = (r?.timeOfDelivery ?? r?.tod ?? '').toString();
      if ((sampleDest[p] ?? '').trim().length === 0) sampleDest[p] = (r?.destinationStudioAddress ?? r?.destinationStudio ?? '').toString();
    }
    for (const r of Array.isArray(payload?.raw?.salesSampleTermsByPage) ? payload.raw.salesSampleTermsByPage : []) {
      const p = Number((r?.page ?? '1').toString());
      const v = (r?.salesSampleTerms ?? r?.terms ?? '').toString();
      if (Number.isFinite(p) && (sampleTerms[p] ?? '').trim().length === 0) sampleTerms[p] = v;
    }
    for (const r of Array.isArray(payload?.raw?.salesSampleTermsOfDeliveryByPage) ? payload.raw.salesSampleTermsOfDeliveryByPage : []) {
      const p = Number((r?.page ?? '1').toString());
      const v = (r?.termsOfDelivery ?? '').toString();
      if (Number.isFinite(p) && (sampleTermsOfDelivery[p] ?? '').trim().length === 0) sampleTermsOfDelivery[p] = v;
    }
    for (const r of Array.isArray(payload?.raw?.salesSampleTimeOfDeliveryByPage) ? payload.raw.salesSampleTimeOfDeliveryByPage : []) {
      const p = Number((r?.page ?? '1').toString());
      const v = (r?.timeOfDelivery ?? r?.tod ?? '').toString();
      if (Number.isFinite(p) && (sampleTod[p] ?? '').trim().length === 0) sampleTod[p] = v;
    }
    for (const r of Array.isArray(payload?.raw?.salesSampleDestinationStudioAddressByPage) ? payload.raw.salesSampleDestinationStudioAddressByPage : []) {
      const p = Number((r?.page ?? '1').toString());
      const v = (r?.destinationStudioAddress ?? r?.destinationStudio ?? r?.destination ?? '').toString();
      if (Number.isFinite(p) && (sampleDest[p] ?? '').trim().length === 0) sampleDest[p] = v;
    }
    setSalesSampleTermsByPageDraft(sampleTerms);
    setSalesSampleTermsOfDeliveryByPageDraft(sampleTermsOfDelivery);
    setSalesSampleTimeOfDeliveryByPageDraft(sampleTod);
    setSalesSampleDestinationStudioAddressByPageDraft(sampleDest);
  };

  useEffect(() => {
    if (!data) return;
    hydrateFromPayload();
  }, [data?.id, payload]);

  const saveDraftMutation = useMutation({
    mutationFn: async () => {
      const nextPayload = {
        ...(payload ?? {}),
        analyzedFileName: data?.analyzedFileName ?? payload?.analyzedFileName ?? '',
        formFields: salesOrderHeaderDraft,
        bomDraftRows,
        bomProdUnitsRows,
        bomYarnSourceTableRows,
        productArticleTableRows,
        miscellaneousTableRows,
        quantityPerArticleRows,
        timeOfDeliveryRows,
        invoiceAvgPriceRows,
        termsOfDeliveryRows: Object.entries(termsOfDeliveryByPageDraft).map(([page, termsOfDelivery]) => ({
          page: Number(page),
          termsOfDelivery,
        })),
        salesSampleRows: ((salesSampleRows.length > 0 ? salesSampleRows : (Array.isArray(payload?.raw?.salesSampleArticlesByPage) ? payload.raw.salesSampleArticlesByPage : [])) as Array<Record<string, any>>).map((row) => {
          const page = Number((row?.page ?? '1').toString());
          return {
            ...row,
            salesSampleTerms: salesSampleTermsByPageDraft[page] ?? row.salesSampleTerms ?? '',
            termsOfDelivery: salesSampleTermsOfDeliveryByPageDraft[page] ?? row.termsOfDelivery ?? '',
            timeOfDelivery: salesSampleTimeOfDeliveryByPageDraft[page] ?? row.timeOfDelivery ?? row.tod ?? '',
            destinationStudioAddress: salesSampleDestinationStudioAddressByPageDraft[page] ?? row.destinationStudioAddress ?? row.destinationStudio ?? '',
          };
        }),
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
        section2cColourSizeBreakdownTotal: section2cTotalDraftRows,
      };
      const responses = [];
      for (const documentType of ['supplementary', 'size-per-colour-breakdown', 'total-country-breakdown']) {
        responses.push(await salesOrderApi.saveDraft({ ...nextPayload, documentType }));
      }
      return responses;
    },
    onSuccess: () => {
      setSuccessMessage('Successfully updated draft');
      setSuccessOpen(true);
      refetch();
    },
    onError: (e: Error) => {
      alert(`Failed to update draft: ${e.message}`);
    },
  });

  const hasHeaderDraft = useMemo(() => {
    return SALES_ORDER_HEADER_FIELDS.some((f) => (salesOrderHeaderDraft[f.key] ?? '').trim().length > 0);
  }, [salesOrderHeaderDraft]);

  if (isLoading) {
    return <div className='text-sm text-gray-500'>Loading...</div>;
  }

  if (error) {
    return <div className='text-sm text-red-600'>Failed to load data.</div>;
  }

  if (!data) {
    return <div className='text-sm text-gray-500'>No data.</div>;
  }

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
              navigate('/sales-order-hm');
            }}
          >
            OK
          </Button>
        </div>
      </Modal>
      <div className='flex items-center justify-between'>
        <div>
          <h1 className='text-2xl font-bold text-gray-900'>Sales Order Draft</h1>
          <div className='text-sm text-gray-500'>ID: {data.id}</div>
        </div>
        <div className='flex gap-2'>
          <Button type='button' variant='outline' onClick={() => navigate('/sales-order-hm')}>
            Back
          </Button>
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-sm font-semibold text-gray-900'>Sales Order Header (Draft)</div>
          <Button
            type='button'
            variant='primary'
            disabled={saveDraftMutation.isPending}
            onClick={() => saveDraftMutation.mutate()}
          >
            Save Draft
          </Button>
        </div>
        <div className='p-6 space-y-6'>
          {!hasHeaderDraft ? (
            <div className='text-sm text-gray-500 italic'>No header fields.</div>
          ) : (
            <div className='grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-3'>
              {SALES_ORDER_HEADER_FIELDS.map((field) => (
                <div key={field.key} className='grid grid-cols-12 items-center gap-3'>
                  <div className='col-span-4 text-sm text-gray-700'>{field.label}</div>
                  <div className='col-span-8'>
                    <Input
                      value={salesOrderHeaderDraft[field.key] ?? ''}
                      onChange={(e) => {
                        const v = e.target.value;
                        setSalesOrderHeaderDraft((prev) => ({ ...prev, [field.key]: v }));
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
        <div className='px-6 py-3 border-b border-gray-200 flex gap-2 overflow-auto'>
          {Array.from({ length: pageCount }).map((_, idx) => {
            const p = idx + 1;
            return (
              <button
                key={p}
                type='button'
                className={
                  p === activePage
                    ? 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-indigo-600 text-white'
                    : 'px-3 py-1.5 rounded-lg text-xs font-semibold bg-gray-100 text-gray-700 hover:bg-gray-200'
                }
                onClick={() => setActivePage(p)}
              >
                Page-{p}
              </button>
            );
          })}
        </div>
        <div className='p-6 space-y-6 bg-gray-50'>
          <div className='bg-white rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Terms of Delivery</div>
            <textarea
              className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
              value={termsOfDeliveryForActivePage}
              onChange={(e) => setTermsOfDeliveryByPageDraft((prev) => ({ ...prev, [activePage]: e.target.value }))}
            />
          </div>
          {activePage === 1 && (
            <EditableObjectTable title='Time of Delivery' rows={timeOfDeliveryRows.filter((r) => (r?.page ?? '1').toString() === String(activePage))} onRowsChange={(rows) => setTimeOfDeliveryRows((prev) => [...prev.filter((r) => (r?.page ?? '1').toString() !== String(activePage)), ...rows.map((r) => ({ ...r, page: String(activePage) }))])} />
          )}
          <EditableObjectTable title='Quantity per Article' rows={quantityPerArticleRowsForActivePage} onRowsChange={(rows) => setQuantityPerArticleRows((prev) => [...prev.filter((r) => (r?.page ?? '1').toString() !== String(activePage)), ...rows.map((r) => ({ ...r, page: String(activePage) }))])} />
          <EditableObjectTable title='Invoice Average Price' rows={invoiceAvgPriceRowsForActivePage} onRowsChange={(rows) => setInvoiceAvgPriceRows((prev) => [...prev.filter((r) => (r?.page ?? '1').toString() !== String(activePage)), ...rows.map((r) => ({ ...r, page: String(activePage) }))])} />
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 text-sm font-semibold text-gray-900'>Sales Sample</div>
        <div className='p-6 space-y-4'>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Sales Sample Terms</div>
            <textarea className='w-full min-h-[110px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600' value={salesSampleTermsForActivePage} onChange={(e) => setSalesSampleTermsByPageDraft((prev) => ({ ...prev, [activePage]: e.target.value }))} />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Terms of Delivery</div>
            <textarea className='w-full min-h-[84px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600' value={salesSampleTermsOfDeliveryForActivePage} onChange={(e) => setSalesSampleTermsOfDeliveryByPageDraft((prev) => ({ ...prev, [activePage]: e.target.value }))} />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Destination Studio Address</div>
            <textarea className='w-full min-h-[84px] rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600' value={salesSampleDestinationStudioAddressForActivePage} onChange={(e) => setSalesSampleDestinationStudioAddressByPageDraft((prev) => ({ ...prev, [activePage]: e.target.value }))} />
          </div>
          <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
            <div className='text-sm font-semibold text-gray-900 mb-3'>Time Of Delivery</div>
            <Input value={salesSampleTimeOfDeliveryForActivePage} onChange={(e) => setSalesSampleTimeOfDeliveryByPageDraft((prev) => ({ ...prev, [activePage]: e.target.value }))} />
          </div>
          <EditableObjectTable title='Articles' rows={salesSampleRowsForActivePage} onRowsChange={(rows) => setSalesSampleRows((prev) => [...prev.filter((r) => (r?.page ?? '1').toString() !== String(activePage)), ...rows.map((r) => ({ ...r, page: String(activePage), salesSampleTerms: salesSampleTermsByPageDraft[activePage] ?? '', termsOfDelivery: salesSampleTermsOfDeliveryByPageDraft[activePage] ?? '', timeOfDelivery: salesSampleTimeOfDeliveryByPageDraft[activePage] ?? '', destinationStudioAddress: salesSampleDestinationStudioAddressByPageDraft[activePage] ?? '' }))])} />
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 2 – SALES ORDER DETAIL (SIZE BREAKDOWN)</div>
        </div>

        <div className='p-6'>
          <div className='grid grid-cols-1 lg:grid-cols-2 gap-6'>
            {/* Assortment Table */}
            <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
              <div className='flex items-center justify-between mb-4'>
                <div className='text-sm font-semibold text-gray-900'>Assortment</div>
                <Button
                  type='button'
                  variant='primary'
                  onClick={() => {
                    setSalesOrderDetailDraftRows((prev) => [
                      ...prev,
                      { countryOfDestination: activeCountryTabAssortment, type: 'Assortment', articleNo: '', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                    ]);
                  }}
                >
                  Add row
                </Button>
              </div>

              {uniqueCountriesAssortment.length > 0 && (
                <div className='mb-4 flex items-center gap-2 overflow-x-auto'>
                  {uniqueCountriesAssortment.map((country) => (
                    <button
                      key={country}
                      onClick={() => setActiveCountryTabAssortment(country)}
                      className={`px-3 py-1.5 rounded-lg text-sm font-medium whitespace-nowrap transition-all ${
                        activeCountryTabAssortment === country
                          ? 'bg-blue-600 text-white'
                          : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                      }`}
                    >
                      {country}
                    </button>
                  ))}
                </div>
              )}

              <div className='w-full max-h-[60vh] overflow-auto'>
                <table className='min-w-[900px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                  <thead className='bg-white sticky top-0 z-10'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Article No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Color</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Size</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody className='bg-white'>
                    {section2NonTotalEntries
                      .filter(({ row }) => (row?.countryOfDestination ?? '').toString() === activeCountryTabAssortment && (row?.type ?? '').toString().trim().toLowerCase() === 'assortment')
                      .map(({ row, idx }) => (
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
                              value={(row as any).articleNo ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, articleNo: v } : r)));
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
                                setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(v) } : r)));
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
            </div>

            {/* Solid Table */}
            <div className='bg-gray-50 rounded-xl border border-gray-200 p-4'>
              <div className='flex items-center justify-between mb-4'>
                <div className='text-sm font-semibold text-gray-900'>Solid</div>
                <Button
                  type='button'
                  variant='primary'
                  onClick={() => {
                    setSalesOrderDetailDraftRows((prev) => [
                      ...prev,
                      { countryOfDestination: activeCountryTabSolid, type: 'Solid', articleNo: '', color: '', size: '', qty: '', total: '', noOfAsst: '', editable: true },
                    ]);
                  }}
                >
                  Add row
                </Button>
              </div>

              {uniqueCountriesSolid.length > 0 && (
                <div className='mb-4 flex items-center gap-2 overflow-x-auto'>
                  {uniqueCountriesSolid.map((country) => (
                    <button
                      key={country}
                      onClick={() => setActiveCountryTabSolid(country)}
                      className={`px-3 py-1.5 rounded-lg text-sm font-medium whitespace-nowrap transition-all ${
                        activeCountryTabSolid === country
                          ? 'bg-blue-600 text-white'
                          : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                      }`}
                    >
                      {country}
                    </button>
                  ))}
                </div>
              )}

              <div className='w-full max-h-[60vh] overflow-auto'>
                <table className='min-w-[900px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                  <thead className='bg-white sticky top-0 z-10'>
                    <tr>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Article No</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Color</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Size</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Qty</th>
                      <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                    </tr>
                  </thead>
                  <tbody className='bg-white'>
                    {section2NonTotalEntries
                      .filter(({ row }) => (row?.countryOfDestination ?? '').toString() === activeCountryTabSolid && (row?.type ?? '').toString().trim().toLowerCase() === 'solid')
                      .map(({ row, idx }) => (
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
                              value={(row as any).articleNo ?? ''}
                              onChange={(e) => {
                                const v = e.target.value;
                                setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, articleNo: v } : r)));
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
                                setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, qty: normalizeDigits(v) } : r)));
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
            </div>
          </div>
        </div>
      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div className='text-xs font-semibold text-gray-500'>SECTION 2B – TOTAL COUNTRY BREAKDOWN</div>
          <Button
            type='button'
            variant='primary'
            onClick={() => {
              setCountryBreakdownDraftRows((prev) => [...prev, { country: '', pmCode: '', total: '', editable: true }]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6'>
          {countryBreakdownDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No country breakdown rows.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
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
                          onChange={(e) => setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, country: e.target.value } : r)))}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.pmCode}
                          onChange={(e) => setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, pmCode: e.target.value } : r)))}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={
                            (row.countryOfDestination ?? '').toString() ||
                            section2TotalCountryLookup.get((row.country ?? '').toString().trim().toLowerCase()) ||
                            ''
                          }
                          onChange={(e) =>
                            setCountryBreakdownDraftRows((prev) =>
                              prev.map((r, i) => (i === idx ? { ...r, countryOfDestination: e.target.value } : r)),
                            )
                          }
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => setCountryBreakdownDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(e.target.value) } : r)))}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Button
                          type='button'
                          variant='danger'
                          onClick={() => setCountryBreakdownDraftRows((prev) => prev.filter((_, i) => i !== idx))}
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
            onClick={() => {
              setSection2cDraftRows((prev) => [...(prev ?? []), { article: '', size: '', qty: '', editable: true }]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6 space-y-6'>
          {section2cDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No size breakdown rows.</div>
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
            onClick={() => {
              setBomDraftRows((prev) => [...prev, { position: '', placement: '', type: '', description: '', composition: '', materialSupplier: '' }]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6'>
          {bomDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No BoM rows.</div>
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
                        <Input value={row.position} onChange={(e) => setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, position: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.placement} onChange={(e) => setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, placement: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input value={row.type} onChange={(e) => setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, type: e.target.value } : r)))} />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.description}
                          rows={2}
                          onChange={(e) => setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, description: e.target.value } : r)))}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.composition}
                          rows={2}
                          onChange={(e) => setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, composition: e.target.value } : r)))}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <textarea
                          className='w-full rounded-lg border border-gray-200 px-3 py-2 text-sm text-gray-700 focus:outline-none focus:ring-2 focus:ring-indigo-600'
                          value={row.materialSupplier}
                          rows={2}
                          onChange={(e) =>
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, materialSupplier: e.target.value } : r)))
                          }
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

      <EditableObjectTable title='Bill of Material: Production Units and Processing Capabilities' rows={bomProdUnitsRows} onRowsChange={setBomProdUnitsRows} />
      <EditableObjectTable title='Bill of Material: Yarn Source Details' rows={bomYarnSourceTableRows} onRowsChange={setBomYarnSourceTableRows} />
      <EditableObjectTable title='Product Article' rows={productArticleTableRows} onRowsChange={setProductArticleTableRows} />
      <EditableObjectTable title='Miscellaneous' rows={miscellaneousTableRows} onRowsChange={setMiscellaneousTableRows} />
    </div>
  );
}

function normalizeDigits(input: string): string {
  if (!input) return '';
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

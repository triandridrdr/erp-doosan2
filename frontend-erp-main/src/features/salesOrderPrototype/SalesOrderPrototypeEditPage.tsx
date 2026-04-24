import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { CheckCircle2 } from 'lucide-react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { SizeAutocompleteInput } from '../../components/ui/SizeAutocompleteInput';
import { salesOrderPrototypeApi } from './api';

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
      const color = (m?.color ?? m?.colour ?? '').toString();
      const total = (m?.total ?? m?.Total ?? '').toString();
      const noOfAsst = (m?.noOfAsst ?? '').toString();
      return DETAIL_SIZES.map((sz) => ({
        countryOfDestination: country,
        type,
        color,
        size: sz,
        qty: (m?.[sz] ?? m?.[sz.toLowerCase()] ?? '').toString(),
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

export function SalesOrderPrototypeEditPage() {
  const params = useParams();
  const navigate = useNavigate();
  const id = Number(params.id);

  const [salesOrderHeaderDraft, setSalesOrderHeaderDraft] = useState<Record<string, string>>({});
  const [bomDraftRows, setBomDraftRows] = useState<BomDraftRow[]>([]);
  const [salesOrderDetailDraftRows, setSalesOrderDetailDraftRows] = useState<DetailDraftRow[]>([]);
  const [countryBreakdownDraftRows, setCountryBreakdownDraftRows] = useState<CountryBreakdownRow[]>([]);
  const [section2cDraftRows, setSection2cDraftRows] = useState<Section2cDraftRow[]>([]);
  const [section2cTotalDraftRows, setSection2cTotalDraftRows] = useState<Section2cTotalDraftRow[]>([]);
  const [successOpen, setSuccessOpen] = useState(false);
  const [successMessage, setSuccessMessage] = useState('Successfully updated draft');

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

  const section2cGrandTotal = useMemo(() => {
    let sum = 0;
    for (const r of section2cDraftRows ?? []) {
      const d = normalizeDigits((r?.qty ?? '').toString());
      if (d) sum += Number(d);
    }
    return sum;
  }, [section2cDraftRows]);

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['sales-order-prototypes', id],
    enabled: Number.isFinite(id) && id > 0,
    queryFn: async () => {
      const res = await salesOrderPrototypeApi.getOne(id);
      return res.data;
    },
  });

  const payload = useMemo(() => {
    const p = data?.payloadJson ? safeJsonParse(data.payloadJson) : null;
    return p ?? {};
  }, [data?.payloadJson]);

  const hydrateFromPayload = () => {
    const ff = (payload?.formFields ?? {}) as Record<string, string>;
    const nextHeader: Record<string, string> = {};
    for (const f of SALES_ORDER_HEADER_FIELDS) {
      nextHeader[f] = (ff?.[f] ?? '').toString();
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
        salesOrderDetailSizeBreakdown: salesOrderDetailDraftRows,
        totalCountryBreakdown: countryBreakdownDraftRows,
        section2cColourSizeBreakdown: section2cDraftRows,
        section2cColourSizeBreakdownTotal: section2cTotalDraftRows,
      };
      return salesOrderPrototypeApi.update(id, nextPayload);
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
    return SALES_ORDER_HEADER_FIELDS.some((f) => (salesOrderHeaderDraft[f] ?? '').trim().length > 0);
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
              navigate('/sales-order-prototype');
            }}
          >
            OK
          </Button>
        </div>
      </Modal>
      <div className='flex items-center justify-between'>
        <div>
          <h1 className='text-2xl font-bold text-gray-900'>Sales Order Prototype</h1>
          <div className='text-sm text-gray-500'>ID: {data.id}</div>
        </div>
        <div className='flex gap-2'>
          <Button type='button' variant='outline' onClick={() => navigate('/sales-order-prototype')}>
            Back
          </Button>
        </div>
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
              disabled={saveDraftMutation.isPending}
              onClick={() => saveDraftMutation.mutate()}
            >
              Save Draft
            </Button>
          </div>
        </div>
        <div className='p-6'>
          {!hasHeaderDraft ? (
            <div className='text-sm text-gray-500 italic'>No header fields.</div>
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
          <Button
            type='button'
            variant='primary'
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
        <div className='p-6'>
          {section2NonTotalEntries.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No detail rows.</div>
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
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(v) } : r)));
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
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'>{formatIdThousands(section2cGrandTotal.toString())}</td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                    <td className='px-3 py-2 text-sm font-semibold text-gray-700 whitespace-nowrap'></td>
                  </tr>
                </tbody>
              </table>
            </div>
          )}

          {section2cTotalDraftRows.length === 0 ? null : (
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
                            setSection2cTotalDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, article: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={formatIdThousands(row.total)}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSection2cTotalDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: normalizeDigits(v) } : r)));
                          }}
                          style={{ textAlign: 'left' }}
                        />
                      </td>
                      <td className='px-3 py-2 text-sm text-gray-700 align-top whitespace-nowrap'>{row.editable ? 'TRUE' : 'FALSE'}</td>
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

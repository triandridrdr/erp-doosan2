import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
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
  XS: string;
  S: string;
  M: string;
  L: string;
  XL: string;
  total: string;
  editable: boolean;
};

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
    if (Array.isArray(detail)) {
      setSalesOrderDetailDraftRows(
        detail.map((m) => ({
          countryOfDestination: (m?.countryOfDestination ?? m?.destinationCountry ?? '').toString(),
          type: (m?.type ?? '').toString(),
          color: (m?.color ?? m?.colour ?? '').toString(),
          XS: (m?.XS ?? m?.xs ?? '').toString(),
          S: (m?.S ?? m?.s ?? '').toString(),
          M: (m?.M ?? m?.m ?? '').toString(),
          L: (m?.L ?? m?.l ?? '').toString(),
          XL: (m?.XL ?? m?.xl ?? '').toString(),
          total: (m?.total ?? m?.Total ?? '').toString(),
          editable: true,
        })),
      );
    } else {
      setSalesOrderDetailDraftRows([]);
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
      };
      return salesOrderPrototypeApi.update(id, nextPayload);
    },
    onSuccess: () => {
      alert('Draft updated.');
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
                { countryOfDestination: '', type: '', color: '', XS: '', S: '', M: '', L: '', XL: '', total: '', editable: true },
              ]);
            }}
          >
            Add row
          </Button>
        </div>
        <div className='p-6'>
          {salesOrderDetailDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No detail rows.</div>
          ) : (
            <div className='w-full max-h-[60vh] overflow-auto'>
              <table className='min-w-[1550px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Country of Destination</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Type</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Color</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>XS</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>S</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>M</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>L</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>XL</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Total</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200 whitespace-nowrap'>Editable</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Actions</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {salesOrderDetailDraftRows.map((row, idx) => (
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
                        <Input
                          value={row.XS}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, XS: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.S}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, S: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.M}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, M: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.L}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, L: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.XL}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, XL: v } : r)));
                          }}
                        />
                      </td>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.total}
                          onChange={(e) => {
                            const v = e.target.value;
                            setSalesOrderDetailDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, total: v } : r)));
                          }}
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

import { useMutation } from '@tanstack/react-query';
import { AlertCircle, Loader2, Upload } from 'lucide-react';
import { useMemo, useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { ocrNewApi } from './api';
import type { OcrNewDocumentAnalysisResponseData } from './types';

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
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [activeFileIndex, setActiveFileIndex] = useState<number>(0);
  const [results, setResults] = useState<Array<{ fileName: string; data: OcrNewDocumentAnalysisResponseData }>>([]);
  const [data, setData] = useState<OcrNewDocumentAnalysisResponseData | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [multiFileLogs, setMultiFileLogs] = useState<string[]>([]);

  const [salesOrderHeaderDraft, setSalesOrderHeaderDraft] = useState<Record<string, string>>({});
  const [bomDraftRows, setBomDraftRows] = useState<Array<{ component: string; description: string; category: string; composition: string }>>([]);
  const [salesOrderDetailDraftRows, setSalesOrderDetailDraftRows] = useState<
    Array<{
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
    }>
  >([]);

  const appendLog = (msg: string) => {
    const ts = new Date().toISOString();
    setMultiFileLogs((prev) => [...prev, `[${ts}] ${msg}`]);
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
        component: r?.[0] ?? '',
        description: r?.[1] ?? '',
        category: r?.[2] ?? '',
        composition: r?.[3] ?? '',
      }));
      setBomDraftRows(rows);
    } else {
      setBomDraftRows([]);
    }

    const backendDetail = d?.salesOrderDetailSizeBreakdown ?? [];
    if (backendDetail.length > 0) {
      setSalesOrderDetailDraftRows(
        backendDetail
          .map((m) => ({
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
          }))
          .filter((r) => [r.countryOfDestination, r.type, r.color, r.XS, r.S, r.M, r.L, r.XL, r.total].some((v) => v.trim().length > 0)),
      );
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

    let bomRows: Array<{ component: string; description: string; category: string; composition: string }> = [];
    for (const r of out) {
      const bomTable = (r?.data?.tables ?? []).find((t) => isBomDraftTable(t.rows));
      if (bomTable?.rows?.length) {
        bomRows = bomTable.rows.slice(1).map((row) => ({
          component: row?.[0] ?? '',
          description: row?.[1] ?? '',
          category: row?.[2] ?? '',
          composition: row?.[3] ?? '',
        }));
        break;
      }
    }
    setBomDraftRows(bomRows);

    let detailRows: Array<{
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
    }> = [];
    for (const r of out) {
      const backendDetail = r?.data?.salesOrderDetailSizeBreakdown ?? [];
      if (backendDetail.length > 0) {
        detailRows = backendDetail
          .map((m) => ({
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
          }))
          .filter((row) =>
            [row.countryOfDestination, row.type, row.color, row.XS, row.S, row.M, row.L, row.XL, row.total].some((v) => v.trim().length > 0),
          );
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


  const hasHeaderDraft = useMemo(() => {
    return SALES_ORDER_HEADER_FIELDS.some((f) => (salesOrderHeaderDraft[f] ?? '').trim().length > 0);
  }, [salesOrderHeaderDraft]);

  return (
    <div className='space-y-6'>
      <div className='bg-white rounded-2xl border border-gray-200 p-6'>
        <div className='flex items-start justify-between gap-4'>
          <div>
            <h3 className='text-lg font-bold text-gray-900'>OCR New</h3>
            <p className='text-sm text-gray-500 mt-1'>Offline OCR (no Textract). PDF will be rendered to PNG (300 DPI) then analyzed.</p>
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

      </div>

      <div className='bg-white rounded-2xl border border-gray-200 overflow-hidden'>
        <div className='px-6 py-4 border-b border-gray-200 flex items-center justify-between'>
          <div>
            <div className='text-xs font-semibold text-gray-500'>SECTION 1 – SALES ORDER HEADER (DRAFT)</div>
          </div>
          <div className='flex gap-2'>
            <Button type='button' variant='primary' disabled={!data}>
              Save Draft
            </Button>
            <Button type='button' variant='primary' disabled={!data}>
              Attach Style
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
          <Button
            type='button'
            variant='primary'
            disabled={!data}
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
          {!data ? (
            <div className='text-sm text-gray-500 italic'>No data.</div>
          ) : salesOrderDetailDraftRows.length === 0 ? (
            <div className='text-sm text-gray-500 italic'>No detail table detected.</div>
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
            disabled={!data}
            onClick={() => {
              setBomDraftRows((prev) => [...prev, { component: '', description: '', category: '', composition: '' }]);
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
              <table className='min-w-[900px] w-full border border-gray-200 rounded-lg overflow-hidden'>
                <thead className='bg-gray-50 sticky top-0 z-10'>
                  <tr>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Component</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Description</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Category</th>
                    <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Composition</th>
                  </tr>
                </thead>
                <tbody className='bg-white'>
                  {bomDraftRows.map((row, idx) => (
                    <tr key={idx} className='border-b border-gray-100 last:border-b-0'>
                      <td className='px-3 py-2 align-top'>
                        <Input
                          value={row.component}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, component: v } : r)));
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
                          value={row.category}
                          onChange={(e) => {
                            const v = e.target.value;
                            setBomDraftRows((prev) => prev.map((r, i) => (i === idx ? { ...r, category: v } : r)));
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

function isBomDraftTable(rows: Array<Array<string>> | undefined): boolean {
  if (!rows || rows.length === 0) return false;
  const header = rows[0] ?? [];
  const norm = (s: string | undefined) => (s ?? '').toLowerCase().replace(/\s+/g, ' ').trim();
  if (header.length < 4) return false;
  return (
    norm(header[0]) === 'component' &&
    norm(header[1]) === 'description' &&
    norm(header[2]) === 'category' &&
    norm(header[3]) === 'composition'
  );
}

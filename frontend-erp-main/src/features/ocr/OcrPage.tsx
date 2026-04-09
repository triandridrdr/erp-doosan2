/**
 * @file features/ocr/OcrPage.tsx
 * @description OCR 기능을 제공하는 페이지 컴포넌트입니다.
 * 이미지 파일을 업로드하여 단순 텍스트 추출 또는 상세 문서 분석(테이블, Key-Value)을 수행합니다.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertCircle, FileText, Loader2, Table as TableIcon, Type, Upload } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { ocrApi } from './api';
import type { DocumentAnalysisResponseData, TableDto } from './types';
import { salesApi, type SalesOrderRequest } from '../sales/api';

// OCR 모드 정의: 단순 추출(extract) vs 문서 분석(analyze)
type OcrMode = 'extract' | 'analyze';

type GarmentSalesOrderDraft = {
  header: {
    orderNo: string;
    dateOfOrder: string;
    supplierCode: string;
    supplierName: string;
    productNo: string;
    productName: string;
    productType: string;
    productDescription: string;
    season: string;
    optionNo: string;
    developmentNo: string;
    productDevName: string;
    customsCustomerGroup: string;
    typeOfConstruction: string;
    remarks: string;
  };
  salesOrderDetails: Array<Record<string, string>>;
  bomItems: Array<Record<string, string>>;
};

const DRAFT_STORAGE_KEY = 'ocr_sales_order_draft_v1';

function getHeaderValue(
  data: DocumentAnalysisResponseData | null,
  key: keyof GarmentSalesOrderDraft['header'],
): string {
  const v = data?.classified?.salesOrderHeader?.[key];
  return typeof v === 'string' ? v : '';
}

function buildColumns(rows: Array<Record<string, string>>): string[] {
  const cols = new Set<string>();
  for (const r of rows) {
    for (const k of Object.keys(r)) {
      if (k && k.trim()) cols.add(k);
    }
  }
  return Array.from(cols);
}

function getFirstValue(row: Record<string, string>, keys: string[]): string {
  for (const k of keys) {
    const direct = row[k];
    if (typeof direct === 'string' && direct.trim()) return direct.trim();
  }
  const lowered = Object.fromEntries(Object.entries(row).map(([k, v]) => [k.toLowerCase(), v]));
  for (const k of keys) {
    const v = lowered[k.toLowerCase()];
    if (typeof v === 'string' && v.trim()) return v.trim();
  }
  return '';
}

function parseNumberLike(v: string, fallback: number): number {
  const cleaned = v.replace(/[^0-9.\-]/g, '');
  const n = Number(cleaned);
  return Number.isFinite(n) ? n : fallback;
}

export function OcrPage() {
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<OcrMode>('extract'); // 현재 선택된 모드
  const [selectedFile, setSelectedFile] = useState<File | null>(null); // 업로드된 파일
  const [previewUrl, setPreviewUrl] = useState<string | null>(null); // 이미지 미리보기 URL

  const [draft, setDraft] = useState<GarmentSalesOrderDraft | null>(null);
  const [hasUserEditedDraft, setHasUserEditedDraft] = useState(false);

  // 텍스트 추출 Mutation
  const {
    mutate: extractText,
    isPending: isExtractPending,
    data: extractResult,
    error: extractError,
    reset: resetExtract,
  } = useMutation({
    mutationFn: ocrApi.extract,
  });

  // 문서 분석 Mutation
  const {
    mutate: analyzeDoc,
    isPending: isAnalyzePending,
    data: analyzeResult,
    error: analyzeError,
    reset: resetAnalyze,
  } = useMutation({
    mutationFn: ocrApi.analyze,
  });

  const isPending = isExtractPending || isAnalyzePending;

  const analyzedData = analyzeResult?.data ?? null;

  const parsedDraft = useMemo<GarmentSalesOrderDraft | null>(() => {
    if (!analyzedData?.classified?.salesOrderHeader) return null;
    return {
      header: {
        orderNo: getHeaderValue(analyzedData, 'orderNo'),
        dateOfOrder: getHeaderValue(analyzedData, 'dateOfOrder'),
        supplierCode: getHeaderValue(analyzedData, 'supplierCode'),
        supplierName: getHeaderValue(analyzedData, 'supplierName'),
        productNo: getHeaderValue(analyzedData, 'productNo'),
        productName: getHeaderValue(analyzedData, 'productName'),
        productType: getHeaderValue(analyzedData, 'productType'),
        productDescription: getHeaderValue(analyzedData, 'productDescription'),
        season: getHeaderValue(analyzedData, 'season'),
        optionNo: getHeaderValue(analyzedData, 'optionNo'),
        developmentNo: getHeaderValue(analyzedData, 'developmentNo'),
        productDevName: getHeaderValue(analyzedData, 'productDevName'),
        customsCustomerGroup: getHeaderValue(analyzedData, 'customsCustomerGroup'),
        typeOfConstruction: getHeaderValue(analyzedData, 'typeOfConstruction'),
        remarks: '',
      },
      salesOrderDetails: analyzedData.classified?.salesOrderDetails ?? [],
      bomItems: analyzedData.classified?.bomItems ?? [],
    };
  }, [analyzedData]);

  useEffect(() => {
    if (mode !== 'analyze') return;
    if (!parsedDraft) return;
    if (hasUserEditedDraft) return;
    setDraft(parsedDraft);
  }, [hasUserEditedDraft, mode, parsedDraft]);

  const saveDraft = () => {
    if (!draft) return;
    localStorage.setItem(DRAFT_STORAGE_KEY, JSON.stringify(draft));
    alert('Draft saved.');
  };

  const loadDraft = () => {
    const raw = localStorage.getItem(DRAFT_STORAGE_KEY);
    if (!raw) {
      alert('No saved draft found.');
      return;
    }
    try {
      const parsed = JSON.parse(raw) as GarmentSalesOrderDraft;
      setDraft(parsed);
      setHasUserEditedDraft(true);
    } catch {
      alert('Failed to load draft.');
    }
  };

  const clearDraft = () => {
    setDraft(null);
    setHasUserEditedDraft(false);
    localStorage.removeItem(DRAFT_STORAGE_KEY);
  };

  const createSalesOrderMutation = useMutation({
    mutationFn: (payload: SalesOrderRequest) => salesApi.create(payload),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] });
      alert('Sales order created successfully.');
    },
    onError: (error: Error) => {
      alert(`Failed to create sales order: ${error.message}`);
    },
  });

  const createSalesOrderFromDraft = () => {
    if (!draft) return;

    const orderDate = (draft.header.dateOfOrder || new Date().toISOString().split('T')[0]).trim();

    const customerCode = (draft.header.supplierCode || draft.header.orderNo || 'OCR-CUST').trim();
    const customerName = (draft.header.supplierName || 'OCR Customer').trim();

    const mappedLines = draft.salesOrderDetails
      .map((row, idx) => {
        const itemCode = getFirstValue(row, ['itemCode', 'ITEM CODE', 'Item Code', 'Material Code', 'Code', '품번', '품목코드']);
        const itemName = getFirstValue(row, ['itemName', 'ITEM NAME', 'Item Name', 'Description', '품명', '품목명']);
        const qtyStr = getFirstValue(row, ['quantity', 'QTY', 'Qty', 'Quantity', '수량']);
        const unitPriceStr = getFirstValue(row, ['unitPrice', 'Unit Price', 'Price', '단가']);
        const remarks = getFirstValue(row, ['remarks', 'Remark', '비고']);

        const quantity = parseNumberLike(qtyStr, 1);
        const unitPrice = parseNumberLike(unitPriceStr, 0);

        if (!itemCode && !itemName) return null;

        return {
          lineNumber: idx + 1,
          itemCode: itemCode || draft.header.productNo || `OCR-${idx + 1}`,
          itemName: itemName || draft.header.productName || 'Item',
          quantity,
          unitPrice,
          remarks: remarks || undefined,
        };
      })
      .filter((x): x is SalesOrderRequest['lines'][number] => x !== null);

    const lines: SalesOrderRequest['lines'] =
      mappedLines.length > 0
        ? mappedLines
        : [
            {
              lineNumber: 1,
              itemCode: draft.header.productNo || 'OCR-ITEM',
              itemName: draft.header.productName || 'OCR Item',
              quantity: 1,
              unitPrice: 0,
              remarks: draft.header.remarks || undefined,
            },
          ];

    const payload: SalesOrderRequest = {
      orderDate,
      customerCode,
      customerName,
      deliveryAddress: undefined,
      remarks: draft.header.remarks || undefined,
      lines,
    };

    createSalesOrderMutation.mutate(payload);
  };

  // 모드 변경 핸들러
  const handleModeChange = (newMode: OcrMode) => {
    setMode(newMode);
    // 모드 변경 시 이전 결과 초기화
    if (newMode === 'extract') resetAnalyze();
    else resetExtract();
  };

  // 파일 선택 핸들러
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.files && e.target.files[0]) {
      const file = e.target.files[0];
      setSelectedFile(file);
      setPreviewUrl(URL.createObjectURL(file)); // 미리보기 URL 생성
      // 파일 변경 시 이전 결과 초기화
      resetExtract();
      resetAnalyze();
    }
  };

  // 처리 시작 핸들러
  const handleProcess = () => {
    if (selectedFile) {
      if (mode === 'extract') {
        extractText(selectedFile);
      } else {
        analyzeDoc(selectedFile);
      }
    }
  };

  // 문서 분석 결과 렌더링 함수
  const renderAnalysisResult = (data: DocumentAnalysisResponseData) => {
    // 첫 번째 줄을 문서 제목으로 추정하여 추출
    const documentTitle = data.extractedText
      .split('\n')
      .find((line) => line.trim().length > 0)
      ?.trim();

    return (
      <div className='space-y-8 animate-in fade-in slide-in-from-bottom-4 duration-500'>
        {/* Sales Order Draft (editable) */}
        <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
          <div className='bg-gray-50 px-4 py-3 border-b border-gray-200 flex items-center justify-between gap-3'>
            <div>
              <h3 className='font-semibold text-gray-900'>Sales Order draft</h3>
              <p className='text-xs text-gray-500 mt-1'>Auto-filled from OCR. You can edit and save as draft.</p>
            </div>
            <div className='flex items-center gap-2 flex-wrap justify-end'>
              <Button
                type='button'
                variant='outline'
                size='sm'
                onClick={() => {
                  if (parsedDraft) {
                    setDraft(parsedDraft);
                    setHasUserEditedDraft(false);
                  }
                }}
                disabled={!parsedDraft}
              >
                Use parsed values
              </Button>
              <Button type='button' variant='outline' size='sm' onClick={loadDraft}>
                Load draft
              </Button>
              <Button type='button' variant='outline' size='sm' onClick={saveDraft} disabled={!draft}>
                Save draft
              </Button>
              <Button
                type='button'
                size='sm'
                onClick={createSalesOrderFromDraft}
                disabled={!draft || createSalesOrderMutation.isPending}
              >
                {createSalesOrderMutation.isPending ? 'Creating...' : 'Create Sales Order'}
              </Button>
              <Button type='button' variant='ghost' size='sm' onClick={clearDraft}>
                Clear
              </Button>
            </div>
          </div>

          <div className='p-4'>
            {!draft ? (
              <div className='text-sm text-gray-500 italic'>No draft data yet. Run Analyze, then click “Use parsed values”.</div>
            ) : (
              <div className='space-y-6'>
                <div className='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4'>
                <Input
                  label='Order No'
                  value={draft.header.orderNo}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, orderNo: e.target.value } });
                  }}
                />

                <Input
                  label='Date of Order'
                  value={draft.header.dateOfOrder}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, dateOfOrder: e.target.value } });
                  }}
                />

                <Input
                  label='Supplier Code'
                  value={draft.header.supplierCode}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, supplierCode: e.target.value } });
                  }}
                />

                <Input
                  label='Supplier Name'
                  value={draft.header.supplierName}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, supplierName: e.target.value } });
                  }}
                />

                <Input
                  label='Product No'
                  value={draft.header.productNo}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, productNo: e.target.value } });
                  }}
                />

                <Input
                  label='Product Name'
                  value={draft.header.productName}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, productName: e.target.value } });
                  }}
                />

                <Input
                  label='Option No'
                  value={draft.header.optionNo}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, optionNo: e.target.value } });
                  }}
                />

                <Input
                  label='Development No'
                  value={draft.header.developmentNo}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, developmentNo: e.target.value } });
                  }}
                />

                <Input
                  label='Product Dev Name'
                  value={draft.header.productDevName}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, productDevName: e.target.value } });
                  }}
                />

                <div className='w-full space-y-2'>
                  <label className='text-sm font-semibold text-gray-700'>Season</label>
                  <select
                    className='flex h-12 w-full rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                    value={draft.header.season}
                    onChange={(e) => {
                      setHasUserEditedDraft(true);
                      setDraft({ ...draft, header: { ...draft.header, season: e.target.value } });
                    }}
                  >
                    <option value=''>Select season</option>
                    <option value='1-2026'>1-2026</option>
                    <option value='2-2026'>2-2026</option>
                    <option value='3-2026'>3-2026</option>
                    <option value='4-2026'>4-2026</option>
                    <option value={draft.header.season}>{draft.header.season}</option>
                  </select>
                </div>

                <div className='w-full space-y-2'>
                  <label className='text-sm font-semibold text-gray-700'>Customs Customer Group</label>
                  <select
                    className='flex h-12 w-full rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                    value={draft.header.customsCustomerGroup}
                    onChange={(e) => {
                      setHasUserEditedDraft(true);
                      setDraft({ ...draft, header: { ...draft.header, customsCustomerGroup: e.target.value } });
                    }}
                  >
                    <option value=''>Select group</option>
                    <option value='Women'>Women</option>
                    <option value='Men'>Men</option>
                    <option value='Kids'>Kids</option>
                    <option value='Unisex'>Unisex</option>
                    <option value={draft.header.customsCustomerGroup}>{draft.header.customsCustomerGroup}</option>
                  </select>
                </div>

                <div className='w-full space-y-2'>
                  <label className='text-sm font-semibold text-gray-700'>Type of Construction</label>
                  <select
                    className='flex h-12 w-full rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                    value={draft.header.typeOfConstruction}
                    onChange={(e) => {
                      setHasUserEditedDraft(true);
                      setDraft({ ...draft, header: { ...draft.header, typeOfConstruction: e.target.value } });
                    }}
                  >
                    <option value=''>Select construction</option>
                    <option value='Woven'>Woven</option>
                    <option value='Knit'>Knit</option>
                    <option value='Other'>Other</option>
                    <option value={draft.header.typeOfConstruction}>{draft.header.typeOfConstruction}</option>
                  </select>
                </div>

                <Input
                  label='Product Type'
                  value={draft.header.productType}
                  onChange={(e) => {
                    setHasUserEditedDraft(true);
                    setDraft({ ...draft, header: { ...draft.header, productType: e.target.value } });
                  }}
                />

                <div className='w-full space-y-2 md:col-span-2 lg:col-span-3'>
                  <label className='text-sm font-semibold text-gray-700'>Product Description</label>
                  <textarea
                    className='w-full min-h-24 rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                    value={draft.header.productDescription}
                    onChange={(e) => {
                      setHasUserEditedDraft(true);
                      setDraft({ ...draft, header: { ...draft.header, productDescription: e.target.value } });
                    }}
                  />
                </div>

                <div className='w-full space-y-2 md:col-span-2 lg:col-span-3'>
                  <label className='text-sm font-semibold text-gray-700'>Remarks</label>
                  <textarea
                    className='w-full min-h-24 rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                    value={draft.header.remarks}
                    onChange={(e) => {
                      setHasUserEditedDraft(true);
                      setDraft({ ...draft, header: { ...draft.header, remarks: e.target.value } });
                    }}
                  />
                </div>
                </div>

                <div className='grid grid-cols-1 xl:grid-cols-2 gap-6'>
                  <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
                    <div className='bg-gray-50 px-4 py-3 border-b border-gray-200 flex items-center justify-between'>
                      <div>
                        <h4 className='font-semibold text-gray-900'>Sales Order Detail (editable)</h4>
                        <p className='text-xs text-gray-500 mt-1'>Based on size/colour breakdown tables (if detected).</p>
                      </div>
                      <Button
                        type='button'
                        variant='outline'
                        size='sm'
                        onClick={() => {
                          setHasUserEditedDraft(true);
                          setDraft({ ...draft, salesOrderDetails: [...draft.salesOrderDetails, {}] });
                        }}
                      >
                        Add row
                      </Button>
                    </div>
                    <div className='p-4 overflow-auto'>
                      {draft.salesOrderDetails.length === 0 ? (
                        <div className='text-sm text-gray-500 italic'>No detail rows detected.</div>
                      ) : (
                        <div className='min-w-full'>
                          {(() => {
                            const cols = buildColumns(draft.salesOrderDetails);
                            return (
                              <table className='min-w-full border border-gray-200 rounded-lg overflow-hidden'>
                                <thead className='bg-gray-50'>
                                  <tr>
                                    {cols.map((c) => (
                                      <th key={c} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>
                                        {c}
                                      </th>
                                    ))}
                                    <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>
                                      Actions
                                    </th>
                                  </tr>
                                </thead>
                                <tbody className='bg-white'>
                                  {draft.salesOrderDetails.map((row, rowIdx) => (
                                    <tr key={rowIdx} className='border-b border-gray-100 last:border-b-0'>
                                      {cols.map((c) => (
                                        <td key={c} className='px-2 py-2 align-top'>
                                          <input
                                            className='w-full h-10 rounded-lg border border-gray-200 bg-gray-50/50 px-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                                            value={row[c] ?? ''}
                                            onChange={(e) => {
                                              setHasUserEditedDraft(true);
                                              const next = [...draft.salesOrderDetails];
                                              next[rowIdx] = { ...next[rowIdx], [c]: e.target.value };
                                              setDraft({ ...draft, salesOrderDetails: next });
                                            }}
                                          />
                                        </td>
                                      ))}
                                      <td className='px-2 py-2 text-right'>
                                        <button
                                          type='button'
                                          className='text-sm text-red-600 hover:text-red-700'
                                          onClick={() => {
                                            setHasUserEditedDraft(true);
                                            setDraft({
                                              ...draft,
                                              salesOrderDetails: draft.salesOrderDetails.filter((_, i) => i !== rowIdx),
                                            });
                                          }}
                                        >
                                          Remove
                                        </button>
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            );
                          })()}
                        </div>
                      )}
                    </div>
                  </div>

                  <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
                    <div className='bg-gray-50 px-4 py-3 border-b border-gray-200 flex items-center justify-between'>
                      <div>
                        <h4 className='font-semibold text-gray-900'>BoM (editable)</h4>
                        <p className='text-xs text-gray-500 mt-1'>Based on Bill of Material tables (if detected).</p>
                      </div>
                      <Button
                        type='button'
                        variant='outline'
                        size='sm'
                        onClick={() => {
                          setHasUserEditedDraft(true);
                          setDraft({ ...draft, bomItems: [...draft.bomItems, {}] });
                        }}
                      >
                        Add row
                      </Button>
                    </div>
                    <div className='p-4 overflow-auto'>
                      {draft.bomItems.length === 0 ? (
                        <div className='text-sm text-gray-500 italic'>No BoM items detected.</div>
                      ) : (
                        <div className='min-w-full'>
                          {(() => {
                            const cols = buildColumns(draft.bomItems);
                            return (
                              <table className='min-w-full border border-gray-200 rounded-lg overflow-hidden'>
                                <thead className='bg-gray-50'>
                                  <tr>
                                    {cols.map((c) => (
                                      <th key={c} className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>
                                        {c}
                                      </th>
                                    ))}
                                    <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>
                                      Actions
                                    </th>
                                  </tr>
                                </thead>
                                <tbody className='bg-white'>
                                  {draft.bomItems.map((row, rowIdx) => (
                                    <tr key={rowIdx} className='border-b border-gray-100 last:border-b-0'>
                                      {cols.map((c) => (
                                        <td key={c} className='px-2 py-2 align-top'>
                                          <input
                                            className='w-full h-10 rounded-lg border border-gray-200 bg-gray-50/50 px-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                                            value={row[c] ?? ''}
                                            onChange={(e) => {
                                              setHasUserEditedDraft(true);
                                              const next = [...draft.bomItems];
                                              next[rowIdx] = { ...next[rowIdx], [c]: e.target.value };
                                              setDraft({ ...draft, bomItems: next });
                                            }}
                                          />
                                        </td>
                                      ))}
                                      <td className='px-2 py-2 text-right'>
                                        <button
                                          type='button'
                                          className='text-sm text-red-600 hover:text-red-700'
                                          onClick={() => {
                                            setHasUserEditedDraft(true);
                                            setDraft({ ...draft, bomItems: draft.bomItems.filter((_, i) => i !== rowIdx) });
                                          }}
                                        >
                                          Remove
                                        </button>
                                      </td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            );
                          })()}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>

        {/* Parsed sections (Sales Order / Details / BoM) */}
        <div className='space-y-6'>
          <h3 className='font-bold text-lg text-gray-900'>Parsed result</h3>

          {/* Sales Order header */}
          <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
            <div className='bg-gray-50 px-4 py-3 border-b border-gray-200'>
              <h4 className='font-semibold text-gray-900'>Sales Order (Header)</h4>
            </div>
            <div className='p-4'>
              {data.classified && Object.keys(data.classified.salesOrderHeader || {}).length > 0 ? (
                <div className='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4'>
                  {Object.entries(data.classified.salesOrderHeader).map(([k, v]) => (
                    <div key={k} className='space-y-1'>
                      <div className='text-xs text-gray-500'>{k}</div>
                      <div className='px-3 py-2 rounded-lg border border-gray-200 bg-gray-50 text-sm text-gray-900 break-words'>
                        {v}
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <div className='text-sm text-gray-500 italic'>No header fields detected.</div>
              )}
            </div>
          </div>

          {/* Sales Order detail */}
          <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
            <div className='bg-gray-50 px-4 py-3 border-b border-gray-200'>
              <h4 className='font-semibold text-gray-900'>Sales Order Detail</h4>
            </div>
            <div className='p-4'>
              {data.classified && data.classified.salesOrderDetails && data.classified.salesOrderDetails.length > 0 ? (
                <pre className='text-xs font-mono bg-gray-50 border border-gray-200 rounded-lg p-3 overflow-auto max-h-80'>
                  {JSON.stringify(data.classified.salesOrderDetails, null, 2)}
                </pre>
              ) : (
                <div className='text-sm text-gray-500 italic'>No detail rows detected.</div>
              )}
            </div>
          </div>

          {/* BoM */}
          <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
            <div className='bg-gray-50 px-4 py-3 border-b border-gray-200'>
              <h4 className='font-semibold text-gray-900'>BoM</h4>
            </div>
            <div className='p-4'>
              {data.classified && data.classified.bomItems && data.classified.bomItems.length > 0 ? (
                <pre className='text-xs font-mono bg-gray-50 border border-gray-200 rounded-lg p-3 overflow-auto max-h-80'>
                  {JSON.stringify(data.classified.bomItems, null, 2)}
                </pre>
              ) : (
                <div className='text-sm text-gray-500 italic'>No BoM items detected.</div>
              )}
            </div>
          </div>
        </div>

        {/* Raw JSON */}
        <div className='bg-gray-50 p-4 rounded-lg border border-gray-200'>
          <details className='group'>
            <summary className='flex justify-between items-center font-medium cursor-pointer list-none text-sm text-gray-700'>
              <span>View raw JSON</span>
              <span className='transition group-open:rotate-180'>
                <svg
                  fill='none'
                  height='24'
                  shapeRendering='geometricPrecision'
                  stroke='currentColor'
                  strokeLinecap='round'
                  strokeLinejoin='round'
                  strokeWidth='1.5'
                  viewBox='0 0 24 24'
                  width='24'
                >
                  <path d='M6 9l6 6 6-6'></path>
                </svg>
              </span>
            </summary>
            <div className='text-neutral-600 mt-3 group-open:animate-fadeIn whitespace-pre-wrap text-xs font-mono p-2 bg-white rounded border border-gray-200 overflow-auto max-h-96'>
              {JSON.stringify(data, null, 2)}
            </div>
          </details>
        </div>

        {/* 문서 제목 (추정) */}
        {documentTitle && (
          <div className='text-center pb-6 border-b border-gray-100'>
            <h2 className='text-2xl font-bold text-gray-800 break-words'>{documentTitle}</h2>
            <p className='text-sm text-gray-400 mt-2'>Document title (estimated)</p>
          </div>
        )}

        {/* 테이블 섹션 */}
        <div className='space-y-4'>
          <h3 className='font-bold text-lg text-gray-900 flex items-center'>
            <TableIcon className='w-5 h-5 mr-2' />
            Extracted tables ({data.tables.length})
          </h3>

          {data.tables.length > 0 ? (
            <div className='grid grid-cols-1 xl:grid-cols-2 gap-6'>
              {data.tables.map((table: TableDto, idx: number) => (
                <div key={idx} className='bg-white rounded-lg border border-gray-200 overflow-hidden shadow-sm'>
                  <div className='bg-gray-50 px-4 py-2 border-b border-gray-100 text-xs font-medium text-gray-500 uppercase tracking-wider'>
                    Table {idx + 1}
                  </div>
                  <div className='overflow-x-auto'>
                    <table className='min-w-full divide-y divide-gray-200'>
                      <tbody className='bg-white divide-y divide-gray-200'>
                        {table.rows.map((row, rIdx) => (
                          <tr key={rIdx} className={rIdx === 0 ? 'bg-gray-50/50' : ''}>
                            {row.map((cell, cIdx) => (
                              <td
                                key={cIdx}
                                className={`px-4 py-3 text-sm text-gray-700 whitespace-pre-wrap border-r border-gray-100 last:border-r-0 ${
                                  rIdx === 0 ? 'font-semibold text-gray-900' : ''
                                }`}
                              >
                                {cell}
                              </td>
                            ))}
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className='bg-gray-50 rounded-lg p-8 text-center text-gray-500 border border-gray-200 border-dashed'>
              No tables detected.
            </div>
          )}
        </div>

        {/* 키-값 쌍 섹션 (Form Data) */}
        <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
          <div className='bg-gray-50 px-4 py-3 border-b border-gray-200 flex justify-between items-center'>
            <h3 className='font-semibold text-gray-900'>Key-value details (Key-Value Pairs)</h3>
            <span className='text-xs text-gray-500'>Confidence scores shown</span>
          </div>
          <div className='max-h-96 overflow-y-auto p-4'>
            {data.keyValuePairs.length > 0 ? (
              <div className='grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4'>
                {data.keyValuePairs.map((kv, idx) => (
                  <div
                    key={idx}
                    className='p-3 rounded-lg border border-gray-100 hover:bg-gray-50 transition-colors flex justify-between items-start text-sm'
                  >
                    <div className='flex-1 pr-2'>
                      <span className='text-gray-500 text-xs block mb-1'>Key</span>
                      <span className='text-gray-700 font-medium break-words'>{kv.key}</span>
                    </div>
                    <div className='flex-1 text-right pl-2 border-l border-gray-100'>
                      <span className='text-gray-500 text-xs block mb-1'>Value</span>
                      <span className='text-gray-900 break-words'>{kv.value}</span>
                      <div className='mt-1 text-[10px] text-gray-400'>{Math.round(kv.valueConfidence)}%</div>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className='text-center text-gray-500 italic py-4'>No key-value pairs detected.</div>
            )}
          </div>
        </div>

        {/* 전체 텍스트 보기 토글 */}
        <div className='bg-gray-50 p-4 rounded-lg border border-gray-200'>
          <details className='group'>
            <summary className='flex justify-between items-center font-medium cursor-pointer list-none text-sm text-gray-700'>
              <span>View full text</span>
              <span className='transition group-open:rotate-180'>
                <svg
                  fill='none'
                  height='24'
                  shapeRendering='geometricPrecision'
                  stroke='currentColor'
                  strokeLinecap='round'
                  strokeLinejoin='round'
                  strokeWidth='1.5'
                  viewBox='0 0 24 24'
                  width='24'
                >
                  <path d='M6 9l6 6 6-6'></path>
                </svg>
              </span>
            </summary>
            <div className='text-neutral-600 mt-3 group-open:animate-fadeIn whitespace-pre-wrap text-xs font-mono p-2 bg-white rounded border border-gray-200'>
              {data.extractedText}
            </div>
          </details>
        </div>
      </div>
    );
  };

  return (
    <div className='space-y-8 max-w-screen-2xl mx-auto pb-20'>
      <div className='flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4'>
        <h1 className='text-2xl font-bold text-gray-900'>OCR Document Analysis</h1>

        {/* 모드 선택 탭 */}
        <div className='bg-gray-100 p-1 rounded-lg flex'>
          <button
            onClick={() => handleModeChange('extract')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all ${
              mode === 'extract' ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-900'
            }`}
          >
            <span className='flex items-center'>
              <Type className='w-4 h-4 mr-2' />
              Text extraction
            </span>
          </button>
          <button
            onClick={() => handleModeChange('analyze')}
            className={`px-4 py-2 rounded-md text-sm font-medium transition-all ${
              mode === 'analyze' ? 'bg-white text-indigo-600 shadow-sm' : 'text-gray-500 hover:text-gray-900'
            }`}
          >
            <span className='flex items-center'>
              <TableIcon className='w-4 h-4 mr-2' />
              Table/document analysis
            </span>
          </button>
        </div>
      </div>

      <div className='flex flex-col gap-8'>
        {/* 상단: 파일 업로드 및 미리보기 섹션 */}
        <div className='bg-white p-6 rounded-lg shadow-sm border border-gray-200'>
          <h2 className='text-lg font-semibold text-gray-900 mb-4 flex items-center'>
            <Upload className='w-5 h-5 mr-2' />
            {mode === 'extract' ? 'Upload file (text extraction)' : 'Upload file (document analysis)'}
          </h2>

          <div className={`grid gap-6 ${selectedFile ? 'grid-cols-1 lg:grid-cols-2' : 'grid-cols-1'}`}>
            {/* 업로드 영역 */}
            <div className='flex flex-col'>
              <div className='flex-1 flex flex-col items-center justify-center border-2 border-dashed border-gray-300 rounded-lg p-10 hover:bg-gray-50 transition-colors relative bg-gray-50/50 min-h-96'>
                <input
                  type='file'
                  accept='image/*,application/pdf'
                  onChange={handleFileChange}
                  className='absolute inset-0 w-full h-full opacity-0 cursor-pointer'
                />
                {!selectedFile ? (
                  <div className='text-center text-gray-500'>
                    <FileText className='w-12 h-12 mx-auto mb-3 text-gray-400' />
                    <p className='text-sm font-medium'>Drag a file here, or click to select</p>
                    <p className='text-xs mt-1 text-gray-400'>PNG, JPG, PDF (max 10MB)</p>
                  </div>
                ) : (
                  <div className='text-center'>
                    <p className='text-sm font-medium text-gray-900 mb-2'>Selected file</p>
                    <p className='text-xs text-gray-500 bg-white px-3 py-1 rounded border border-gray-200 inline-block'>
                      {selectedFile.name}
                    </p>
                    <p className='text-xs text-gray-400 mt-2'>Click to choose a different file</p>
                  </div>
                )}
              </div>
            </div>

            {/* 미리보기 영역 (파일 선택 시 표시) */}
            {selectedFile && previewUrl && (
              <div className='flex flex-col items-center justify-center bg-gray-900/5 rounded-lg border border-gray-200 p-4 min-h-96'>
                <img
                  src={previewUrl}
                  alt='Preview'
                  className='max-h-96 max-w-full object-contain rounded-md shadow-sm'
                />
              </div>
            )}
          </div>

          {/* 처리 버튼 */}
          <div className='mt-6 flex justify-end'>
            <Button
              onClick={handleProcess}
              disabled={!selectedFile || isPending}
              className={`w-full sm:w-auto h-12 px-8 text-base ${mode === 'analyze' ? 'bg-indigo-600 hover:bg-indigo-700' : ''}`}
            >
              {isPending ? (
                <>
                  <Loader2 className='w-5 h-5 mr-2 animate-spin' />
                  {mode === 'extract' ? 'Extracting text...' : 'Analyzing document...'}
                </>
              ) : (
                <>{mode === 'extract' ? 'Extract text' : 'Analyze tables and data'}</>
              )}
            </Button>
          </div>
        </div>

        {/* 에러 메시지 표시 */}
        {(extractError || analyzeError) && (
          <div className='bg-red-50 border border-red-200 rounded-lg p-4 flex items-start animate-in fade-in slide-in-from-top-2'>
            <AlertCircle className='w-5 h-5 text-red-500 mr-2 flex-shrink-0 mt-0.5' />
            <div>
              <h3 className='text-sm font-medium text-red-800'>Request failed</h3>
              <p className='text-sm text-red-700 mt-1'>
                {(extractError as Error)?.message ||
                  (analyzeError as Error)?.message ||
                  'An unknown error occurred.'}
              </p>
            </div>
          </div>
        )}

        {/* 하단: 결과 표시 섹션 */}
        <div>
          {/* Analyze 모드 결과 */}
          {mode === 'analyze' && (
            <div className={`transition-all duration-500 ${analyzeResult ? 'opacity-100' : 'opacity-0'}`}>
              {analyzeResult?.data && (
                <div className='bg-white p-8 rounded-lg shadow-sm border border-gray-200'>
                  <div className='flex items-center justify-between mb-6'>
                    <h2 className='text-xl font-bold text-gray-900 flex items-center'>
                      <TableIcon className='w-6 h-6 mr-3 text-indigo-600' />
                      Results
                    </h2>
                    <span className='text-sm font-medium text-indigo-600 bg-indigo-50 px-3 py-1 rounded-full border border-indigo-100'>
                      Avg. confidence: {analyzeResult.data.averageConfidence.toFixed(1)}%
                    </span>
                  </div>

                  {renderAnalysisResult(analyzeResult.data)}
                </div>
              )}
            </div>
          )}

          {/* Extract 모드 결과 */}
          {mode === 'extract' && (extractResult || isExtractPending) && (
            <div className='bg-white p-6 rounded-lg shadow-sm border border-gray-200 min-h-150'>
              <h2 className='text-lg font-semibold text-gray-900 mb-4 flex items-center'>
                <FileText className='w-5 h-5 mr-2' />
                Extracted text
              </h2>

              {isPending && (
                <div className='flex flex-col items-center justify-center h-64 text-gray-500'>
                  <Loader2 className='w-8 h-8 animate-spin mb-4 text-indigo-500' />
                  <p>Processing text...</p>
                </div>
              )}

              {extractResult && extractResult.success && (
                <div className='space-y-6 animate-in fade-in slide-in-from-bottom-4 duration-500'>
                  <div className='bg-indigo-50 p-4 rounded-lg flex items-center justify-between'>
                    <span className='text-sm font-medium text-indigo-900'>Avg. confidence</span>
                    <span className='text-lg font-bold text-indigo-600'>
                      {extractResult.data.averageConfidence.toFixed(1)}%
                    </span>
                  </div>

                  <div>
                    <h3 className='text-sm font-medium text-gray-700 mb-2'>Full text</h3>
                    <div className='bg-gray-50 p-4 rounded-lg text-sm text-gray-800 whitespace-pre-wrap border border-gray-100 max-h-96 overflow-y-auto font-mono'>
                      {extractResult.data.extractedText}
                    </div>
                  </div>

                  {/* 블록 상세 보기 */}
                  <details className='group'>
                    <summary className='text-sm font-medium text-gray-700 cursor-pointer mb-2 list-none flex items-center'>
                      <span>Detected blocks ({extractResult.data.blocks.length}) - details</span>
                      <span className='ml-2 transition group-open:rotate-180 text-gray-400'>▼</span>
                    </summary>

                    <div className='border border-gray-200 rounded-lg overflow-hidden mt-2'>
                      <div className='max-h-60 overflow-y-auto divide-y divide-gray-100'>
                        {extractResult.data.blocks.map((block, index) => (
                          <div
                            key={index}
                            className='p-3 hover:bg-gray-50 transition-colors flex justify-between items-start'
                          >
                            <p className='text-sm text-gray-900 flex-1 mr-4'>{block.text}</p>
                            <div className='flex flex-col items-end'>
                              <span className='text-xs bg-gray-100 px-2 py-0.5 rounded text-gray-600 font-medium'>
                                {block.blockType}
                              </span>
                              <span
                                className={`text-xs mt-1 ${
                                  block.confidence > 90
                                    ? 'text-green-600'
                                    : block.confidence > 70
                                      ? 'text-yellow-600'
                                      : 'text-red-600'
                                }`}
                              >
                                {block.confidence.toFixed(1)}%
                              </span>
                            </div>
                          </div>
                        ))}
                      </div>
                    </div>
                  </details>
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

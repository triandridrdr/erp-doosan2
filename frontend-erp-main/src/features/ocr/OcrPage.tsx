/**
 * @file features/ocr/OcrPage.tsx
 * @description OCR 기능을 제공하는 페이지 컴포넌트입니다.
 * 이미지 파일을 업로드하여 단순 텍스트 추출 또는 상세 문서 분석(테이블, Key-Value)을 수행합니다.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { AlertCircle, FileText, Loader2, Table as TableIcon, Type, Upload } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';

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

  const preferredOrder = [
    'destinationCountry',
    'sectionType',
    'type',
    'breakdownIncluded',
    'productNo',
    'productName',
    'productDescription',
    'season',
    'supplierCode',
    'supplierName',
    'colourName',
    'hmColourCode',
    'articleNo',
    'ptArticleNumber',
    'optionNo',
    'description',
    'finishedGood',
    'style',
    'unitPrice',
    'XS (Assortment)',
    'S (Assortment)',
    'M (Assortment)',
    'L (Assortment)',
    'XL (Assortment)',
    'quantity (Assortment)',
    'noOfAst (Assortment)',
    'totPcs (Assortment)',
    'XS (Solid)',
    'S (Solid)',
    'M (Solid)',
    'L (Solid)',
    'XL (Solid)',
    'quantity (Solid)',
    'noOfAst (Solid)',
    'totPcs (Solid)',
    'XS (Total)',
    'S (Total)',
    'M (Total)',
    'L (Total)',
    'XL (Total)',
    'quantity (Total)',
    'noOfAst (Total)',
    'totPcs (Total)',
    'XS',
    'S',
    'M',
    'L',
    'XL',
    'quantity',
    'noOfAst',
    'totPcs',
  ];

  const ordered: string[] = [];
  for (const k of preferredOrder) {
    if (cols.has(k)) ordered.push(k);
  }

  const remaining = Array.from(cols)
    .filter((k) => !preferredOrder.includes(k))
    .sort((a, b) => a.localeCompare(b));

  return [...ordered, ...remaining];
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
  const [selectedFiles, setSelectedFiles] = useState<File[]>([]);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null); // 이미지 미리보기 URL

  const isBatchAnalyzingRef = useRef(false);

  const [draft, setDraft] = useState<GarmentSalesOrderDraft | null>(null);
  const [hasUserEditedDraft, setHasUserEditedDraft] = useState(false);

  const [analyzeData, setAnalyzeData] = useState<DocumentAnalysisResponseData | null>(null);
  const [analyzeAvgConfidence, setAnalyzeAvgConfidence] = useState<number | null>(null);

  type AnalyzeTraceEntry = {
    fileName: string;
    fileSize: number;
    fileType: string;
    startedAt: string;
    finishedAt?: string;
    status: 'sending' | 'success' | 'error';
    errorMessage?: string;
    roleByName: 'supplementary' | 'size_breakdown' | 'unknown';
    roleByContent?: 'supplementary' | 'size_breakdown' | 'unknown';
    roleFinal?: 'supplementary' | 'size_breakdown' | 'unknown';
    extractedTextLength?: number;
    tablesCount?: number;
    keyValuePairsCount?: number;
    classifiedDetailsCount?: number;
    classifiedBomCount?: number;
    fallbackDetailKeysCount?: number;
    assortmentDetected?: boolean;
  };

  const [analyzeTrace, setAnalyzeTrace] = useState<AnalyzeTraceEntry[]>([]);

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
    mutateAsync: analyzeDocAsync,
    isPending: isAnalyzePending,
    error: analyzeError,
    reset: resetAnalyze,
  } = useMutation({
    mutationFn: ocrApi.analyze,
    onSuccess: (res) => {
      if (isBatchAnalyzingRef.current) return;
      setAnalyzeData(res.data);
      setAnalyzeAvgConfidence(res.data.averageConfidence);
    },
  });

  const isPending = isExtractPending || isAnalyzePending;

  const analyzedData = analyzeData;

  function detectDocRole(fileName: string): 'supplementary' | 'size_breakdown' | 'unknown' {
    const n = fileName.toLowerCase();
    if (n.includes('supplementary') || n.includes('supplement')) return 'supplementary';
    if (n.includes('sizepercolour') || n.includes('size per colour') || n.includes('sizepercolor') || n.includes('breakdown')) {
      return 'size_breakdown';
    }
    return 'unknown';
  }

  function detectDocRoleFromContent(d: DocumentAnalysisResponseData): 'supplementary' | 'size_breakdown' | 'unknown' {
    const text = (d.extractedText ?? '').toLowerCase();

    const hasSizeColour =
      text.includes('size / colour breakdown') ||
      text.includes('size/colour breakdown') ||
      text.includes('size / color breakdown') ||
      text.includes('size/color breakdown') ||
      text.includes('assortment') ||
      text.includes('h&m colour code') ||
      text.includes('hm colour code') ||
      text.includes('colour name') ||
      text.includes('pt article number');

    if (hasSizeColour) return 'size_breakdown';

    const fields = Object.keys(d.formFields ?? {}).join(' ').toLowerCase();
    const hasSizeColourFields =
      fields.includes('h&m') ||
      fields.includes('hm') ||
      fields.includes('colour') ||
      fields.includes('color') ||
      fields.includes('assortment') ||
      fields.includes('article');

    if (hasSizeColourFields) return 'size_breakdown';

    return 'unknown';
  }

  function normalizeDetailRow(row: Record<string, string>): Record<string, string> {
    const out: Record<string, string> = {};
    const lowerEntries = Object.entries(row).map(([k, v]) => [k.trim().toLowerCase(), v] as const);

    const pick = (aliases: string[]) => {
      for (const a of aliases) {
        const found = lowerEntries.find(([k]) => k === a);
        if (found && typeof found[1] === 'string' && found[1].trim()) return found[1].trim();
      }
      return '';
    };

    const country = pick(['country', 'destination country', 'country of destination', 'destination', 'tujuan negara', '국가']);
    const articleNo = pick(['article no', 'article number', 'article', 'article_no', 'article no.']);
    const hmColourCode = pick(['h&m colour code', 'hm colour code', 'h&m color code', 'hm color code', 'colour code', 'color code']);
    const colourName = pick(['colour name', 'color name', 'colour', 'color']);
    const ptArticleNumber = pick(['pt article number', 'pt article no', 'pt article no.', 'pt article', 'pt article #']);
    const optionNo = pick(['option no', 'option number', 'option', 'option_no', 'option no.']);
    const description = pick(['description', 'desc', 'item description', 'product description']);
    const finishedGood = pick(['finished good', 'finishedgoods', 'fg', 'finished_good', 'finishedgood']);
    const style = pick(['style', 'style no', 'styleno', 'style_no']);
    const quantity = pick(['quantity', 'qty', 'q\'ty', 'total qty', 'total quantity', '수량']);
    const destinationCountry = pick(['destination country', 'country of destination', 'destination', 'tujuan negara', '국가']);
    const unitPrice = pick(['unit price', 'unitprice', 'u/price', 'price', '단가']);

    const sectionType = pick([
      'sectiontype',
      'section type',
      'type',
      'breakdown group',
      'breakdowngroup',
      'pack type',
      'packtype',
    ]);
    const noOfAst = pick(['noofast', 'no of ast', 'no of asst', 'no of assortment', 'no. of ast', 'no. of asst']);
    const totPcs = pick(['totpcs', 'tot pcs', 'total pcs', 'tot pieces', 'total pieces']);

    const sizeXS = pick(['xs', 'xs (xs)', 'xs (xs)*', 'xsmall', 'x-small']);
    const sizeS = pick(['s', 's (s)', 's (s)*', 'small']);
    const sizeM = pick(['m', 'm (m)', 'm (m)*', 'medium']);
    const sizeL = pick(['l', 'l (l)', 'l (l)*', 'large']);
    const sizeXL = pick(['xl', 'xl (xl)', 'xl (xl)*', 'xlarge', 'x-large']);

    if (country) out.country = country;
    if (articleNo) out.articleNo = articleNo;
    if (hmColourCode) out.hmColourCode = hmColourCode;
    if (colourName) out.colourName = colourName;
    if (ptArticleNumber) out.ptArticleNumber = ptArticleNumber;
    if (optionNo) out.optionNo = optionNo;
    if (description) out.description = description;
    if (finishedGood) out.finishedGood = finishedGood;
    if (style) out.style = style;
    if (quantity) out.quantity = quantity;
    if (destinationCountry) out.destinationCountry = destinationCountry;
    if (unitPrice) out.unitPrice = unitPrice;

    if (sectionType) {
      const s = sectionType.trim().toLowerCase();
      if (s === 'assortment') out.sectionType = 'Assortment';
      else if (s === 'solid') out.sectionType = 'Solid';
      else if (s === 'total') out.sectionType = 'Total';
      else out.sectionType = sectionType;
    }
    if (noOfAst) out.noOfAst = noOfAst;
    if (totPcs) out.totPcs = totPcs;

    if (sizeXS) out.XS = sizeXS;
    if (sizeS) out.S = sizeS;
    if (sizeM) out.M = sizeM;
    if (sizeL) out.L = sizeL;
    if (sizeXL) out.XL = sizeXL;

    for (const [k, v] of Object.entries(row)) {
      if (typeof v !== 'string') continue;
      if (out[k] !== undefined) continue;
      if (Object.prototype.hasOwnProperty.call(out, k)) continue;
      out[k] = v;
    }

    return out;
  }

  function parseDestinationCountryFromText(extractedText: string): string {
    const lines = extractedText
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);

    const isCountryLine = (l: string) => {
      if (!l) return false;
      const upper = l.toUpperCase();
      if (upper === 'XS (XS)' || upper === 'XS (XS)*') return false;
      if (upper === 'S (S)' || upper === 'S (S)*') return false;
      if (upper === 'M (M)' || upper === 'M (M)*') return false;
      if (upper === 'L (L)' || upper === 'L (L)*') return false;
      if (upper === 'XL (XL)' || upper === 'XL (XL)*') return false;
      return /[A-Za-z]{3,}.*\b[A-Z]{2}\b\s*\([A-Za-z0-9\-]+\)/.test(l);
    };

    const idx = lines.findIndex((l) => l.toLowerCase().includes('size / colour breakdown') || l.toLowerCase().includes('size/colour breakdown'));
    if (idx >= 0) {
      const next = lines[idx + 1];
      if (next && isCountryLine(next)) {
        return next;
      }
    }

    const match = lines.find((l) => isCountryLine(l));
    return match ?? '';
  }

  function isLikelyCountryValue(v: string): boolean {
    if (!v) return false;
    const upper = v.toUpperCase().trim();
    if (upper === 'XS (XS)' || upper === 'XS (XS)*') return false;
    if (upper === 'S (S)' || upper === 'S (S)*') return false;
    if (upper === 'M (M)' || upper === 'M (M)*') return false;
    if (upper === 'L (L)' || upper === 'L (L)*') return false;
    if (upper === 'XL (XL)' || upper === 'XL (XL)*') return false;
    return /[A-Za-z]{3,}.*\b[A-Z]{2}\b\s*\([A-Za-z0-9\-]+\)/.test(v);
  }

  function pivotSalesOrderDetails(
    details: Array<Record<string, string>>,
    header: GarmentSalesOrderDraft['header'],
  ): Array<Record<string, string>> {
    const get = (r: Record<string, string>, k: string) => (typeof r[k] === 'string' ? r[k].trim() : '');

    const toNumber = (v: string) => {
      if (!v) return NaN;
      const cleaned = v.replace(/[\s,]/g, '');
      const n = Number(cleaned);
      return Number.isFinite(n) ? n : NaN;
    };

    const baseKeyOf = (r: Record<string, string>) => {
      const dc = get(r, 'destinationCountry') || get(r, 'country');
      const keyParts = [
        dc,
        get(r, 'colourName'),
        get(r, 'hmColourCode'),
        get(r, 'articleNo'),
        get(r, 'ptArticleNumber'),
        get(r, 'optionNo'),
        get(r, 'description'),
      ];
      return keyParts.map((x) => x || '').join('||');
    };

    const inferSectionTypesIfMissing = (rows: Array<Record<string, string>>): Array<Record<string, string>> => {
      const out = rows.map((r) => ({ ...r }));

      const groups = new Map<string, Array<{ idx: number; row: Record<string, string> }>>();
      for (let i = 0; i < out.length; i++) {
        const r = out[i];
        const hasSection = (get(r, 'sectionType') || '').trim();
        if (hasSection) continue;
        const k = baseKeyOf(r);
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k)!.push({ idx: i, row: r });
      }

      for (const [, items] of groups) {
        const assortment: Array<{ idx: number; q: number }> = [];
        const nonAssort: Array<{ idx: number; q: number }> = [];

        for (const it of items) {
          const r = it.row;
          const hasAst = !!get(r, 'noOfAst') || !!get(r, 'totPcs');
          const q = toNumber(get(r, 'quantity'));
          if (hasAst) assortment.push({ idx: it.idx, q });
          else nonAssort.push({ idx: it.idx, q });
        }

        for (const a of assortment) {
          out[a.idx].sectionType = 'Assortment';
        }

        if (nonAssort.length === 1) {
          out[nonAssort[0].idx].sectionType = 'Solid';
        } else if (nonAssort.length >= 2) {
          const sorted = [...nonAssort].sort((a, b) => {
            const an = Number.isFinite(a.q) ? a.q : -Infinity;
            const bn = Number.isFinite(b.q) ? b.q : -Infinity;
            return an - bn;
          });
          const max = sorted[sorted.length - 1];
          out[max.idx].sectionType = 'Total';
          for (let i = 0; i < sorted.length - 1; i++) {
            out[sorted[i].idx].sectionType = 'Solid';
          }
        }
      }

      return out;
    };

    const detailsWithSections = inferSectionTypesIfMissing(details);

    const hasAnySectionType = detailsWithSections.some((r) => {
      const v = get(r, 'sectionType').toLowerCase();
      return v === 'assortment' || v === 'solid' || v === 'total';
    });

    const alreadyPivoted = detailsWithSections.some((r) =>
      Object.keys(r).some((k) =>
        k === 'XS (Assortment)' ||
        k === 'S (Assortment)' ||
        k === 'M (Assortment)' ||
        k === 'L (Assortment)' ||
        k === 'XL (Assortment)' ||
        k === 'XS (Solid)' ||
        k === 'S (Solid)' ||
        k === 'M (Solid)' ||
        k === 'L (Solid)' ||
        k === 'XL (Solid)' ||
        k === 'quantity (Assortment)' ||
        k === 'quantity (Solid)' ||
        k === 'noOfAst (Assortment)' ||
        k === 'noOfAst (Solid)' ||
        k === 'totPcs (Assortment)' ||
        k === 'totPcs (Solid)' ||
        k === 'XS (Total)' ||
        k === 'S (Total)' ||
        k === 'M (Total)' ||
        k === 'L (Total)' ||
        k === 'XL (Total)' ||
        k === 'quantity (Total)' ||
        k === 'noOfAst (Total)' ||
        k === 'totPcs (Total)'
      )
    );

    if (alreadyPivoted) {
      return detailsWithSections.map((r) => {
        const row: Record<string, string> = { ...r };
        if (!row.productNo && header.productNo) row.productNo = header.productNo;
        if (!row.productName && header.productName) row.productName = header.productName;
        if (!row.productDescription && header.productDescription) row.productDescription = header.productDescription;
        if (!row.season && header.season) row.season = header.season;
        if (!row.supplierCode && header.supplierCode) row.supplierCode = header.supplierCode;
        if (!row.supplierName && header.supplierName) row.supplierName = header.supplierName;

        const st = (get(row, 'sectionType') || '').trim();
        if (st && !row.type) row.type = st;
        if (!st && !row.type) {
          const hasAst = !!get(row, 'noOfAst') || !!get(row, 'totPcs');
          if (hasAst) row.type = 'Assortment';
        }
        return row;
      });
    }

    if (!hasAnySectionType) {
      return detailsWithSections.map((r) => {
        const row: Record<string, string> = { ...r };
        if (!row.productNo && header.productNo) row.productNo = header.productNo;
        if (!row.productName && header.productName) row.productName = header.productName;
        if (!row.productDescription && header.productDescription) row.productDescription = header.productDescription;
        if (!row.season && header.season) row.season = header.season;
        if (!row.supplierCode && header.supplierCode) row.supplierCode = header.supplierCode;
        if (!row.supplierName && header.supplierName) row.supplierName = header.supplierName;
        const dc = (row.destinationCountry || row.country || '').trim();
        if (dc && !isLikelyCountryValue(dc)) {
          delete row.destinationCountry;
          delete row.country;
        }

        const st = (get(row, 'sectionType') || '').trim();
        if (st && !row.type) row.type = st;
        if (!st && !row.type) {
          const hasAst = !!get(row, 'noOfAst') || !!get(row, 'totPcs');
          if (hasAst) row.type = 'Assortment';
        }
        return row;
      });
    }

    const outByKey = new Map<string, Record<string, string>>();

    for (let i = 0; i < detailsWithSections.length; i++) {
      const r = detailsWithSections[i];
      const sectionTypeRaw = (get(r, 'sectionType') || '').toLowerCase();
      const section =
        sectionTypeRaw === 'solid' ? 'Solid' : sectionTypeRaw === 'assortment' ? 'Assortment' : sectionTypeRaw === 'total' ? 'Total' : '';

      let baseKey = baseKeyOf(r);
      if (!baseKey.replace(/\|/g, '').trim()) {
        const fallback = [get(r, 'articleNo'), get(r, 'hmColourCode'), get(r, 'ptArticleNumber'), get(r, 'colourName')]
          .filter(Boolean)
          .join('||');
        baseKey = fallback ? `__fallback__||${fallback}` : `__row__||${i}`;
      }
      if (!outByKey.has(baseKey)) {
        const dc = get(r, 'destinationCountry') || get(r, 'country');
        const row: Record<string, string> = {
          productNo: header.productNo || '',
          productName: header.productName || '',
          productDescription: header.productDescription || '',
          season: header.season || '',
          supplierCode: header.supplierCode || '',
          supplierName: header.supplierName || '',
        };

        if (isLikelyCountryValue(dc)) row.destinationCountry = dc;
        const colourName = get(r, 'colourName');
        if (colourName) row.colourName = colourName;
        const hmColourCode = get(r, 'hmColourCode');
        if (hmColourCode) row.hmColourCode = hmColourCode;
        const articleNo = get(r, 'articleNo');
        if (articleNo) row.articleNo = articleNo;
        const ptArticleNumber = get(r, 'ptArticleNumber');
        if (ptArticleNumber) row.ptArticleNumber = ptArticleNumber;
        const optionNo = get(r, 'optionNo');
        if (optionNo) row.optionNo = optionNo;
        const description = get(r, 'description');
        if (description) row.description = description;

        outByKey.set(baseKey, row);
      }

      const target = outByKey.get(baseKey)!;
      const sizes: Array<'XS' | 'S' | 'M' | 'L' | 'XL'> = ['XS', 'S', 'M', 'L', 'XL'];
      for (const s of sizes) {
        const v = get(r, s);
        if (!v) continue;
        const col = section ? `${s} (${section})` : s;
        target[col] = v;
      }
      const q = get(r, 'quantity');
      if (q) {
        const qCol = section ? `quantity (${section})` : 'quantity';
        target[qCol] = q;
      }

      const noOfAst = get(r, 'noOfAst');
      if (noOfAst) {
        const col = section ? `noOfAst (${section})` : 'noOfAst';
        target[col] = noOfAst;
      }

      const totPcs = get(r, 'totPcs');
      if (totPcs) {
        const col = section ? `totPcs (${section})` : 'totPcs';
        target[col] = totPcs;
      }
    }

    return Array.from(outByKey.values()).map((row) => {
      const hasA = Object.keys(row).some((k) => k.endsWith(' (Assortment)'));
      const hasS = Object.keys(row).some((k) => k.endsWith(' (Solid)'));
      const hasT = Object.keys(row).some((k) => k.endsWith(' (Total)'));
      const parts: string[] = [];
      if (hasA) parts.push('Assortment');
      if (hasS) parts.push('Solid');
      if (hasT) parts.push('Total');
      if (parts.length > 0) {
        row.breakdownIncluded = parts.join(', ');
        row.type = row.breakdownIncluded;
      }
      return row;
    });
  }

  function parseAssortmentFromText(extractedText: string): Record<string, string> {
    const lines = extractedText
      .split('\n')
      .map((l) => l.trim())
      .filter(Boolean);

    const idx = lines.findIndex((l) => l.toLowerCase() === 'assortment' || l.toLowerCase().startsWith('assortment'));
    if (idx < 0) return {};

    const out: Record<string, string> = {};

    for (let i = idx + 1; i < lines.length; i++) {
      const l = lines[i];
      if (/^(quantity:|no of|article no|h&m|colour name|description|size\s*\/\s*colour)/i.test(l)) break;

      const m = l.match(/^(XS|S|M|L|XL)\b.*?\s(\d+(?:\.\d+)?)$/i);
      if (m) {
        const size = m[1].toUpperCase();
        out[size] = m[2];
        continue;
      }

      const qty = l.match(/^quantity\s*:\s*(\d+(?:\.\d+)?)/i);
      if (qty) {
        out.quantity = qty[1];
        break;
      }
    }

    return out;
  }

  function buildDetailRowFromAnalysis(d: DocumentAnalysisResponseData): Record<string, string> {
    const mergedFields: Record<string, string> = {
      ...(d.formFields ?? {}),
    };

    for (const kv of d.keyValuePairs ?? []) {
      const k = (kv.key ?? '').toString().trim();
      const v = (kv.value ?? '').toString().trim();
      if (!k || !v) continue;
      if (mergedFields[k] === undefined) mergedFields[k] = v;
    }

    const row: Record<string, string> = {
      country: getFirstValue(mergedFields, ['Country', 'Destination Country', 'Country of Destination', 'Destination', 'Tujuan Negara', '국가']),
      articleNo: getFirstValue(mergedFields, ['Article No', 'Article No.', 'Article Number', 'Article']),
      hmColourCode: getFirstValue(mergedFields, ['H&M Colour Code', 'HM Colour Code', 'H&M Color Code', 'HM Color Code', 'Colour Code', 'Color Code']),
      colourName: getFirstValue(mergedFields, ['Colour Name', 'Color Name', 'Colour', 'Color']),
      ptArticleNumber: getFirstValue(mergedFields, ['PT Article Number', 'PT Article No', 'PT Article No.', 'PT Article']),
      optionNo: getFirstValue(mergedFields, ['Option No', 'Option No.', 'Option Number', 'Option']),
      description: getFirstValue(mergedFields, ['Description', 'Desc']),
      quantity: getFirstValue(mergedFields, ['Quantity', 'Qty', 'QTY', '수량']),
      unitPrice: getFirstValue(mergedFields, ['Unit Price', 'UnitPrice', 'Price', '단가']),
      style: getFirstValue(mergedFields, ['Style', 'Style No', 'Style No.']),
      finishedGood: getFirstValue(mergedFields, ['Finished Good', 'FG']),
    };

    const destinationCountry = getFirstValue(mergedFields, ['Destination Country', 'Country of Destination', 'Destination', 'Tujuan Negara', '국가']);
    const destinationCountryFromText = parseDestinationCountryFromText(d.extractedText ?? '');
    const resolvedDestinationCountry = (destinationCountry || destinationCountryFromText).trim();
    if (resolvedDestinationCountry) row.destinationCountry = resolvedDestinationCountry;

    const assortmentFromText = parseAssortmentFromText(d.extractedText ?? '');
    if (assortmentFromText.XS) row.XS = assortmentFromText.XS;
    if (assortmentFromText.S) row.S = assortmentFromText.S;
    if (assortmentFromText.M) row.M = assortmentFromText.M;
    if (assortmentFromText.L) row.L = assortmentFromText.L;
    if (assortmentFromText.XL) row.XL = assortmentFromText.XL;
    if (assortmentFromText.quantity && !row.quantity) row.quantity = assortmentFromText.quantity;

    return Object.fromEntries(Object.entries(row).filter(([, v]) => typeof v === 'string' && v.trim().length > 0));
  }

  async function analyzeMultipleFiles(files: File[]) {
    if (files.length === 0) return;

    isBatchAnalyzingRef.current = true;

    resetAnalyze();
    setAnalyzeData(null);
    setAnalyzeAvgConfidence(null);
    setHasUserEditedDraft(false);
    setAnalyzeTrace([]);

    let merged: GarmentSalesOrderDraft | null = null;
    let confidenceSum = 0;
    let confidenceCount = 0;
    let mergedExtractedText = '';

    try {
      for (const f of files) {
        const startedAt = new Date().toISOString();
        const roleByName = detectDocRole(f.name);

      setAnalyzeTrace((prev) => [
        ...prev,
        {
          fileName: f.name,
          fileSize: f.size,
          fileType: f.type,
          startedAt,
          status: 'sending',
          roleByName,
        },
      ]);

      console.log('[OCR][Analyze] sending file', { name: f.name, size: f.size, type: f.type, roleByName });

      try {
        const res = await analyzeDocAsync(f);
        const d = res.data;
        const roleByContent = roleByName === 'unknown' ? detectDocRoleFromContent(d) : roleByName;
        const roleFinal = roleByName === 'unknown' ? roleByContent : roleByName;

        const assortmentDetected = (d.extractedText ?? '').toLowerCase().includes('assortment');
        let fallbackDetailKeysCount: number | undefined = undefined;

        console.log('[OCR][Analyze] response summary', {
          name: f.name,
          avgConfidence: d.averageConfidence,
          extractedTextLength: (d.extractedText ?? '').length,
          tables: d.tables?.length ?? 0,
          keyValuePairs: d.keyValuePairs?.length ?? 0,
          classifiedDetails: d.classified?.salesOrderDetails?.length ?? 0,
          classifiedBom: d.classified?.bomItems?.length ?? 0,
          roleByContent,
          roleFinal,
        });

        confidenceSum += d.averageConfidence;
        confidenceCount += 1;
        mergedExtractedText += (mergedExtractedText ? '\n\n' : '') + d.extractedText;

        if (!merged) {
          merged = {
            header: {
              orderNo: '',
              dateOfOrder: '',
              supplierCode: '',
              supplierName: '',
              productNo: '',
              productName: '',
              productType: '',
              productDescription: '',
              season: '',
              optionNo: '',
              developmentNo: '',
              productDevName: '',
              customsCustomerGroup: '',
              typeOfConstruction: '',
              remarks: '',
            },
            salesOrderDetails: [],
            bomItems: [],
          };
        }

        const role = roleFinal;

      if (role === 'supplementary' || role === 'unknown') {
        const header = d.classified?.salesOrderHeader ?? {};
        merged.header = {
          ...merged.header,
          orderNo: header.orderNo ?? merged.header.orderNo,
          dateOfOrder: header.dateOfOrder ?? merged.header.dateOfOrder,
          supplierCode: header.supplierCode ?? merged.header.supplierCode,
          supplierName: header.supplierName ?? merged.header.supplierName,
          productNo: header.productNo ?? merged.header.productNo,
          productName: header.productName ?? merged.header.productName,
          productType: header.productType ?? merged.header.productType,
          productDescription: header.productDescription ?? merged.header.productDescription,
          season: header.season ?? merged.header.season,
          optionNo: header.optionNo ?? merged.header.optionNo,
          developmentNo: header.developmentNo ?? merged.header.developmentNo,
          productDevName: header.productDevName ?? merged.header.productDevName,
          customsCustomerGroup: header.customsCustomerGroup ?? merged.header.customsCustomerGroup,
          typeOfConstruction: header.typeOfConstruction ?? merged.header.typeOfConstruction,
        };
        merged.bomItems = d.classified?.bomItems ?? merged.bomItems;
      }

      if (role === 'size_breakdown') {
        const rawDetails = d.classified?.salesOrderDetails ?? [];
        const normalizedDetails = rawDetails.map(normalizeDetailRow);
        const destinationCountryFromText = parseDestinationCountryFromText(d.extractedText ?? '');

        const details = normalizedDetails.map((row) => {
          const dc = (row.destinationCountry || row.country || '').trim();
          if (dc && !isLikelyCountryValue(dc)) {
            const { destinationCountry, country, ...rest } = row;
            return rest;
          }
          if (dc) return row;
          if (!destinationCountryFromText) return row;
          if (!isLikelyCountryValue(destinationCountryFromText)) return row;
          return { ...row, destinationCountry: destinationCountryFromText };
        });

        if (details.length > 0) {
          const nextDetails = pivotSalesOrderDetails(details, merged.header);
          if (nextDetails.length > 0) {
            const prev = merged.salesOrderDetails ?? [];
            const seen = new Set(prev.map((r) => JSON.stringify(r)));
            const mergedUnique = [...prev];
            for (const r of nextDetails) {
              const key = JSON.stringify(r);
              if (seen.has(key)) continue;
              seen.add(key);
              mergedUnique.push(r);
            }
            merged.salesOrderDetails = mergedUnique;
          }
        } else {
          const fallbackRow = buildDetailRowFromAnalysis(d);
          fallbackDetailKeysCount = Object.keys(fallbackRow).length;
          if (fallbackDetailKeysCount > 0) {
            const nextDetails = pivotSalesOrderDetails([fallbackRow], merged.header);
            if (nextDetails.length > 0) {
              const prev = merged.salesOrderDetails ?? [];
              if (prev.length === 0) {
                merged.salesOrderDetails = nextDetails;
              }
            }
          }
        }
      }

        const finishedAt = new Date().toISOString();
        setAnalyzeTrace((prev) => {
          const next = [...prev];
          const idx = next.findIndex((x) => x.fileName === f.name && x.startedAt === startedAt);
          if (idx >= 0) {
            next[idx] = {
              ...next[idx],
              status: 'success',
              finishedAt,
              roleByContent,
              roleFinal,
              extractedTextLength: (d.extractedText ?? '').length,
              tablesCount: d.tables?.length ?? 0,
              keyValuePairsCount: d.keyValuePairs?.length ?? 0,
              classifiedDetailsCount: d.classified?.salesOrderDetails?.length ?? 0,
              classifiedBomCount: d.classified?.bomItems?.length ?? 0,
              fallbackDetailKeysCount,
              assortmentDetected,
            };
          }
          return next;
        });
      } catch (err) {
        const finishedAt = new Date().toISOString();
        const message = err instanceof Error ? err.message : String(err);
        console.error('[OCR][Analyze] error analyzing file', { name: f.name, message });
        setAnalyzeTrace((prev) => {
          const next = [...prev];
          const idx = next.findIndex((x) => x.fileName === f.name && x.startedAt === startedAt);
          if (idx >= 0) {
            next[idx] = {
              ...next[idx],
              status: 'error',
              finishedAt,
              errorMessage: message,
            };
          }
          return next;
        });
      }
    }

      if (merged) {
        setDraft(merged);
        setAnalyzeData({
          extractedText: mergedExtractedText,
          lines: [],
          tables: [],
          keyValuePairs: [],
          formFields: {},
          classified: {
            salesOrderHeader: { ...merged.header },
            salesOrderDetails: merged.salesOrderDetails,
            bomItems: merged.bomItems,
            unmappedFields: {},
          },
          averageConfidence: confidenceCount ? confidenceSum / confidenceCount : 0,
        });
        setAnalyzeAvgConfidence(confidenceCount ? confidenceSum / confidenceCount : 0);
      }
    } finally {
      isBatchAnalyzingRef.current = false;
    }
  }

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
    if (isBatchAnalyzingRef.current) return;
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
    if (newMode === 'extract') {
      resetAnalyze();
      setAnalyzeData(null);
      setAnalyzeAvgConfidence(null);
    } else {
      resetExtract();
    }
  };

  // 파일 선택 핸들러
  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (!e.target.files || e.target.files.length === 0) return;

    const files = Array.from(e.target.files);
    const first = files[0] ?? null;

    setSelectedFiles(files);
    setSelectedFile(first);
    if (first) setPreviewUrl(URL.createObjectURL(first));

    resetExtract();
    resetAnalyze();
    setAnalyzeData(null);
    setAnalyzeAvgConfidence(null);
  };

  // 처리 시작 핸들러
  const handleProcess = () => {
    if (mode === 'extract') {
      if (selectedFile) extractText(selectedFile);
      return;
    }

    const files = selectedFiles.length > 0 ? selectedFiles : selectedFile ? [selectedFile] : [];
    if (files.length === 0) return;
    void analyzeMultipleFiles(files);
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
                <div className='overflow-auto rounded-lg border border-gray-200'>
                  <table className='min-w-full'>
                    <thead className='bg-gray-50'>
                      <tr>
                        <th className='px-4 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Field</th>
                        <th className='px-4 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Value</th>
                        <th className='px-4 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Editable</th>
                      </tr>
                    </thead>
                    <tbody className='bg-white'>
                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>SO Number</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.orderNo}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, orderNo: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Date (SO)</td>
                        <td className='px-4 py-2'>
                          <Input
                            type='date'
                            value={draft.header.dateOfOrder}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, dateOfOrder: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Season</td>
                        <td className='px-4 py-2'>
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
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Buyer Code</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.supplierCode}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, supplierCode: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Supplier</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.supplierName}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, supplierName: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Article / Product No</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.productNo}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, productNo: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Product Name</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.productName}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, productName: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Product Type</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.productType}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, productType: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Customs Customer Group</td>
                        <td className='px-4 py-2'>
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
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Type of Construction</td>
                        <td className='px-4 py-2'>
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
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Option No</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.optionNo}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, optionNo: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Development No</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.developmentNo}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, developmentNo: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Product Dev Name</td>
                        <td className='px-4 py-2'>
                          <Input
                            value={draft.header.productDevName}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, productDevName: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr className='border-b border-gray-100'>
                        <td className='px-4 py-2 text-sm text-gray-700'>Product Description</td>
                        <td className='px-4 py-2'>
                          <textarea
                            className='w-full min-h-20 rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                            value={draft.header.productDescription}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, productDescription: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>

                      <tr>
                        <td className='px-4 py-2 text-sm text-gray-700'>Remarks</td>
                        <td className='px-4 py-2'>
                          <textarea
                            className='w-full min-h-20 rounded-xl border border-gray-200 bg-gray-50/50 px-3 py-2 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200'
                            value={draft.header.remarks}
                            onChange={(e) => {
                              setHasUserEditedDraft(true);
                              setDraft({ ...draft, header: { ...draft.header, remarks: e.target.value } });
                            }}
                          />
                        </td>
                        <td className='px-4 py-2 text-sm text-gray-700'>TRUE</td>
                      </tr>
                    </tbody>
                  </table>
                </div>

                <div className='space-y-6'>
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
                                            className={`h-10 rounded-lg border border-gray-200 bg-gray-50/50 text-sm text-gray-900 focus:bg-white focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200 ${/^(XS|S|M|L|XL)(\s*\((Assortment|Solid)\))?$/.test(
                                              c,
                                            )
                                              ? 'w-24 min-w-24 px-3'
                                              : 'w-full px-2'}`}
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

        {false && (
          <>
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
          </>
        )}
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
                  multiple={mode === 'analyze'}
                  onChange={handleFileChange}
                  className='absolute inset-0 w-full h-full opacity-0 cursor-pointer'
                />
                {mode === 'analyze' ? (
                  selectedFiles.length === 0 ? (
                    <div className='text-center text-gray-500'>
                      <FileText className='w-12 h-12 mx-auto mb-3 text-gray-400' />
                      <p className='text-sm font-medium'>Drag files here, or click to select</p>
                      <p className='text-xs mt-1 text-gray-400'>PDFs: Supplementary + SizePerColourBreakdown_Supplier</p>
                    </div>
                  ) : (
                    <div className='text-center'>
                      <p className='text-sm font-medium text-gray-900 mb-2'>Selected files ({selectedFiles.length})</p>
                      <div className='mt-2 space-y-1'>
                        {selectedFiles.slice(0, 4).map((f) => (
                          <p
                            key={f.name}
                            className='text-xs text-gray-500 bg-white px-3 py-1 rounded border border-gray-200 inline-block'
                          >
                            {f.name}
                          </p>
                        ))}
                        {selectedFiles.length > 4 && (
                          <p className='text-xs text-gray-400'>+{selectedFiles.length - 4} more</p>
                        )}
                      </div>
                      <p className='text-xs text-gray-400 mt-3'>Click to choose different files</p>
                    </div>
                  )
                ) : !selectedFile ? (
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
              disabled={(mode === 'extract' ? !selectedFile : selectedFiles.length === 0) || isPending}
              className={`w-full sm:w-auto h-12 px-8 text-base ${mode === 'analyze' ? 'bg-indigo-600 hover:bg-indigo-700' : ''}`}
            >
              {isPending ? (
                <>
                  <Loader2 className='w-5 h-5 mr-2 animate-spin' />
                  {mode === 'extract' ? 'Extracting text...' : 'Analyzing document...'}
                </>
              ) : (
                <>{mode === 'extract' ? 'Extract text' : 'Analyze files and merge'}</>
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
            <div className={`transition-all duration-500 ${analyzeData ? 'opacity-100' : 'opacity-0'}`}>
              {analyzeData && (
                <div className='bg-white p-8 rounded-lg shadow-sm border border-gray-200'>
                  <div className='flex items-center justify-between mb-6'>
                    <h2 className='text-xl font-bold text-gray-900 flex items-center'>
                      <TableIcon className='w-6 h-6 mr-3 text-indigo-600' />
                      Results
                    </h2>
                    <span className='text-sm font-medium text-indigo-600 bg-indigo-50 px-3 py-1 rounded-full border border-indigo-100'>
                      Avg. confidence: {(analyzeAvgConfidence ?? analyzeData.averageConfidence).toFixed(1)}%
                    </span>
                  </div>

                  <details className='mb-6 rounded-lg border border-gray-200 bg-gray-50/40 px-4 py-3'>
                    <summary className='cursor-pointer text-sm font-semibold text-gray-800'>Debug trace (per file)</summary>
                    <div className='mt-3 overflow-auto'>
                      {analyzeTrace.length === 0 ? (
                        <div className='text-sm text-gray-500 italic'>No trace yet. Run Analyze.</div>
                      ) : (
                        <table className='min-w-full border border-gray-200 bg-white'>
                          <thead className='bg-gray-50'>
                            <tr>
                              <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>File</th>
                              <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Status</th>
                              <th className='px-3 py-2 text-left text-xs font-semibold text-gray-600 border-b border-gray-200'>Role</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>Text len</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>Tables</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>KV</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>Details</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>BoM</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>Fallback keys</th>
                              <th className='px-3 py-2 text-right text-xs font-semibold text-gray-600 border-b border-gray-200'>Assortment</th>
                            </tr>
                          </thead>
                          <tbody>
                            {analyzeTrace.map((t) => (
                              <tr key={`${t.fileName}-${t.startedAt}`} className='border-b border-gray-100 last:border-b-0'>
                                <td className='px-3 py-2 text-xs text-gray-800 whitespace-nowrap'>{t.fileName}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 whitespace-nowrap'>
                                  {t.status}
                                  {t.status === 'error' && t.errorMessage ? `: ${t.errorMessage}` : ''}
                                </td>
                                <td className='px-3 py-2 text-xs text-gray-800 whitespace-nowrap'>
                                  {t.roleFinal ?? t.roleByName}
                                  {t.roleByName === 'unknown' && t.roleByContent ? ` (content: ${t.roleByContent})` : ''}
                                </td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.extractedTextLength ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.tablesCount ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.keyValuePairsCount ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.classifiedDetailsCount ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.classifiedBomCount ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.fallbackDetailKeysCount ?? '-'}</td>
                                <td className='px-3 py-2 text-xs text-gray-800 text-right'>{t.assortmentDetected === undefined ? '-' : t.assortmentDetected ? 'yes' : 'no'}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </div>
                  </details>

                  {renderAnalysisResult(analyzeData)}
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

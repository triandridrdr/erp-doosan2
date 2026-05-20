import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { masterSizeApi, normalizeSizeLabel, type MasterSizeDto, type MasterSizeUpsertRequest } from './api';

/**
 * Keys on a backend Size/Colour Breakdown row that are metadata, not size
 * columns. Used to isolate the size-label keys so they can be pivoted into
 * per-size rows and registered with the master-size table.
 */
export const CSB_META_KEYS: ReadonlySet<string> = new Set([
  'type',
  'countryofdestination',
  'destinationcountry',
  'articleno',
  'article',
  'color',
  'colour',
  'total',
  'noofasst',
  'size',
  'qty',
  'hmcolourcode',
  'ptarticlenumber',
  'ptarticleno',
  'ptarticle',
  'description',
  'ptarticlenumber',
  'optionno',
]);

/** Extract cm value from a kids/baby size key like "1½-2Y(92)" → 92. Returns null for adult sizes. */
function extractCmFromSizeKey(key: string): number | null {
  const m = key.match(/\((\d+)\)/);
  return m ? parseInt(m[1], 10) : null;
}

const ADULT_SIZE_ORDER: Record<string, number> = {
  XS: 10, S: 20, M: 30, L: 40, XL: 50, '1XL': 51, XXL: 60, '2XL': 61, XXXL: 70, '3XL': 71,
};

/** Numeric sort weight for a size key: cm value for kids, standard order for adults, 999 otherwise. */
function sizeKeyOrder(key: string): number {
  const cm = extractCmFromSizeKey(key);
  if (cm !== null) return cm;
  const paren = key.match(/\(([A-Z]{1,3}(?:\s*\/\s*P)?)\)/i);
  const base = paren?.[1] ?? key;
  const upper = base.toUpperCase().replace(/\s+/g, '').replace(/\*/g, '').replace(/\/P$/, '');
  return ADULT_SIZE_ORDER[upper] ?? 999;
}

function canonicalSizeKey(key: string): string {
  const raw = (key ?? '').toString().trim();
  const local = raw.match(/^([A-Z0-9]+)\s*\(([A-Z]{1,3}(?:\s*\/\s*P)?)\)\*?$/i);
  if (local) {
    const label = local[1].toUpperCase();
    const inside = local[2].toUpperCase().replace(/\s+/g, '');
    return `${label} (${inside})*`;
  }
  const adult = raw.match(/^(XS|S|M|L|XL)\s*\(\s*(XS|S|M|L|XL)\s*\)\*?$/i);
  if (adult) {
    const size = adult[1].toUpperCase();
    return `${size} (${size})*`;
  }
  return raw;
}

/** Return every key in a backend CSB row that is NOT a known meta field, sorted by size order. */
export function extractSizeKeysFromRow(row: Record<string, any> | null | undefined): string[] {
  if (!row) return [];
  const canonical = new Map<string, { original: string; canonical: string }>();
  for (const k of Object.keys(row)) {
    if (CSB_META_KEYS.has(k.toLowerCase())) continue;
    const normalized = canonicalSizeKey(k);
    const existing = canonical.get(normalized);
    if (!existing || k === normalized) canonical.set(normalized, { original: k, canonical: normalized });
  }
  return Array.from(canonical.values())
    .sort((a, b) => sizeKeyOrder(a.canonical) - sizeKeyOrder(b.canonical))
    .map((x) => x.original);
}

/** Flatten a list of CSB rows into the set of unique size labels. */
export function collectSizeLabelsFromRows(rows: Array<Record<string, any>> | null | undefined): string[] {
  if (!rows || rows.length === 0) return [];
  const out = new Set<string>();
  for (const r of rows) {
    const sizeValue = (r?.size ?? '').toString().trim();
    if (sizeValue) out.add(sizeValue);
    for (const k of extractSizeKeysFromRow(r)) {
      const v = k.trim();
      if (v) out.add(v);
    }
  }
  return Array.from(out);
}

export const MASTER_SIZES_QUERY_KEY = ['master-sizes', 'active'] as const;
export const ALL_MASTER_SIZES_QUERY_KEY = ['master-sizes', 'all'] as const;

/** Fetch & cache the active master size list. */
export function useMasterSizes() {
  return useQuery({
    queryKey: MASTER_SIZES_QUERY_KEY,
    queryFn: async () => {
      const res = await masterSizeApi.list();
      return (res?.data ?? []) as MasterSizeDto[];
    },
    staleTime: 5 * 60 * 1000, // 5 minutes — dropdown data is low-churn
  });
}

/**
 * Idempotent mutation: ensure a size exists in the master, then invalidate
 * the master-size cache so every open autocomplete refreshes.
 */
export function useEnsureMasterSize() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (request: MasterSizeUpsertRequest) => {
      const res = await masterSizeApi.upsert(request);
      return res?.data as MasterSizeDto | undefined;
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: MASTER_SIZES_QUERY_KEY });
    },
  });
}

/**
 * Given a list of raw size labels (e.g. collected from OCR results),
 * register any that aren't present in the cached master list yet.
 * Deduplicates by normalized label and runs POSTs in parallel.
 * Cache is invalidated once at the end.
 */
export function useEnsureMasterSizesBatch() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (labels: string[]) => {
      const unique = new Map<string, string>(); // normalized -> display label
      for (const raw of labels ?? []) {
        const display = (raw ?? '').toString().trim();
        if (!display) continue;
        const norm = normalizeSizeLabel(display);
        if (!norm) continue;
        if (!unique.has(norm)) unique.set(norm, display);
      }
      if (unique.size === 0) return [] as MasterSizeDto[];

      // Prefetch existing so we skip no-op network round trips when possible.
      let existingNormalizedSet: Set<string> = new Set();
      try {
        const cached = qc.getQueryData<MasterSizeDto[]>(MASTER_SIZES_QUERY_KEY);
        if (cached && cached.length > 0) {
          existingNormalizedSet = new Set(cached.map((r) => r.normalizedLabel));
        } else {
          const res = await masterSizeApi.list();
          const data = (res?.data ?? []) as MasterSizeDto[];
          existingNormalizedSet = new Set(data.map((r) => r.normalizedLabel));
          qc.setQueryData<MasterSizeDto[]>(MASTER_SIZES_QUERY_KEY, data);
        }
      } catch {
        // If prefetch fails we still attempt upserts; the backend is idempotent.
      }

      const toUpsert: Array<{ label: string }> = [];
      for (const [norm, display] of unique.entries()) {
        if (existingNormalizedSet.has(norm)) continue;
        toUpsert.push({ label: display });
      }
      if (toUpsert.length === 0) return [] as MasterSizeDto[];

      const results = await Promise.all(
        toUpsert.map((r) =>
          masterSizeApi.upsert(r).then((res) => res?.data as MasterSizeDto | undefined).catch(() => undefined),
        ),
      );
      return results.filter((r): r is MasterSizeDto => !!r);
    },
    onSuccess: (inserted) => {
      if (inserted && inserted.length > 0) {
        qc.invalidateQueries({ queryKey: MASTER_SIZES_QUERY_KEY });
      }
    },
  });
}

/** Fetch ALL master sizes including inactive — for the management page. */
export function useAllMasterSizes() {
  return useQuery({
    queryKey: ALL_MASTER_SIZES_QUERY_KEY,
    queryFn: async () => {
      const res = await masterSizeApi.list({ includeInactive: true });
      return (res?.data ?? []) as MasterSizeDto[];
    },
  });
}

/** Soft-delete a master size by id. Invalidates both active and all caches. */
export function useDeleteMasterSize() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => masterSizeApi.delete(id),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['master-sizes'] });
    },
  });
}

/** Add (upsert) a master size. Invalidates both active and all caches. */
export function useAddMasterSize() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (request: MasterSizeUpsertRequest) => masterSizeApi.upsert(request),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['master-sizes'] });
    },
  });
}

/** Restore (reactivate) a soft-deleted / inactive master size. */
export function useRestoreMasterSize() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, label }: { id: number; label: string }) =>
      masterSizeApi.update(id, { label, active: true }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['master-sizes'] });
    },
  });
}

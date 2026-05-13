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
]);

/** Return every key in a backend CSB row that is NOT a known meta field. */
export function extractSizeKeysFromRow(row: Record<string, any> | null | undefined): string[] {
  if (!row) return [];
  return Object.keys(row).filter((k) => !CSB_META_KEYS.has(k.toLowerCase()));
}

/** Flatten a list of CSB rows into the set of unique size labels. */
export function collectSizeLabelsFromRows(rows: Array<Record<string, any>> | null | undefined): string[] {
  if (!rows || rows.length === 0) return [];
  const out = new Set<string>();
  for (const r of rows) {
    for (const k of extractSizeKeysFromRow(r)) {
      const v = k.trim();
      if (v) out.add(v);
    }
  }
  return Array.from(out);
}

export const MASTER_SIZES_QUERY_KEY = ['master-sizes', 'active'] as const;

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

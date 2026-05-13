import * as React from 'react';

import { useMasterSizes } from '../../features/masterSize/hooks';
import { Input } from './Input';

/** Baseline options shown while the master-size query is loading or when
 *  the backend is unreachable. The master list (when available) is the
 *  source of truth at runtime. */
const FALLBACK_SIZE_OPTIONS = ['XS', 'S', 'M', 'L', 'XL'] as const;

export interface SizeAutocompleteInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  /** Optional caller-provided option list. When supplied this OVERRIDES the
   *  master-size fetch (useful for tests or constrained contexts). */
  options?: readonly string[];
  /** Set to false to skip the master-size fetch even when no options prop
   *  is passed. Defaults to true. */
  fetchFromMaster?: boolean;
}

export const SizeAutocompleteInput = React.forwardRef<HTMLInputElement, SizeAutocompleteInputProps>(
  ({ options, fetchFromMaster = true, list, id, value, ...props }, ref) => {
    const reactId = React.useId();
    const datalistId = list ?? `size-autocomplete-${id ?? reactId}`;

    // Only hit the API when the caller didn't pre-supply options.
    const shouldFetch = options == null && fetchFromMaster;
    const query = useMasterSizes();
    const masterLabels = React.useMemo(() => {
      if (!shouldFetch) return [] as string[];
      const rows = query.data ?? [];
      return rows.map((r) => r.label).filter((s) => s && s.length > 0);
    }, [shouldFetch, query.data]);

    const baseOptions: readonly string[] = options ?? (masterLabels.length > 0 ? masterLabels : FALLBACK_SIZE_OPTIONS);

    const normalizedValue = typeof value === 'string' ? value.trim() : '';
    const mergedOptions = React.useMemo(() => {
      const next = [...baseOptions];
      if (normalizedValue && !next.some((opt) => opt.toLowerCase() === normalizedValue.toLowerCase())) {
        next.push(normalizedValue);
      }
      return next;
    }, [normalizedValue, baseOptions]);

    return (
      <>
        <Input
          ref={ref}
          id={id}
          list={datalistId}
          value={value}
          autoComplete='off'
          {...props}
        />
        <datalist id={datalistId}>
          {mergedOptions.map((option) => (
            <option key={option} value={option} />
          ))}
        </datalist>
      </>
    );
  },
);

SizeAutocompleteInput.displayName = 'SizeAutocompleteInput';

import * as React from 'react';

import { Input } from './Input';

const DEFAULT_SIZE_OPTIONS = ['XS', 'S', 'M', 'L', 'XL'] as const;

export interface SizeAutocompleteInputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  options?: readonly string[];
}

export const SizeAutocompleteInput = React.forwardRef<HTMLInputElement, SizeAutocompleteInputProps>(
  ({ options = DEFAULT_SIZE_OPTIONS, list, id, value, ...props }, ref) => {
    const reactId = React.useId();
    const datalistId = list ?? `size-autocomplete-${id ?? reactId}`;

    const normalizedValue = typeof value === 'string' ? value.trim() : '';
    const mergedOptions = React.useMemo(() => {
      const next = [...options];
      if (normalizedValue && !next.some((opt) => opt.toLowerCase() === normalizedValue.toLowerCase())) {
        next.push(normalizedValue);
      }
      return next;
    }, [normalizedValue, options]);

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

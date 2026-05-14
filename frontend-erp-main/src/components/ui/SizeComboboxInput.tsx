/**
 * SizeComboboxInput — drop-in replacement for SizeAutocompleteInput.
 * Features:
 *  - Autocomplete dropdown from master-size database
 *  - × button on each option to delete from master (for cleanup)
 *  - Free-text manual input (any value can be typed)
 *  - Clear button to wipe current value
 */
import { ChevronDown, Loader2, Search, Trash2, X } from 'lucide-react';
import * as React from 'react';

import { type MasterSizeDto } from '../../features/masterSize/api';
import { useDeleteMasterSize, useMasterSizes } from '../../features/masterSize/hooks';
import { cn } from '../../lib/utils';

export interface SizeComboboxInputProps {
  value: string;
  onChange: React.ChangeEventHandler<HTMLInputElement>;
  className?: string;
  id?: string;
  disabled?: boolean;
  placeholder?: string;
}

export function SizeComboboxInput({
  value,
  onChange,
  className,
  id,
  disabled = false,
  placeholder = 'Pilih atau ketik ukuran…',
}: SizeComboboxInputProps) {
  const [open, setOpen] = React.useState(false);
  const [query, setQuery] = React.useState('');
  const containerRef = React.useRef<HTMLDivElement>(null);
  const inputRef = React.useRef<HTMLInputElement>(null);
  const [deletingId, setDeletingId] = React.useState<number | null>(null);

  const { data: masterSizes = [] } = useMasterSizes();
  const deleteMutation = useDeleteMasterSize();

  const filteredOptions = React.useMemo<MasterSizeDto[]>(() => {
    const q = query.trim().toLowerCase();
    if (!q) return masterSizes;
    return masterSizes.filter((s) => s.label.toLowerCase().includes(q));
  }, [masterSizes, query]);

  React.useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const fireChange = React.useCallback(
    (newValue: string) => {
      onChange({ target: { value: newValue } } as React.ChangeEvent<HTMLInputElement>);
    },
    [onChange],
  );

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    setQuery(e.target.value);
    setOpen(true);
    onChange(e);
  };

  const handleSelect = (label: string) => {
    setQuery(label);
    fireChange(label);
    setOpen(false);
    inputRef.current?.focus();
  };

  const handleClear = (e: React.MouseEvent) => {
    e.stopPropagation();
    setQuery('');
    fireChange('');
    inputRef.current?.focus();
  };

  const handleDelete = async (e: React.MouseEvent, dto: MasterSizeDto) => {
    e.stopPropagation();
    e.preventDefault();
    setDeletingId(dto.id);
    try {
      await deleteMutation.mutateAsync(dto.id);
    } finally {
      setDeletingId(null);
    }
  };

  React.useEffect(() => {
    setQuery(value ?? '');
  }, [value]);

  const showClear = !disabled && (value ?? '').length > 0;

  return (
    <div ref={containerRef} className='relative w-full'>
      <div className={cn('flex items-center rounded-md border border-gray-300 bg-white focus-within:ring-1 focus-within:ring-primary focus-within:border-primary transition-colors', disabled && 'opacity-50 cursor-not-allowed', className)}>
        <input
          ref={inputRef}
          id={id}
          value={value ?? ''}
          onChange={handleInputChange}
          onFocus={() => setOpen(true)}
          disabled={disabled}
          placeholder={placeholder}
          autoComplete='off'
          className='flex-1 h-9 px-3 text-sm bg-transparent outline-none text-gray-900 placeholder:text-gray-400 min-w-0'
        />
        {showClear && (
          <button
            type='button'
            onClick={handleClear}
            tabIndex={-1}
            className='p-1.5 text-gray-400 hover:text-gray-600 shrink-0'
            title='Hapus nilai'
          >
            <X className='h-3.5 w-3.5' />
          </button>
        )}
        <button
          type='button'
          tabIndex={-1}
          disabled={disabled}
          onClick={() => !disabled && setOpen((v) => !v)}
          className='p-1.5 text-gray-400 hover:text-gray-600 shrink-0 border-l border-gray-200'
          title='Buka pilihan'
        >
          <ChevronDown className={cn('h-3.5 w-3.5 transition-transform', open ? 'rotate-180' : '')} />
        </button>
      </div>

      {open && !disabled && (
        <div className='absolute z-50 mt-1 w-full bg-white border border-gray-200 rounded-md shadow-lg max-h-60 overflow-y-auto'>
          {filteredOptions.length === 0 ? (
            <div className='flex items-center gap-2 px-3 py-6 text-sm text-gray-400 justify-center'>
              <Search className='h-4 w-4' />
              Tidak ada ukuran ditemukan
            </div>
          ) : (
            filteredOptions.map((dto) => {
              const isDeleting = deletingId === dto.id;
              return (
                <div
                  key={dto.id}
                  className={cn(
                    'flex items-center justify-between px-3 py-2 cursor-pointer hover:bg-blue-50 group',
                    value === dto.label && 'bg-blue-50 font-medium',
                  )}
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => handleSelect(dto.label)}
                >
                  <span className='text-sm text-gray-800 flex-1 truncate'>{dto.label}</span>
                  <button
                    type='button'
                    title='Hapus dari master'
                    onMouseDown={(e) => e.preventDefault()}
                    onClick={(e) => handleDelete(e, dto)}
                    disabled={isDeleting}
                    className='ml-2 p-1 rounded text-gray-300 hover:text-red-500 hover:bg-red-50 opacity-0 group-hover:opacity-100 transition-all shrink-0'
                  >
                    {isDeleting ? (
                      <Loader2 className='h-3.5 w-3.5 animate-spin' />
                    ) : (
                      <Trash2 className='h-3.5 w-3.5' />
                    )}
                  </button>
                </div>
              );
            })
          )}
          {(value ?? '').trim().length > 0 && !filteredOptions.some((o) => o.label.toLowerCase() === (value ?? '').toLowerCase().trim()) && (
            <div className='border-t border-gray-100 px-3 py-2'>
              <div
                className='flex items-center gap-2 text-xs text-gray-500 cursor-pointer hover:text-primary'
                onMouseDown={(e) => e.preventDefault()}
                onClick={() => handleSelect(value)}
              >
                <span>Gunakan &ldquo;<strong>{value}</strong>&rdquo; sebagai input manual</span>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

SizeComboboxInput.displayName = 'SizeComboboxInput';

import { CheckCircle2, Loader2, Plus, RotateCcw, Trash2, XCircle } from 'lucide-react';
import { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { type MasterSizeDto } from './api';
import {
  useAddMasterSize,
  useAllMasterSizes,
  useDeleteMasterSize,
  useRestoreMasterSize,
} from './hooks';

export function MasterSizePage() {
  const { data: sizes = [], isLoading, isError } = useAllMasterSizes();
  const addMutation = useAddMasterSize();
  const deleteMutation = useDeleteMasterSize();
  const restoreMutation = useRestoreMasterSize();

  const [newLabel, setNewLabel] = useState('');
  const [addError, setAddError] = useState('');
  const [showInactive, setShowInactive] = useState(true);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  const displayed = showInactive ? sizes : sizes.filter((s) => s.active);

  const handleAdd = async () => {
    const label = newLabel.trim();
    if (!label) {
      setAddError('Label tidak boleh kosong.');
      return;
    }
    setAddError('');
    try {
      await addMutation.mutateAsync({ label });
      setNewLabel('');
    } catch {
      setAddError('Gagal menambahkan ukuran. Coba lagi.');
    }
  };

  const handleDelete = async (id: number) => {
    try {
      await deleteMutation.mutateAsync(id);
    } finally {
      setConfirmDeleteId(null);
    }
  };

  const handleRestore = async (row: MasterSizeDto) => {
    await restoreMutation.mutateAsync({ id: row.id, label: row.label });
  };

  return (
    <div className='p-6 max-w-3xl mx-auto'>
      <div className='mb-6'>
        <h1 className='text-2xl font-bold text-gray-900'>Master Size</h1>
        <p className='text-sm text-gray-500 mt-1'>
          Kelola daftar ukuran yang muncul di dropdown input ukuran di seluruh aplikasi.
        </p>
      </div>

      {/* Add new size */}
      <div className='bg-white border border-gray-200 rounded-lg p-4 mb-6'>
        <h2 className='text-sm font-semibold text-gray-700 mb-3'>Tambah Ukuran Baru</h2>
        <div className='flex gap-2'>
          <Input
            placeholder='Contoh: S, M, L, XS, XL, 0-1M (50)*'
            value={newLabel}
            onChange={(e) => {
              setNewLabel(e.target.value);
              if (addError) setAddError('');
            }}
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleAdd();
            }}
            className='flex-1'
          />
          <Button
            onClick={handleAdd}
            disabled={addMutation.isPending}
            className='shrink-0 flex items-center gap-1.5'
          >
            {addMutation.isPending ? (
              <Loader2 className='h-4 w-4 animate-spin' />
            ) : (
              <Plus className='h-4 w-4' />
            )}
            Tambah
          </Button>
        </div>
        {addError && <p className='text-xs text-red-600 mt-1.5'>{addError}</p>}
        {addMutation.isSuccess && (
          <p className='text-xs text-green-600 mt-1.5 flex items-center gap-1'>
            <CheckCircle2 className='h-3.5 w-3.5' /> Ukuran berhasil ditambahkan.
          </p>
        )}
      </div>

      {/* Filter + count */}
      <div className='flex items-center justify-between mb-3'>
        <span className='text-sm text-gray-500'>
          {displayed.length} ukuran{showInactive ? '' : ' aktif'}
        </span>
        <label className='flex items-center gap-2 text-sm text-gray-600 cursor-pointer select-none'>
          <input
            type='checkbox'
            checked={showInactive}
            onChange={(e) => setShowInactive(e.target.checked)}
            className='rounded border-gray-300 text-primary'
          />
          Tampilkan yang nonaktif
        </label>
      </div>

      {/* Table */}
      <div className='bg-white border border-gray-200 rounded-lg overflow-hidden'>
        {isLoading && (
          <div className='flex items-center justify-center py-12 text-gray-400'>
            <Loader2 className='h-5 w-5 animate-spin mr-2' /> Memuat data...
          </div>
        )}
        {isError && (
          <div className='flex items-center justify-center py-12 text-red-500 gap-2'>
            <XCircle className='h-5 w-5' /> Gagal memuat data ukuran.
          </div>
        )}
        {!isLoading && !isError && displayed.length === 0 && (
          <div className='py-12 text-center text-gray-400 text-sm'>Belum ada data ukuran.</div>
        )}
        {!isLoading && !isError && displayed.length > 0 && (
          <table className='w-full text-sm'>
            <thead className='bg-gray-50 border-b border-gray-200'>
              <tr>
                <th className='px-4 py-3 text-left font-semibold text-gray-600 w-16'>No</th>
                <th className='px-4 py-3 text-left font-semibold text-gray-600'>Label</th>
                <th className='px-4 py-3 text-left font-semibold text-gray-600'>Normalized</th>
                <th className='px-4 py-3 text-left font-semibold text-gray-600 w-24'>Sort</th>
                <th className='px-4 py-3 text-left font-semibold text-gray-600 w-24'>Status</th>
                <th className='px-4 py-3 text-right font-semibold text-gray-600 w-28'>Aksi</th>
              </tr>
            </thead>
            <tbody className='divide-y divide-gray-100'>
              {displayed.map((row, idx) => (
                <tr
                  key={row.id}
                  className={row.active ? 'hover:bg-gray-50' : 'bg-gray-50 opacity-60 hover:opacity-80'}
                >
                  <td className='px-4 py-2.5 text-gray-400'>{idx + 1}</td>
                  <td className='px-4 py-2.5 font-medium text-gray-800'>{row.label}</td>
                  <td className='px-4 py-2.5 text-gray-500 font-mono text-xs'>{row.normalizedLabel}</td>
                  <td className='px-4 py-2.5 text-gray-500'>{row.sortOrder}</td>
                  <td className='px-4 py-2.5'>
                    {row.active ? (
                      <span className='inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700 font-medium'>
                        <CheckCircle2 className='h-3 w-3' /> Aktif
                      </span>
                    ) : (
                      <span className='inline-flex items-center gap-1 text-xs px-2 py-0.5 rounded-full bg-gray-200 text-gray-500 font-medium'>
                        <XCircle className='h-3 w-3' /> Nonaktif
                      </span>
                    )}
                  </td>
                  <td className='px-4 py-2.5 text-right'>
                    {row.active ? (
                      confirmDeleteId === row.id ? (
                        <span className='inline-flex items-center gap-1.5'>
                          <button
                            onClick={() => handleDelete(row.id)}
                            disabled={deleteMutation.isPending}
                            className='text-xs text-red-600 hover:text-red-800 font-semibold'
                          >
                            {deleteMutation.isPending ? (
                              <Loader2 className='h-3.5 w-3.5 animate-spin' />
                            ) : (
                              'Ya, hapus'
                            )}
                          </button>
                          <button
                            onClick={() => setConfirmDeleteId(null)}
                            className='text-xs text-gray-400 hover:text-gray-600'
                          >
                            Batal
                          </button>
                        </span>
                      ) : (
                        <button
                          onClick={() => setConfirmDeleteId(row.id)}
                          className='p-1.5 rounded text-gray-400 hover:text-red-600 hover:bg-red-50 transition-colors'
                          title='Hapus ukuran ini'
                        >
                          <Trash2 className='h-4 w-4' />
                        </button>
                      )
                    ) : (
                      <button
                        onClick={() => handleRestore(row)}
                        disabled={restoreMutation.isPending}
                        className='p-1.5 rounded text-gray-400 hover:text-green-600 hover:bg-green-50 transition-colors'
                        title='Aktifkan kembali'
                      >
                        <RotateCcw className='h-4 w-4' />
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

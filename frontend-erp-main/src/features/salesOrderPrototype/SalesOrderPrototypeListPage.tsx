import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { salesOrderPrototypeApi } from './api';

export function SalesOrderPrototypeListPage() {
  const [q, setQ] = useState('');
  const navigate = useNavigate();

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['sales-order-prototypes'],
    queryFn: async () => {
      const res = await salesOrderPrototypeApi.getAll();
      return res.data ?? [];
    },
  });

  const filtered = useMemo(() => {
    const s = q.trim().toLowerCase();
    if (!s) return data ?? [];
    return (data ?? []).filter((r) => {
      const name = (r.analyzedFileName ?? '').toLowerCase();
      const so = (r.salesOrderNumber ?? '').toLowerCase();
      return name.includes(s) || so.includes(s) || String(r.id).includes(s);
    });
  }, [data, q]);

  return (
    <div className='space-y-6'>
      <div className='flex items-center justify-between'>
        <h1 className='text-2xl font-bold text-gray-900'>Sales Order Prototype</h1>
        <Button type='button' onClick={() => refetch()}>
          Refresh
        </Button>
      </div>

      <div className='bg-white p-4 rounded-lg shadow-sm border border-gray-200 flex items-center gap-3'>
        <div className='flex-1 max-w-md'>
          <Input placeholder='Search by id / SO number / file name' value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
      </div>

      <div className='bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden'>
        <div className='overflow-x-auto'>
          <table className='min-w-full divide-y divide-gray-200'>
            <thead className='bg-gray-50'>
              <tr>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>ID</th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>Sales Order Number</th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>File Name</th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>Created At</th>
                <th className='px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider'>Actions</th>
              </tr>
            </thead>
            <tbody className='bg-white divide-y divide-gray-200'>
              {isLoading && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-gray-500'>
                    Loading...
                  </td>
                </tr>
              )}
              {error && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-red-500'>
                    An error occurred while loading data.
                  </td>
                </tr>
              )}
              {!isLoading && !error && filtered.length === 0 && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-gray-500'>
                    No prototypes.
                  </td>
                </tr>
              )}
              {filtered.map((r) => (
                <tr key={r.id} className='hover:bg-gray-50 transition-colors'>
                  <td className='px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900'>{r.id}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-700'>{r.salesOrderNumber ?? ''}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-700'>{r.analyzedFileName ?? ''}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{r.createdAt ?? ''}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-right'>
                    <div className='inline-flex gap-2'>
                      <Button type='button' variant='outline' onClick={() => navigate(`/sales-order-prototype/${r.id}`)}>
                        Edit
                      </Button>
                      <Button
                        type='button'
                        variant='danger'
                        onClick={async () => {
                          const ok = window.confirm(`Delete prototype id=${r.id}?`);
                          if (!ok) return;
                          try {
                            await salesOrderPrototypeApi.delete(r.id);
                            await refetch();
                          } catch (e) {
                            const msg = e instanceof Error ? e.message : String(e);
                            alert(`Failed to delete: ${msg}`);
                          }
                        }}
                      >
                        Delete
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

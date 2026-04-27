/**
 * @file features/sales/SalesOrderListPage.tsx
 * @description Sales / Other list page styled per the DCBJ ERP design (PPT slide 3).
 * Uses tabs (All / Request For Approval / Approved), a filter form with optional
 * Detail Inquiry section, the orders table, and a pagination footer.
 * Backend logic (React Query + salesApi) is unchanged.
 */
import { useQuery } from '@tanstack/react-query';
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight, Plus, Search } from 'lucide-react';
import { useMemo, useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { cn } from '../../lib/utils';
import { type OrderStatus, salesApi } from './api';
import { SalesOrderCreateModal } from './SalesOrderCreateModal';

type Tab = 'All' | 'Request For Approval' | 'Approved';

const TABS: Tab[] = ['All', 'Request For Approval', 'Approved'];

const today = new Date().toISOString().split('T')[0];
const oneMonthAgo = (() => {
  const d = new Date();
  d.setMonth(d.getMonth() - 1);
  return d.toISOString().split('T')[0];
})();

const PAGE_SIZE_OPTIONS = [10, 20, 50];

function statusToTab(status: OrderStatus): Tab {
  if (status === 'PENDING') return 'Request For Approval';
  if (status === 'CONFIRMED' || status === 'SHIPPED') return 'Approved';
  return 'All';
}

function approvalLabel(status: OrderStatus): string {
  if (status === 'CONFIRMED' || status === 'SHIPPED') return 'Yes';
  if (status === 'PENDING') return 'No';
  return '-';
}

export function SalesOrderListPage() {
  const [tab, setTab] = useState<Tab>('All');
  const [showDetail, setShowDetail] = useState(false);
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false);

  const [orderDateFrom, setOrderDateFrom] = useState(oneMonthAgo);
  const [orderDateTo, setOrderDateTo] = useState(today);
  const [style, setStyle] = useState('');
  const [department, setDepartment] = useState('');
  const [employee, setEmployee] = useState('');
  const [buyerPO, setBuyerPO] = useState('');
  const [customer, setCustomer] = useState('');
  const [itemName, setItemName] = useState('');
  const [itemCode, setItemCode] = useState('');

  // Detail-only filters
  const [customerType, setCustomerType] = useState('');
  const [inCharger, setInCharger] = useState('');
  const [area, setArea] = useState('All');
  const [deliveryPlace, setDeliveryPlace] = useState('');
  const [paymentCondition, setPaymentCondition] = useState('');
  const [memo, setMemo] = useState('');

  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);

  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());

  const { data: sales, isLoading, error } = useQuery({
    queryKey: ['sales-orders'],
    queryFn: async () => {
      const res = await salesApi.getAll();
      return res.data;
    },
  });

  const filtered = useMemo(() => {
    const all = sales?.content ?? [];
    return all.filter((o) => {
      if (tab !== 'All' && statusToTab(o.status) !== tab) return false;
      if (orderDateFrom && o.orderDate < orderDateFrom) return false;
      if (orderDateTo && o.orderDate > orderDateTo) return false;
      if (customer && !o.customerName?.toLowerCase().includes(customer.toLowerCase())) return false;
      if (buyerPO && !o.orderNumber?.toLowerCase().includes(buyerPO.toLowerCase())) return false;
      if (itemName) {
        const hasItem = o.lines?.some((l) => l.itemName?.toLowerCase().includes(itemName.toLowerCase()));
        if (!hasItem) return false;
      }
      if (itemCode) {
        const hasItem = o.lines?.some((l) => l.itemCode?.toLowerCase().includes(itemCode.toLowerCase()));
        if (!hasItem) return false;
      }
      return true;
    });
  }, [sales, tab, orderDateFrom, orderDateTo, customer, buyerPO, itemName, itemCode]);

  const totalPages = Math.max(1, Math.ceil(filtered.length / pageSize));
  const pagedRows = filtered.slice((page - 1) * pageSize, page * pageSize);

  const allChecked = pagedRows.length > 0 && pagedRows.every((r) => selectedIds.has(r.id));
  const toggleAll = () => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (allChecked) pagedRows.forEach((r) => next.delete(r.id));
      else pagedRows.forEach((r) => next.add(r.id));
      return next;
    });
  };
  const toggleOne = (id: number) =>
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  return (
    <div className='space-y-4'>
      {/* Tabs + New Order */}
      <div className='bg-white rounded-lg border border-gray-200 px-3 sm:px-4 py-2.5 flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2'>
        <div className='flex items-center gap-1 overflow-x-auto'>
          {TABS.map((t) => (
            <button
              key={t}
              type='button'
              onClick={() => {
                setTab(t);
                setPage(1);
              }}
              className={cn(
                'px-3 sm:px-4 py-1.5 text-xs sm:text-sm font-medium rounded-md transition-colors whitespace-nowrap',
                tab === t ? 'bg-primary-soft text-primary' : 'text-gray-600 hover:bg-gray-50',
              )}
            >
              {t}
            </button>
          ))}
        </div>
        <Button onClick={() => setIsCreateModalOpen(true)} className='h-8 px-3 text-xs self-start sm:self-auto'>
          <Plus className='w-3.5 h-3.5 mr-1' />
          New Order
        </Button>
      </div>

      {/* Filter form */}
      <div className='bg-white rounded-lg border border-gray-200 px-3 sm:px-5 py-4'>
        <div className='grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-12 gap-x-4 gap-y-3 lg:items-center'>
          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Oder Date</label>
          <div className='sm:col-span-1 lg:col-span-4 flex items-center gap-2'>
            <input
              type='date'
              value={orderDateFrom}
              onChange={(e) => setOrderDateFrom(e.target.value)}
              className='h-9 w-full rounded-md border border-gray-300 bg-white px-3 text-sm focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary'
            />
            <span className='text-gray-400'>~</span>
            <input
              type='date'
              value={orderDateTo}
              onChange={(e) => setOrderDateTo(e.target.value)}
              className='h-9 w-full rounded-md border border-gray-300 bg-white px-3 text-sm focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary'
            />
          </div>

          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Buyer P/O</label>
          <div className='lg:col-span-4'>
            <Input value={buyerPO} onChange={(e) => setBuyerPO(e.target.value)} />
          </div>

          <div className='sm:col-span-2 lg:col-span-2 flex justify-end'>
            <Button
              type='button'
              variant='outline'
              className='h-9 px-5 text-xs w-full sm:w-auto'
              onClick={() => setPage(1)}
            >
              <Search className='w-3.5 h-3.5 mr-1' /> Inquiry
            </Button>
          </div>

          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Style</label>
          <div className='lg:col-span-4'>
            <Input value={style} onChange={(e) => setStyle(e.target.value)} />
          </div>
          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Customer</label>
          <div className='lg:col-span-4'>
            <Input value={customer} onChange={(e) => setCustomer(e.target.value)} />
          </div>
          <div className='hidden lg:block lg:col-span-2' />

          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Department</label>
          <div className='lg:col-span-4 relative'>
            <Input value={department} onChange={(e) => setDepartment(e.target.value)} className='pr-8' />
            <Search className='absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400' />
          </div>
          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Item Name</label>
          <div className='lg:col-span-4 relative'>
            <Input value={itemName} onChange={(e) => setItemName(e.target.value)} className='pr-8' />
            <Search className='absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400' />
          </div>
          <div className='hidden lg:block lg:col-span-2' />

          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Employee</label>
          <div className='lg:col-span-4 relative'>
            <Input value={employee} onChange={(e) => setEmployee(e.target.value)} className='pr-8' />
            <Search className='absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400' />
          </div>
          <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Item Code</label>
          <div className='lg:col-span-4 relative'>
            <Input value={itemCode} onChange={(e) => setItemCode(e.target.value)} className='pr-8' />
            <Search className='absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400' />
          </div>
          <div className='sm:col-span-2 lg:col-span-2 flex justify-end'>
            <Button
              type='button'
              variant='outline'
              className='h-9 px-5 text-xs w-full sm:w-auto'
              onClick={() => setShowDetail((v) => !v)}
            >
              Detail Inquiry {showDetail ? '\u2296' : '\u2295'}
            </Button>
          </div>
        </div>

        {/* Detail Inquiry expanded section */}
        {showDetail && (
          <div className='mt-4 pt-4 border-t border-dashed border-gray-200 grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-12 gap-x-4 gap-y-3 lg:items-center'>
            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Customer Type</label>
            <div className='lg:col-span-4 relative'>
              <Input value={customerType} onChange={(e) => setCustomerType(e.target.value)} className='pr-8' />
              <Search className='absolute right-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-gray-400' />
            </div>
            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>In Charger</label>
            <div className='lg:col-span-4'>
              <Input value={inCharger} onChange={(e) => setInCharger(e.target.value)} />
            </div>
            <div className='hidden lg:block lg:col-span-2' />

            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Area</label>
            <div className='lg:col-span-4'>
              <select
                value={area}
                onChange={(e) => setArea(e.target.value)}
                className='h-9 w-full rounded-md border border-gray-300 bg-white px-3 text-sm focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary'
              >
                <option>All</option>
                <option>Domestic</option>
                <option>Export</option>
              </select>
            </div>
            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Payment Condition</label>
            <div className='lg:col-span-4'>
              <Input value={paymentCondition} onChange={(e) => setPaymentCondition(e.target.value)} />
            </div>
            <div className='hidden lg:block lg:col-span-2' />

            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Delivery Place</label>
            <div className='lg:col-span-4'>
              <Input value={deliveryPlace} onChange={(e) => setDeliveryPlace(e.target.value)} />
            </div>
            <label className='lg:col-span-1 text-xs font-medium text-gray-700'>Memo</label>
            <div className='sm:col-span-1 lg:col-span-7'>
              <Input value={memo} onChange={(e) => setMemo(e.target.value)} />
            </div>
          </div>
        )}
      </div>

      {/* Table */}
      <div className='bg-white rounded-lg border border-gray-200 overflow-hidden'>
        <div className='overflow-x-auto'>
          <table className='min-w-full text-sm'>
            <thead className='bg-gray-50 text-gray-600'>
              <tr>
                <th className='px-3 py-2.5 text-left font-medium w-10'>
                  <input
                    type='checkbox'
                    checked={allChecked}
                    onChange={toggleAll}
                    className='accent-primary'
                  />
                </th>
                <th className='px-3 py-2.5 text-left font-medium'>Order No</th>
                <th className='px-3 py-2.5 text-left font-medium'>BOM</th>
                <th className='px-3 py-2.5 text-left font-medium'>Buyer P/O</th>
                <th className='px-3 py-2.5 text-left font-medium'>Area</th>
                <th className='px-3 py-2.5 text-left font-medium'>Order Date</th>
                <th className='px-3 py-2.5 text-left font-medium'>Delivery Date</th>
                <th className='px-3 py-2.5 text-left font-medium'>Customer</th>
                <th className='px-3 py-2.5 text-left font-medium'>Item</th>
                <th className='px-3 py-2.5 text-left font-medium'>Style</th>
                <th className='px-3 py-2.5 text-right font-medium'>Quantity</th>
                <th className='px-3 py-2.5 text-left font-medium'>Memo</th>
                <th className='px-3 py-2.5 text-left font-medium'>Approval</th>
              </tr>
            </thead>
            <tbody className='divide-y divide-gray-100 text-gray-700'>
              {isLoading && (
                <tr>
                  <td colSpan={13} className='px-3 py-10 text-center text-gray-500'>
                    Loading...
                  </td>
                </tr>
              )}
              {!isLoading && error && (
                <tr>
                  <td colSpan={13} className='px-3 py-10 text-center text-red-500'>
                    An error occurred while loading data.
                  </td>
                </tr>
              )}
              {!isLoading && !error && pagedRows.length === 0 && (
                <tr>
                  <td colSpan={13} className='px-3 py-10 text-center text-gray-500'>
                    No sales orders.
                  </td>
                </tr>
              )}
              {pagedRows.map((order) => {
                const firstLine = order.lines?.[0];
                const totalQty = order.lines?.reduce((s, l) => s + (l.quantity ?? 0), 0) ?? 0;
                const checked = selectedIds.has(order.id);
                return (
                  <tr key={order.id} className='hover:bg-gray-50 transition-colors'>
                    <td className='px-3 py-2.5'>
                      <input
                        type='checkbox'
                        checked={checked}
                        onChange={() => toggleOne(order.id)}
                        className='accent-primary'
                      />
                    </td>
                    <td className='px-3 py-2.5 font-medium text-primary underline cursor-pointer'>
                      {order.orderNumber}
                    </td>
                    <td className='px-3 py-2.5'>BOM-Register</td>
                    <td className='px-3 py-2.5'>{order.remarks ?? '-'}</td>
                    <td className='px-3 py-2.5'>{order.deliveryAddress ? 'Domestic' : 'Export'}</td>
                    <td className='px-3 py-2.5'>{order.orderDate}</td>
                    <td className='px-3 py-2.5'>-</td>
                    <td className='px-3 py-2.5'>{order.customerName}</td>
                    <td className='px-3 py-2.5'>{firstLine?.itemName ?? '-'}</td>
                    <td className='px-3 py-2.5'>{firstLine?.itemCode ?? '-'}</td>
                    <td className='px-3 py-2.5 text-right'>{totalQty.toLocaleString()}</td>
                    <td className='px-3 py-2.5 truncate max-w-[120px]'>{order.remarks ?? '-'}</td>
                    <td className='px-3 py-2.5'>{approvalLabel(order.status)}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Pagination footer */}
        <div className='flex items-center justify-between px-4 py-2 border-t border-gray-200'>
          <div className='flex items-center gap-1 text-gray-600'>
            <button
              type='button'
              onClick={() => setPage(1)}
              disabled={page === 1}
              className='p-1 rounded hover:bg-gray-100 disabled:opacity-40'
            >
              <ChevronsLeft className='w-4 h-4' />
            </button>
            <button
              type='button'
              onClick={() => setPage((p) => Math.max(1, p - 1))}
              disabled={page === 1}
              className='p-1 rounded hover:bg-gray-100 disabled:opacity-40'
            >
              <ChevronLeft className='w-4 h-4' />
            </button>

            {Array.from({ length: Math.min(5, totalPages) }, (_, i) => {
              const p = i + 1;
              return (
                <button
                  key={p}
                  type='button'
                  onClick={() => setPage(p)}
                  className={cn(
                    'min-w-7 h-7 px-2 rounded text-xs font-medium',
                    page === p ? 'bg-primary text-white' : 'hover:bg-gray-100',
                  )}
                >
                  {p}
                </button>
              );
            })}

            <button
              type='button'
              onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
              disabled={page === totalPages}
              className='p-1 rounded hover:bg-gray-100 disabled:opacity-40'
            >
              <ChevronRight className='w-4 h-4' />
            </button>
            <button
              type='button'
              onClick={() => setPage(totalPages)}
              disabled={page === totalPages}
              className='p-1 rounded hover:bg-gray-100 disabled:opacity-40'
            >
              <ChevronsRight className='w-4 h-4' />
            </button>
          </div>

          <div className='flex items-center gap-2 text-xs text-gray-600'>
            <span>Result per page</span>
            <select
              value={pageSize}
              onChange={(e) => {
                setPageSize(Number(e.target.value));
                setPage(1);
              }}
              className='h-7 rounded border border-gray-300 px-1 text-xs focus:outline-none focus:ring-1 focus:ring-primary'
            >
              {PAGE_SIZE_OPTIONS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>
        </div>
      </div>

      {/* Footer actions */}
      <div className='flex items-center gap-2'>
        <Button type='button' variant='outline' className='h-8 px-3 text-xs'>
          Available Inventory Inquiry
        </Button>
        <Button type='button' variant='outline' className='h-8 px-3 text-xs'>
          Excel
        </Button>
      </div>

      <SalesOrderCreateModal isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)} />
    </div>
  );
}

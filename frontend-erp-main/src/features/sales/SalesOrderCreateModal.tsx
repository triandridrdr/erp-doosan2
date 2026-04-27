/**
 * @file features/sales/SalesOrderCreateModal.tsx
 * @description Order creation modal styled per the DCBJ ERP design (PPT slides 5/6).
 * Two area tabs (Domestic / Export), two-column main form, item line table, and a
 * "Save this information?" confirmation dialog. Backend API + payload unchanged.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Search } from 'lucide-react';
import React, { useState } from 'react';
import { createPortal } from 'react-dom';

import { Button } from '../../components/ui/Button';
import { Modal } from '../../components/ui/Modal';
import { cn } from '../../lib/utils';
import { salesApi, type SalesOrderRequest } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

type AreaTab = 'Domestic' | 'Export';

interface OrderLine {
  itemName: string;
  description: string;
  unit: string;
  size: string;
  style: string;
  cuttingNo: string;
  color: string;
  destination: string;
  unitPrice: number;
  supplyAmount: number;
  fobPrice: number;
  cmtCost: number;
  // payload-only
  itemCode: string;
  quantity: number;
}

const initialLine: OrderLine = {
  itemName: '',
  description: '',
  unit: '',
  size: '',
  style: '',
  cuttingNo: '',
  color: '',
  destination: '',
  unitPrice: 0,
  supplyAmount: 0,
  fobPrice: 0,
  cmtCost: 0,
  itemCode: '',
  quantity: 1,
};

const BORDER_INPUT =
  'h-8 w-full rounded border border-gray-300 bg-white px-2 text-xs focus:outline-none focus:ring-1 focus:ring-primary focus:border-primary';

function FieldRow({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <div className='grid grid-cols-12 items-center gap-2'>
      <label className='col-span-4 text-xs font-medium text-gray-700'>
        {label}
        {required && <span className='text-red-500 ml-0.5'>*</span>}
      </label>
      <div className='col-span-8'>{children}</div>
    </div>
  );
}

export function SalesOrderCreateModal({ isOpen, onClose }: Props) {
  const queryClient = useQueryClient();

  const [areaTab, setAreaTab] = useState<AreaTab>('Domestic');

  const [orderDate, setOrderDate] = useState(new Date().toISOString().split('T')[0]);
  const [documentNo, setDocumentNo] = useState('');
  const [department, setDepartment] = useState('');
  const [customer, setCustomer] = useState('');
  const [deliveryPlace, setDeliveryPlace] = useState('');
  const [forwardingWarehouse, setForwardingWarehouse] = useState('');
  const [memo, setMemo] = useState('');
  const [buyerPO, setBuyerPO] = useState('');
  const [employee, setEmployee] = useState('');
  const [inCharger, setInCharger] = useState('');
  const [paymentCondition, setPaymentCondition] = useState('');
  const [sizeMode, setSizeMode] = useState('National Size');

  const [lines, setLines] = useState<OrderLine[]>([{ ...initialLine }]);

  const [confirmOpen, setConfirmOpen] = useState(false);

  const reset = () => {
    setAreaTab('Domestic');
    setOrderDate(new Date().toISOString().split('T')[0]);
    setDocumentNo('');
    setDepartment('');
    setCustomer('');
    setDeliveryPlace('');
    setForwardingWarehouse('');
    setMemo('');
    setBuyerPO('');
    setEmployee('');
    setInCharger('');
    setPaymentCondition('');
    setSizeMode('National Size');
    setLines([{ ...initialLine }]);
  };

  const createMutation = useMutation({
    mutationFn: salesApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] });
      setConfirmOpen(false);
      onClose();
      reset();
      alert('Sales order created successfully.');
    },
    onError: (error: Error) => {
      setConfirmOpen(false);
      alert(`Failed to create sales order: ${error.message}`);
    },
  });

  const updateLine = <K extends keyof OrderLine>(index: number, field: K, value: OrderLine[K]) => {
    setLines((prev) => {
      const next = [...prev];
      next[index] = { ...next[index], [field]: value };
      return next;
    });
  };

  const addLine = () => setLines((prev) => [...prev, { ...initialLine }]);
  const deleteLastLine = () =>
    setLines((prev) => (prev.length > 1 ? prev.slice(0, -1) : prev));

  const supplyTotal = lines.reduce((s, l) => s + (Number(l.supplyAmount) || 0), 0);
  const vatAmount = Math.round(supplyTotal * 0.1);
  const total = supplyTotal + vatAmount;

  const handleSave = (e: React.FormEvent) => {
    e.preventDefault();
    if (!orderDate || !customer.trim() || !buyerPO.trim()) {
      alert('Please fill in all required fields (Order Date, Customer, Buyer P/O).');
      return;
    }
    setConfirmOpen(true);
  };

  const submit = () => {
    const payload: SalesOrderRequest = {
      orderDate,
      customerCode: customer.trim(),
      customerName: customer.trim(),
      deliveryAddress: areaTab === 'Domestic' ? deliveryPlace : undefined,
      remarks: [buyerPO ? `Buyer P/O: ${buyerPO}` : '', memo].filter(Boolean).join(' | ') || undefined,
      lines: lines
        .filter((l) => l.itemName.trim() || l.itemCode.trim())
        .map((l, i) => ({
          lineNumber: i + 1,
          itemCode: l.itemCode || l.itemName,
          itemName: l.itemName,
          quantity: Number(l.quantity) || 1,
          unitPrice: Number(l.unitPrice) || 0,
          remarks: l.description || undefined,
        })),
    };
    if (payload.lines.length === 0) {
      alert('Please add at least one item line.');
      return;
    }
    createMutation.mutate(payload);
  };

  return (
    <>
      <Modal
        isOpen={isOpen}
        onClose={() => {
          if (!createMutation.isPending) {
            onClose();
            reset();
          }
        }}
        title='Order'
        maxWidth='max-w-5xl'
        bodyClassName='p-0'
        footer={
          <>
            <Button
              type='button'
              variant='outline'
              className='h-8 px-4 text-xs'
              onClick={() => {
                onClose();
                reset();
              }}
            >
              Cancel
            </Button>
            <Button
              type='submit'
              form='sales-order-form'
              className='h-8 px-6 text-xs'
              disabled={createMutation.isPending}
            >
              {createMutation.isPending ? 'Saving...' : 'Save'}
            </Button>
          </>
        }
      >
        <form id='sales-order-form' onSubmit={handleSave}>
          {/* Domestic / Export tabs */}
          <div className='flex border-b border-gray-200 px-5'>
            {(['Domestic', 'Export'] as AreaTab[]).map((t) => (
              <button
                key={t}
                type='button'
                onClick={() => setAreaTab(t)}
                className={cn(
                  'px-4 py-2 text-sm transition-colors -mb-px border-b-2',
                  areaTab === t
                    ? 'text-primary border-primary font-semibold'
                    : 'text-gray-500 border-transparent hover:text-gray-700',
                )}
              >
                {t}
              </button>
            ))}
          </div>

          {/* Two-column form */}
          <div className='px-5 py-4 grid grid-cols-2 gap-x-8 gap-y-2.5'>
            {/* Left column */}
            <FieldRow label='Oder Date' required>
              <input
                type='date'
                value={orderDate}
                onChange={(e) => setOrderDate(e.target.value)}
                className={BORDER_INPUT}
                required
              />
            </FieldRow>

            <FieldRow label='Buyer P/O' required>
              <input
                type='text'
                value={buyerPO}
                onChange={(e) => setBuyerPO(e.target.value)}
                className={BORDER_INPUT}
                required
              />
            </FieldRow>

            <FieldRow label='Document No'>
              <input
                type='text'
                value={documentNo}
                onChange={(e) => setDocumentNo(e.target.value)}
                className={BORDER_INPUT}
              />
            </FieldRow>

            <FieldRow label='Employee'>
              <div className='relative'>
                <input
                  type='text'
                  value={employee}
                  onChange={(e) => setEmployee(e.target.value)}
                  className={cn(BORDER_INPUT, 'pr-7')}
                />
                <Search className='absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-gray-400' />
              </div>
            </FieldRow>

            <FieldRow label='Department'>
              <div className='relative'>
                <input
                  type='text'
                  value={department}
                  onChange={(e) => setDepartment(e.target.value)}
                  className={cn(BORDER_INPUT, 'pr-7')}
                />
                <Search className='absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-gray-400' />
              </div>
            </FieldRow>

            <FieldRow label='In Charger'>
              <input
                type='text'
                value={inCharger}
                onChange={(e) => setInCharger(e.target.value)}
                className={BORDER_INPUT}
              />
            </FieldRow>

            <FieldRow label='Customer' required>
              <div className='relative'>
                <input
                  type='text'
                  value={customer}
                  onChange={(e) => setCustomer(e.target.value)}
                  className={cn(BORDER_INPUT, 'pr-7')}
                  required
                />
                <Search className='absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-gray-400' />
              </div>
            </FieldRow>

            <FieldRow label='Payment Condition'>
              <input
                type='text'
                value={paymentCondition}
                onChange={(e) => setPaymentCondition(e.target.value)}
                className={BORDER_INPUT}
              />
            </FieldRow>

            <FieldRow label='Delivery Place'>
              <input
                type='text'
                value={deliveryPlace}
                onChange={(e) => setDeliveryPlace(e.target.value)}
                className={BORDER_INPUT}
              />
            </FieldRow>

            <div />

            <FieldRow label='Forwarding Warehouse'>
              <div className='relative'>
                <input
                  type='text'
                  value={forwardingWarehouse}
                  onChange={(e) => setForwardingWarehouse(e.target.value)}
                  className={cn(BORDER_INPUT, 'pr-7')}
                />
                <Search className='absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-gray-400' />
              </div>
            </FieldRow>

            <div />

            <div className='col-span-2 grid grid-cols-12 items-center gap-2'>
              <label className='col-span-2 text-xs font-medium text-gray-700'>Memo</label>
              <div className='col-span-10'>
                <input
                  type='text'
                  value={memo}
                  onChange={(e) => setMemo(e.target.value)}
                  className={BORDER_INPUT}
                />
              </div>
            </div>
          </div>

          {/* Item table toolbar */}
          <div className='px-5 pt-3 flex items-center justify-between'>
            <Button type='button' variant='outline' className='h-7 px-3 text-xs' onClick={addLine}>
              Item Release
            </Button>
            <div className='flex items-center gap-2'>
              <select
                value={sizeMode}
                onChange={(e) => setSizeMode(e.target.value)}
                className='h-7 rounded border border-gray-300 bg-white px-2 text-xs focus:outline-none focus:ring-1 focus:ring-primary'
              >
                <option>National Size</option>
                <option>International Size</option>
              </select>
              <Button type='button' variant='outline' className='h-7 px-3 text-xs' onClick={addLine}>
                Item
              </Button>
            </div>
          </div>

          {/* Item table */}
          <div className='px-5 pt-2 pb-3'>
            <div className='border border-gray-200 rounded'>
              <div className='overflow-x-auto'>
                <table className='min-w-full text-xs'>
                  <thead className='bg-gray-50 text-gray-600'>
                    <tr>
                      <th className='px-2 py-2 text-left font-medium'>Item Name</th>
                      <th className='px-2 py-2 text-left font-medium'>Description</th>
                      <th className='px-2 py-2 text-left font-medium'>Unit</th>
                      <th className='px-2 py-2 text-left font-medium'>Size</th>
                      <th className='px-2 py-2 text-left font-medium'>Style</th>
                      <th className='px-2 py-2 text-left font-medium'>Cutting No</th>
                      <th className='px-2 py-2 text-left font-medium'>Color</th>
                      <th className='px-2 py-2 text-left font-medium'>Destination</th>
                      <th className='px-2 py-2 text-right font-medium'>Unit Price</th>
                      <th className='px-2 py-2 text-right font-medium'>Supply Amount</th>
                      <th className='px-2 py-2 text-right font-medium'>FOB Price</th>
                      <th className='px-2 py-2 text-right font-medium'>CMT Cost</th>
                    </tr>
                  </thead>
                  <tbody className='divide-y divide-gray-100'>
                    {lines.map((line, i) => (
                      <tr key={i} className='hover:bg-gray-50'>
                        <td className='px-1 py-1'>
                          <input
                            value={line.itemName}
                            onChange={(e) => updateLine(i, 'itemName', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.description}
                            onChange={(e) => updateLine(i, 'description', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.unit}
                            onChange={(e) => updateLine(i, 'unit', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.size}
                            onChange={(e) => updateLine(i, 'size', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.style}
                            onChange={(e) => updateLine(i, 'style', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.cuttingNo}
                            onChange={(e) => updateLine(i, 'cuttingNo', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.color}
                            onChange={(e) => updateLine(i, 'color', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            value={line.destination}
                            onChange={(e) => updateLine(i, 'destination', e.target.value)}
                            className={cn(BORDER_INPUT, 'h-7')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            type='number'
                            value={line.unitPrice || ''}
                            onChange={(e) => updateLine(i, 'unitPrice', Number(e.target.value) || 0)}
                            className={cn(BORDER_INPUT, 'h-7 text-right')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            type='number'
                            value={line.supplyAmount || ''}
                            onChange={(e) => updateLine(i, 'supplyAmount', Number(e.target.value) || 0)}
                            className={cn(BORDER_INPUT, 'h-7 text-right')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            type='number'
                            value={line.fobPrice || ''}
                            onChange={(e) => updateLine(i, 'fobPrice', Number(e.target.value) || 0)}
                            className={cn(BORDER_INPUT, 'h-7 text-right')}
                          />
                        </td>
                        <td className='px-1 py-1'>
                          <input
                            type='number'
                            value={line.cmtCost || ''}
                            onChange={(e) => updateLine(i, 'cmtCost', Number(e.target.value) || 0)}
                            className={cn(BORDER_INPUT, 'h-7 text-right')}
                          />
                        </td>
                      </tr>
                    ))}
                    {/* Filler rows for visual parity with the design */}
                    {Array.from({ length: Math.max(0, 5 - lines.length) }).map((_, i) => (
                      <tr key={`empty-${i}`} className='h-8'>
                        <td colSpan={12} />
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>

          {/* Footer summary row inside form */}
          <div className='px-5 pb-4 flex items-center gap-3'>
            <Button type='button' variant='outline' className='h-7 px-3 text-xs' onClick={deleteLastLine}>
              Delete line item
            </Button>
            <div className='ml-auto flex items-center gap-3 text-xs'>
              <label className='text-gray-700'>Supply Amount</label>
              <input readOnly value={supplyTotal.toLocaleString()} className={cn(BORDER_INPUT, 'h-7 w-32 text-right bg-gray-50')} />
              <label className='text-gray-700'>VAT Amount</label>
              <input readOnly value={vatAmount.toLocaleString()} className={cn(BORDER_INPUT, 'h-7 w-32 text-right bg-gray-50')} />
              <label className='text-gray-700'>Total</label>
              <input readOnly value={total.toLocaleString()} className={cn(BORDER_INPUT, 'h-7 w-32 text-right bg-gray-50')} />
            </div>
          </div>
        </form>
      </Modal>

      {/* "Save this information?" confirmation dialog */}
      <ConfirmSaveDialog
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onConfirm={submit}
        loading={createMutation.isPending}
      />
    </>
  );
}

function ConfirmSaveDialog({
  open,
  onCancel,
  onConfirm,
  loading,
}: {
  open: boolean;
  onCancel: () => void;
  onConfirm: () => void;
  loading: boolean;
}) {
  if (!open) return null;
  return createPortal(
    <div className='fixed inset-0 z-[60] flex items-center justify-center bg-black/30 animate-fade-in'>
      <div className='bg-white rounded-lg shadow-xl w-full max-w-xs animate-slide-up'>
        <div className='flex items-center justify-end px-3 pt-2'>
          <button
            type='button'
            onClick={onCancel}
            className='p-1 rounded-full hover:bg-gray-100 text-gray-500'
            aria-label='Close confirm dialog'
          >
            <svg className='w-4 h-4' fill='none' viewBox='0 0 24 24' stroke='currentColor'>
              <path strokeLinecap='round' strokeLinejoin='round' strokeWidth={2} d='M6 18L18 6M6 6l12 12' />
            </svg>
          </button>
        </div>
        <p className='px-6 pb-2 text-center text-sm font-medium text-gray-800'>Save this information?</p>
        <div className='px-6 pb-5 pt-3 flex items-center justify-center gap-2'>
          <Button type='button' variant='outline' className='h-8 px-5 text-xs' onClick={onCancel} disabled={loading}>
            Cancel
          </Button>
          <Button type='button' className='h-8 px-5 text-xs' onClick={onConfirm} disabled={loading}>
            {loading ? 'Saving...' : 'Save'}
          </Button>
        </div>
      </div>
    </div>,
    document.body,
  );
}

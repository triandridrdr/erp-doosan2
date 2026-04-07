/**
 * @file features/inventory/StockCreateModal.tsx
 * @description 신규 재고(품목)를 등록하기 위한 모달 컴포넌트입니다.
 * 품목 정보, 창고 정보, 초기 수량 등을 입력받아 생성합니다.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import React, { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { inventoryApi, type StockCreateRequest } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

export function StockCreateModal({ isOpen, onClose }: Props) {
  const queryClient = useQueryClient();

  // 폼 입력 상태
  const [formData, setFormData] = useState<Partial<StockCreateRequest>>({
    itemCode: '',
    itemName: '',
    warehouseCode: '',
    warehouseName: '',
    quantity: 0,
    unit: 'EA',
    unitPrice: 0,
  });

  // 재고 생성 Mutation
  const createMutation = useMutation({
    mutationFn: inventoryApi.create,
    onSuccess: () => {
      // 성공 시 목록 갱신 및 모달 닫기
      queryClient.invalidateQueries({ queryKey: ['stocks'] });
      onClose();
      // 폼 초기화
      setFormData({
        itemCode: '',
        itemName: '',
        warehouseCode: '',
        warehouseName: '',
        quantity: 0,
        unit: 'EA',
        unitPrice: 0,
      });
      alert('재고가 성공적으로 생성되었습니다.');
    },
    onError: (error: Error) => {
      alert(`재고 생성 실패: ${error.message}`);
    },
  });

  // 폼 제출 핸들러
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    // 필수 값 검증
    if (!formData.itemCode || !formData.itemName || !formData.warehouseCode) {
      alert('필수 정보를 입력해주세요.');
      return;
    }

    createMutation.mutate(formData as StockCreateRequest);
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title='신규 재고 등록'>
      <form onSubmit={handleSubmit} className='space-y-4'>
        <div className='grid grid-cols-2 gap-4'>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              품목코드 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.itemCode}
              onChange={(e) => setFormData({ ...formData, itemCode: e.target.value })}
              placeholder='ITEM-001'
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              품목명 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.itemName}
              onChange={(e) => setFormData({ ...formData, itemName: e.target.value })}
              placeholder='품목명 입력'
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              창고코드 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.warehouseCode}
              onChange={(e) => setFormData({ ...formData, warehouseCode: e.target.value })}
              placeholder='WH-001'
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              창고명 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.warehouseName}
              onChange={(e) => setFormData({ ...formData, warehouseName: e.target.value })}
              placeholder='창고명 입력'
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              수량 <span className='text-red-500'>*</span>
            </label>
            <Input
              type='number'
              value={(formData.quantity ?? 0).toString()}
              onChange={(e) => {
                let value = e.target.value;
                // 입력값이 0으로 시작하고 길이가 2 이상인 경우 (예: "01"), 맨 앞의 0을 제거
                if (value.length > 1 && value.startsWith('0')) {
                  value = value.replace(/^0+/, '');
                }
                const numValue = Number(value);
                setFormData({ ...formData, quantity: isNaN(numValue) ? 0 : numValue });
              }}
              onFocus={(e) => e.target.select()}
              min='0'
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              단위 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.unit}
              onChange={(e) => setFormData({ ...formData, unit: e.target.value })}
              placeholder='EA'
              required
            />
          </div>
          <div className='col-span-2'>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              단가 <span className='text-red-500'>*</span>
            </label>
            <Input
              type='number'
              value={(formData.unitPrice ?? 0).toString()}
              onChange={(e) => {
                let value = e.target.value;
                // 입력값이 0으로 시작하고 길이가 2 이상인 경우 (예: "01"), 맨 앞의 0을 제거
                if (value.length > 1 && value.startsWith('0')) {
                  value = value.replace(/^0+/, '');
                }
                const numValue = Number(value);
                setFormData({ ...formData, unitPrice: isNaN(numValue) ? 0 : numValue });
              }}
              onFocus={(e) => e.target.select()}
              min='0'
              required
            />
          </div>
        </div>

        <div className='flex justify-end gap-2 pt-4'>
          <Button type='button' variant='ghost' onClick={onClose}>
            취소
          </Button>
          <Button type='submit' disabled={createMutation.isPending}>
            {createMutation.isPending ? '처리중...' : '재고 등록'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

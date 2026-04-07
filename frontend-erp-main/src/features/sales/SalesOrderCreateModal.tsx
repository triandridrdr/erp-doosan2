/**
 * @file features/sales/SalesOrderCreateModal.tsx
 * @description 신규 판매 주문을 등록하기 위한 모달 컴포넌트입니다.
 * 주문 기본 정보와 여러 개의 주문 품목(Line Items)을 동적으로 추가/삭제하며 입력받습니다.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import React, { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { salesApi, type SalesOrderRequest } from './api';

interface Props {
  isOpen: boolean; // 모달 열림 여부
  onClose: () => void; // 닫기 핸들러
}

// 주문 품목 초기값
const initialLine = {
  itemCode: '',
  itemName: '',
  quantity: 1,
  unitPrice: 0,
  remarks: '',
};

export function SalesOrderCreateModal({ isOpen, onClose }: Props) {
  const queryClient = useQueryClient();

  // 주문 기본 정보 폼 상태
  const [formData, setFormData] = useState<Partial<SalesOrderRequest>>({
    orderDate: new Date().toISOString().split('T')[0], // 오늘 날짜로 초기화
    customerCode: '',
    customerName: '',
    deliveryAddress: '',
    remarks: '',
  });

  // 주문 품목 리스트 상태
  const [lines, setLines] = useState([initialLine]);

  // 주문 생성 Mutation (React Query)
  const createMutation = useMutation({
    mutationFn: salesApi.create,
    onSuccess: () => {
      // 성공 시 목록 쿼리 무효화 (데이터 갱신)
      queryClient.invalidateQueries({ queryKey: ['sales-orders'] });
      onClose();
      // 폼 초기화
      setFormData({
        orderDate: new Date().toISOString().split('T')[0],
        customerCode: '',
        customerName: '',
        deliveryAddress: '',
        remarks: '',
      });
      setLines([initialLine]);
      alert('수주가 성공적으로 생성되었습니다.');
    },
    onError: (error: Error) => {
      alert(`수주 생성 실패: ${error.message}`);
    },
  });

  // 품목 입력 값 변경 핸들러
  const handleLineChange = (index: number, field: string, value: string | number) => {
    const newLines = [...lines];
    newLines[index] = { ...newLines[index], [field]: value };
    setLines(newLines);
  };

  // 품목 추가 핸들러
  const addLine = () => {
    setLines([...lines, initialLine]);
  };

  // 품목 삭제 핸들러 (최소 1개 유지)
  const removeLine = (index: number) => {
    if (lines.length > 1) {
      setLines(lines.filter((_, i) => i !== index));
    }
  };

  // 폼 제출 핸들러
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!formData.customerCode || !formData.customerName || !formData.orderDate) {
      alert('필수 정보를 입력해주세요.');
      return;
    }

    // API 요청 페이로드 구성
    const payload: SalesOrderRequest = {
      orderDate: formData.orderDate!,
      customerCode: formData.customerCode!,
      customerName: formData.customerName!,
      deliveryAddress: formData.deliveryAddress,
      remarks: formData.remarks,
      lines: lines.map((line, index) => ({
        ...line,
        lineNumber: index + 1, // 순번 자동 생성
        quantity: Number(line.quantity),
        unitPrice: Number(line.unitPrice),
      })),
    };

    createMutation.mutate(payload);
  };

  // 총 주문 금액 계산
  const totalAmount = lines.reduce(
    (sum, line) => sum + (Number(line.quantity) || 0) * (Number(line.unitPrice) || 0),
    0,
  );

  return (
    <Modal isOpen={isOpen} onClose={onClose} title='신규 수주 등록'>
      <form onSubmit={handleSubmit} className='space-y-6'>
        {/* 상단: 주문 기본 정보 입력 */}
        <div className='grid grid-cols-2 gap-4'>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              주문일자 <span className='text-red-500'>*</span>
            </label>
            <Input
              type='date'
              value={formData.orderDate}
              onChange={(e) => setFormData({ ...formData, orderDate: e.target.value })}
              required
            />
          </div>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              고객사 코드 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.customerCode}
              onChange={(e) => setFormData({ ...formData, customerCode: e.target.value })}
              placeholder='CUST-001'
              required
            />
          </div>
          <div className='col-span-2'>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              고객사명 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={formData.customerName}
              onChange={(e) => setFormData({ ...formData, customerName: e.target.value })}
              placeholder='고객사명을 입력하세요'
              required
            />
          </div>
          <div className='col-span-2'>
            <label className='block text-sm font-medium text-gray-700 mb-1'>배송지 주소</label>
            <Input
              value={formData.deliveryAddress}
              onChange={(e) => setFormData({ ...formData, deliveryAddress: e.target.value })}
              placeholder='배송지 주소를 입력하세요'
            />
          </div>
          <div className='col-span-2'>
            <label className='block text-sm font-medium text-gray-700 mb-1'>비고</label>
            <Input
              value={formData.remarks}
              onChange={(e) => setFormData({ ...formData, remarks: e.target.value })}
              placeholder='특이사항을 입력하세요'
            />
          </div>
        </div>

        {/* 하단: 주문 품목 리스트 */}
        <div className='border-t border-gray-200 pt-4'>
          <div className='flex items-center justify-between mb-2'>
            <h3 className='text-lg font-medium text-gray-900'>주문 품목</h3>
            <Button type='button' variant='outline' size='sm' onClick={addLine}>
              <Plus className='w-4 h-4 mr-2' />
              품목 추가
            </Button>
          </div>

          <div className='space-y-4'>
            {lines.map((line, index) => (
              <div key={index} className='flex gap-2 items-start bg-gray-50 p-3 rounded-lg'>
                <div className='grid grid-cols-12 gap-2 flex-1'>
                  <div className='col-span-3'>
                    <Input
                      placeholder='품목코드'
                      value={line.itemCode}
                      onChange={(e) => handleLineChange(index, 'itemCode', e.target.value)}
                      required
                    />
                  </div>
                  <div className='col-span-3'>
                    <Input
                      placeholder='품목명'
                      value={line.itemName}
                      onChange={(e) => handleLineChange(index, 'itemName', e.target.value)}
                      required
                    />
                  </div>
                  <div className='col-span-2'>
                    <Input
                      type='number'
                      placeholder='수량'
                      value={line.quantity.toString()}
                      onChange={(e) => {
                        let value = e.target.value;
                        if (value.length > 1 && value.startsWith('0')) {
                          value = value.replace(/^0+/, '');
                        }
                        const numValue = Number(value);
                        handleLineChange(index, 'quantity', isNaN(numValue) ? 0 : numValue);
                      }}
                      onFocus={(e) => e.target.select()}
                      min='1'
                      required
                    />
                  </div>
                  <div className='col-span-2'>
                    <Input
                      type='number'
                      placeholder='단가'
                      value={line.unitPrice.toString()}
                      onChange={(e) => {
                        let value = e.target.value;
                        if (value.length > 1 && value.startsWith('0')) {
                          value = value.replace(/^0+/, '');
                        }
                        const numValue = Number(value);
                        handleLineChange(index, 'unitPrice', isNaN(numValue) ? 0 : numValue);
                      }}
                      onFocus={(e) => e.target.select()}
                      min='0'
                      required
                    />
                  </div>
                  <div className='col-span-2'>
                    <Input
                      placeholder='비고'
                      value={line.remarks}
                      onChange={(e) => handleLineChange(index, 'remarks', e.target.value)}
                    />
                  </div>
                </div>
                {lines.length > 1 && (
                  <button
                    type='button'
                    onClick={() => removeLine(index)}
                    className='mt-2 text-red-500 hover:text-red-700 p-1'
                  >
                    <Trash2 className='w-4 h-4' />
                  </button>
                )}
              </div>
            ))}
          </div>

          <div className='mt-4 flex justify-end text-lg font-bold text-gray-900'>
            총 주문금액: {new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(totalAmount)}
          </div>
        </div>

        {/* 액션 버튼 */}
        <div className='flex justify-end gap-2 pt-4'>
          <Button type='button' variant='ghost' onClick={onClose}>
            취소
          </Button>
          <Button type='submit' disabled={createMutation.isPending}>
            {createMutation.isPending ? '처리중...' : '주문 등록'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

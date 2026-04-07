/**
 * @file features/accounting/JournalEntryCreateModal.tsx
 * @description 신규 회계 전표를 입력하는 모달 컴포넌트입니다.
 * 차변/대변 라인을 동적으로 추가/삭제할 수 있으며, 차대변 합계 일치 여부를 검증합니다.
 */
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Trash2 } from 'lucide-react';
import React, { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { Modal } from '../../components/ui/Modal';
import { accountingApi, type JournalEntryCreateRequest } from './api';

interface Props {
  isOpen: boolean;
  onClose: () => void;
}

// 전표 라인 초기값
const initialLine = {
  accountCode: '',
  accountName: '',
  debit: 0,
  credit: 0,
  description: '',
};

export function JournalEntryCreateModal({ isOpen, onClose }: Props) {
  const queryClient = useQueryClient();
  // 폼 상태: 전표일자 (오늘 날짜 기본값)
  const [entryDate, setEntryDate] = useState(new Date().toISOString().split('T')[0]);
  const [description, setDescription] = useState('');

  // 전표 라인 리스트 상태 (기본적으로 차변, 대변 입력을 위해 2줄 시작)
  const [lines, setLines] = useState([initialLine, initialLine]);

  // 전표 생성 Mutation
  const createMutation = useMutation({
    mutationFn: accountingApi.create,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['journal-entries'] });
      onClose();
      // 폼 초기화
      setEntryDate(new Date().toISOString().split('T')[0]);
      setDescription('');
      setLines([initialLine, initialLine]);
      alert('전표가 성공적으로 생성되었습니다.');
    },
    onError: (error: Error) => {
      alert(`전표 생성 실패: ${error.message}`);
    },
  });

  // 라인 입력 값 변경 핸들러
  const handleLineChange = (index: number, field: string, value: string | number) => {
    const newLines = [...lines];
    newLines[index] = { ...newLines[index], [field]: value };
    setLines(newLines);
  };

  // 라인 추가 핸들러
  const addLine = () => {
    setLines([...lines, initialLine]);
  };

  // 라인 삭제 핸들러 (최소 2줄 유지 - 차대변 개념상)
  const removeLine = (index: number) => {
    if (lines.length > 2) {
      setLines(lines.filter((_, i) => i !== index));
    }
  };

  // 차변/대변 합계 계산
  const totalDebit = lines.reduce((sum, line) => sum + Number(line.debit), 0);
  const totalCredit = lines.reduce((sum, line) => sum + Number(line.credit), 0);

  // 폼 제출 핸들러
  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();

    // 차대변 불일치 검증 (복식부기 원칙)
    if (totalDebit !== totalCredit) {
      alert('차변과 대변의 합계가 일치하지 않습니다.');
      return;
    }
    if (totalDebit === 0) {
      alert('금액을 입력해주세요.');
      return;
    }

    const payload: JournalEntryCreateRequest = {
      entryDate,
      description,
      lines: lines.map((line, index) => ({
        ...line,
        lineNumber: index + 1,
        debit: Number(line.debit),
        credit: Number(line.credit),
      })),
    };

    createMutation.mutate(payload);
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title='일반 전표 입력'>
      <form onSubmit={handleSubmit} className='space-y-6'>
        {/* 상단: 전표 기본 정보 */}
        <div className='grid grid-cols-2 gap-4'>
          <div>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              전표일자 <span className='text-red-500'>*</span>
            </label>
            <Input type='date' value={entryDate} onChange={(e) => setEntryDate(e.target.value)} required />
          </div>
          <div className='col-span-2'>
            <label className='block text-sm font-medium text-gray-700 mb-1'>
              적요 <span className='text-red-500'>*</span>
            </label>
            <Input
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder='전표에 대한 설명을 입력하세요'
              required
            />
          </div>
        </div>

        {/* 중단: 전표 라인 리스트 */}
        <div className='border-t border-gray-200 pt-4'>
          <div className='flex items-center justify-between mb-2'>
            <h3 className='text-lg font-medium text-gray-900'>전표 라인</h3>
            <Button type='button' variant='outline' size='sm' onClick={addLine}>
              <Plus className='w-4 h-4 mr-2' />
              라인 추가
            </Button>
          </div>

          <div className='space-y-4'>
            {lines.map((line, index) => (
              <div key={index} className='flex gap-2 items-start bg-gray-50 p-3 rounded-lg'>
                <div className='grid grid-cols-12 gap-2 flex-1'>
                  <div className='col-span-2'>
                    <Input
                      placeholder='계정코드'
                      value={line.accountCode}
                      onChange={(e) => handleLineChange(index, 'accountCode', e.target.value)}
                      required
                    />
                  </div>
                  <div className='col-span-3'>
                    <Input
                      placeholder='계정명'
                      value={line.accountName}
                      onChange={(e) => handleLineChange(index, 'accountName', e.target.value)}
                      required
                    />
                  </div>
                  <div className='col-span-2'>
                    <Input
                      type='number'
                      placeholder='차변'
                      value={line.debit}
                      onChange={(e) => handleLineChange(index, 'debit', e.target.value)}
                      min='0'
                    />
                  </div>
                  <div className='col-span-2'>
                    <Input
                      type='number'
                      placeholder='대변'
                      value={line.credit}
                      onChange={(e) => handleLineChange(index, 'credit', e.target.value)}
                      min='0'
                    />
                  </div>
                  <div className='col-span-3'>
                    <Input
                      placeholder='적요 (선택)'
                      value={line.description}
                      onChange={(e) => handleLineChange(index, 'description', e.target.value)}
                    />
                  </div>
                </div>
                {lines.length > 2 && (
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

          {/* 차대변 합계 표시 */}
          <div className='mt-4 flex justify-between items-center bg-gray-100 p-4 rounded-lg'>
            <div className='text-sm font-medium text-gray-600'>합계</div>
            <div className={`text-lg font-bold ${totalDebit === totalCredit ? 'text-green-600' : 'text-red-600'}`}>
              차변: {new Intl.NumberFormat('ko-KR').format(totalDebit)} / 대변:{' '}
              {new Intl.NumberFormat('ko-KR').format(totalCredit)}
            </div>
          </div>
          {totalDebit !== totalCredit && (
            <div className='text-right text-sm text-red-500 mt-1'>
              차대변 차이: {new Intl.NumberFormat('ko-KR').format(Math.abs(totalDebit - totalCredit))}
            </div>
          )}
        </div>

        {/* 액션 버튼 */}
        <div className='flex justify-end gap-2 pt-4'>
          <Button type='button' variant='ghost' onClick={onClose}>
            취소
          </Button>
          <Button type='submit' disabled={createMutation.isPending}>
            {createMutation.isPending ? '처리중...' : '전표 등록'}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * @file features/inventory/StockListPage.tsx
 * @description 재고 현황 목록을 조회하고 표시하는 페이지 컴포넌트입니다.
 * 품목별, 창고별 재고 수량(현재고, 가용재고)을 확인할 수 있습니다.
 */
import { useQuery } from '@tanstack/react-query';
import { Plus, RotateCw, Search } from 'lucide-react';
import { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { inventoryApi } from './api';
import { StockCreateModal } from './StockCreateModal';

export function StockListPage() {
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false); // 재고 등록 모달 상태

  // 재고 목록 조회 쿼리
  const {
    data: stocks,
    isLoading,
    refetch,
  } = useQuery({
    queryKey: ['stocks'],
    queryFn: async () => {
      const res = await inventoryApi.getAll();
      return res.data;
    },
  });

  return (
    <div className='space-y-6'>
      {/* 헤더: 제목 및 액션 버튼(등록, 새로고침) */}
      <div className='flex items-center justify-between'>
        <h1 className='text-2xl font-bold text-gray-900'>재고 관리</h1>
        <div className='flex gap-2'>
          <Button onClick={() => setIsCreateModalOpen(true)}>
            <Plus className='w-4 h-4 mr-2' />
            재고 등록
          </Button>
          <Button variant='outline' onClick={() => refetch()}>
            <RotateCw className='w-4 h-4 mr-2' />
            새로고침
          </Button>
        </div>
      </div>

      {/* 검색 필터 영역 */}
      <div className='bg-white p-4 rounded-lg shadow-sm border border-gray-200 flex items-center space-x-4'>
        <div className='relative flex-1 max-w-sm'>
          <Search className='absolute left-3 top-2.5 h-4 w-4 text-gray-400' />
          <Input placeholder='품목명 또는 창고 검색...' className='pl-9' />
        </div>
      </div>

      {/* 재고 목록 테이블 */}
      <div className='bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden'>
        <div className='overflow-x-auto'>
          <table className='min-w-full divide-y divide-gray-200'>
            <thead className='bg-gray-50'>
              <tr>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  품목코드
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  품목명
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>창고</th>
                <th className='px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  현재고
                </th>
                <th className='px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  가용재고
                </th>
                <th className='px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  단위
                </th>
              </tr>
            </thead>
            <tbody className='bg-white divide-y divide-gray-200'>
              {/* 로딩 표시 */}
              {isLoading && (
                <tr>
                  <td colSpan={6} className='px-6 py-10 text-center text-gray-500'>
                    로딩 중...
                  </td>
                </tr>
              )}
              {/* 데이터 렌더링 */}
              {stocks?.map((stock) => (
                <tr key={stock.id} className='hover:bg-gray-50 transition-colors'>
                  <td className='px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900'>{stock.itemCode}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{stock.itemName}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{stock.warehouseName}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-900 text-right font-medium'>
                    {stock.onHandQuantity}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-blue-600 text-right font-bold'>
                    {stock.availableQuantity}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500 text-center'>{stock.unit}</td>
                </tr>
              ))}
              {/* 데이터 없음 표시 */}
              {stocks && stocks.length === 0 && (
                <tr>
                  <td colSpan={6} className='px-6 py-10 text-center text-gray-500'>
                    재고 데이터가 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 재고 등록 모달 */}
      <StockCreateModal isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)} />
    </div>
  );
}

/**
 * @file features/sales/SalesOrderListPage.tsx
 * @description 판매 주문 목록을 조회하고 표시하는 페이지 컴포넌트입니다.
 * React Query를 사용하여 데이터를 가져오고, 테이블 형태로 렌더링합니다.
 */
import { useQuery } from '@tanstack/react-query';
import { Plus, Search } from 'lucide-react';
import { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { cn } from '../../lib/utils';
import { OrderStatus, salesApi } from './api';
import { SalesOrderCreateModal } from './SalesOrderCreateModal';

export function SalesOrderListPage() {
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false); // 주문 생성 모달 상태

  // 판매 주문 목록 조회 (React Query)
  const {
    data: sales,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['sales-orders'], // 쿼리 키
    queryFn: async () => {
      const res = await salesApi.getAll();
      return res.data;
    },
  });

  // 주문 상태에 따른 배지 색상 반환 함수
  const getStatusColor = (status: OrderStatus) => {
    switch (status) {
      case OrderStatus.CONFIRMED:
        return 'bg-green-100 text-green-800';
      case OrderStatus.PENDING:
        return 'bg-yellow-100 text-yellow-800';
      case OrderStatus.SHIPPED:
        return 'bg-blue-100 text-blue-800';
      case OrderStatus.CANCELLED:
        return 'bg-red-100 text-red-800';
      default:
        return 'bg-gray-100 text-gray-800';
    }
  };

  return (
    <div className='space-y-6'>
      {/* 헤더 영역: 제목 및 등록 버튼 */}
      <div className='flex items-center justify-between'>
        <h1 className='text-2xl font-bold text-gray-900'>수주 관리</h1>
        <Button onClick={() => setIsCreateModalOpen(true)}>
          <Plus className='w-4 h-4 mr-2' />
          신규 주문 등록
        </Button>
      </div>

      {/* 검색 및 필터 영역 */}
      <div className='bg-white p-4 rounded-lg shadow-sm border border-gray-200 flex items-center space-x-4'>
        <div className='relative flex-1 max-w-sm'>
          <Search className='absolute left-3 top-2.5 h-4 w-4 text-gray-400' />
          <Input placeholder='주문번호 또는 고객명 검색...' className='pl-9' />
        </div>
      </div>

      {/* 주문 목록 테이블 */}
      <div className='bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden'>
        <div className='overflow-x-auto'>
          <table className='min-w-full divide-y divide-gray-200'>
            <thead className='bg-gray-50'>
              <tr>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  주문번호
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  고객사
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  주문일자
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>총액</th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>상태</th>
              </tr>
            </thead>
            <tbody className='bg-white divide-y divide-gray-200'>
              {/* 로딩 상태 표시 */}
              {isLoading && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-gray-500'>
                    로딩 중...
                  </td>
                </tr>
              )}
              {/* 에러 상태 표시 */}
              {error && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-red-500'>
                    데이터를 불러오는 중 오류가 발생했습니다.
                  </td>
                </tr>
              )}
              {/* 데이터 없음 표시 */}
              {sales?.content && sales.content.length === 0 && (
                <tr>
                  <td colSpan={5} className='px-6 py-10 text-center text-gray-500'>
                    주문 내역이 없습니다.
                  </td>
                </tr>
              )}
              {/* 데이터 렌더링 */}
              {sales?.content?.map((order) => (
                <tr key={order.id} className='hover:bg-gray-50 cursor-pointer transition-colors'>
                  <td className='px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900'>{order.orderNumber}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{order.customerName}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{order.orderDate}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-900 font-medium'>
                    {new Intl.NumberFormat('ko-KR', {
                      style: 'currency',
                      currency: 'KRW',
                    }).format(order.totalAmount)}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap'>
                    <span
                      className={cn('px-2.5 py-0.5 rounded-full text-xs font-medium', getStatusColor(order.status))}
                    >
                      {order.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* 주문 등록 모달 */}
      <SalesOrderCreateModal isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)} />
    </div>
  );
}

/**
 * @file features/accounting/JournalEntryListPage.tsx
 * @description 회계 전표 목록을 조회하고 표시하는 페이지 컴포넌트입니다.
 * 전표 번호, 일자, 적요 및 차대변 합계를 테이블 형태로 보여줍니다.
 */
import { useQuery } from '@tanstack/react-query';
import { Plus, Search } from 'lucide-react';
import { useState } from 'react';

import { Button } from '../../components/ui/Button';
import { Input } from '../../components/ui/Input';
import { accountingApi } from './api';
import { JournalEntryCreateModal } from './JournalEntryCreateModal';

export function JournalEntryListPage() {
  const [isCreateModalOpen, setIsCreateModalOpen] = useState(false); // 전표 입력 모달 상태

  // 전표 목록 조회 쿼리
  const { data: journals, isLoading } = useQuery({
    queryKey: ['journal-entries'],
    queryFn: async () => {
      const res = await accountingApi.getAll();
      return res.data;
    },
  });

  return (
    <div className='space-y-6'>
      {/* 헤더: 제목 및 전표 입력 버튼 */}
      <div className='flex items-center justify-between'>
        <h1 className='text-2xl font-bold text-gray-900'>회계 전표 관리</h1>
        <Button onClick={() => setIsCreateModalOpen(true)}>
          <Plus className='w-4 h-4 mr-2' />
          전표 입력
        </Button>
      </div>

      {/* 검색 필터 영역 */}
      <div className='bg-white p-4 rounded-lg shadow-sm border border-gray-200 flex items-center space-x-4'>
        <div className='relative flex-1 max-w-sm'>
          <Search className='absolute left-3 top-2.5 h-4 w-4 text-gray-400' />
          <Input placeholder='전표번호 검색...' className='pl-9' />
        </div>
      </div>

      {/* 전표 목록 테이블 */}
      <div className='bg-white rounded-lg shadow-sm border border-gray-200 overflow-hidden'>
        <div className='overflow-x-auto'>
          <table className='min-w-full divide-y divide-gray-200'>
            <thead className='bg-gray-50'>
              <tr>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  전표번호
                </th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>날짜</th>
                <th className='px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider'>적요</th>
                <th className='px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  차변 합계
                </th>
                <th className='px-6 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  대변 합계
                </th>
                <th className='px-6 py-3 text-center text-xs font-medium text-gray-500 uppercase tracking-wider'>
                  상태
                </th>
              </tr>
            </thead>
            <tbody className='bg-white divide-y divide-gray-200'>
              {isLoading && (
                <tr>
                  <td colSpan={6} className='px-6 py-10 text-center text-gray-500'>
                    로딩 중...
                  </td>
                </tr>
              )}
              {/* 데이터 렌더링 */}
              {journals?.content?.map((entry) => (
                <tr key={entry.id} className='hover:bg-gray-50 cursor-pointer transition-colors'>
                  <td className='px-6 py-4 whitespace-nowrap text-sm font-medium text-blue-600'>{entry.entryNumber}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500'>{entry.entryDate}</td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-500 truncate max-w-xs'>
                    {entry.description}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-900 text-right'>
                    {new Intl.NumberFormat('ko-KR').format(entry.totalDebit)}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap text-sm text-gray-900 text-right'>
                    {new Intl.NumberFormat('ko-KR').format(entry.totalCredit)}
                  </td>
                  <td className='px-6 py-4 whitespace-nowrap text-center'>
                    <span className='px-2.5 py-0.5 rounded-full text-xs font-medium bg-gray-100 text-gray-800'>
                      {entry.status}
                    </span>
                  </td>
                </tr>
              ))}
              {/* 데이터 없음 표시 */}
              {journals?.content && journals.content.length === 0 && (
                <tr>
                  <td colSpan={6} className='px-6 py-10 text-center text-gray-500'>
                    전표 내역이 없습니다.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* 전표 입력 모달 */}
      <JournalEntryCreateModal isOpen={isCreateModalOpen} onClose={() => setIsCreateModalOpen(false)} />
    </div>
  );
}

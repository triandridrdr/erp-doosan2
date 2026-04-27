/**
 * @file components/layout/MainLayout.tsx
 * @description DCBJ ERP main layout: fixed white sidebar + content area with
 * a light page-path banner (e.g. "Sales / Other") matching the PPT design.
 */
import { Outlet, useLocation } from 'react-router-dom';

import { Sidebar } from './Sidebar';

// Map routes to "Section / Page" breadcrumb-like titles
const pagePathByRoute: Record<string, string> = {
  '/': 'Dashboard',
  '/sales': 'Sales / Other',
  '/sales-order-prototype': 'Sales / Online Order System',
  '/inventory': 'Inventory / Stock',
  '/accounting': 'Accounting / Journal Entry',
  '/ocr': 'Sales / OCR',
  '/ocr-new': 'Sales / OCR New',
};

export function MainLayout() {
  const location = useLocation();

  const pagePath =
    pagePathByRoute[location.pathname] ||
    (location.pathname.startsWith('/sales-order-prototype/')
      ? 'Sales / Online Order System'
      : 'Overview');

  return (
    <div className='min-h-screen bg-background font-sans text-gray-900'>
      <Sidebar />

      <div className='pl-64 flex flex-col min-h-screen'>
        {/* Light banner showing the current page path */}
        <header className='h-14 bg-white border-b border-gray-200 sticky top-0 z-20 px-8 flex items-center'>
          <h2 className='text-base font-semibold text-gray-800 tracking-tight'>{pagePath}</h2>
        </header>

        <main className='flex-1 p-6 animate-fade-in'>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

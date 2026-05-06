/**
 * @file components/layout/MainLayout.tsx
 * @description DCBJ ERP main layout: responsive layout with off-canvas sidebar
 * on mobile + breadcrumb-style page path banner (matches the PPT design).
 */
import { Menu } from 'lucide-react';
import { useState } from 'react';
import { Outlet, useLocation } from 'react-router-dom';

import { Sidebar } from './Sidebar';

// Map routes to "Section / Page" breadcrumb-like titles
const pagePathByRoute: Record<string, string> = {
  '/': 'Dashboard',
  '/sales': 'Sales / Other',
  '/sales-order-prototype': 'Sales / Sales Order Draft',
  '/inventory': 'Inventory / Stock',
  '/accounting': 'Accounting / Journal Entry',
  '/ocr': 'Sales / OCR',
  '/ocr-new': 'Pre-Sales / Sales Order Scan / OCR New',
  '/presales/purchase-order-scan': 'Pre-Sales / Sales Order Scan / Purchase Order',
  '/presales/supplementary-scan': 'Pre-Sales / Sales Order Scan / Supplementary',
  '/presales/size-per-colour-breakdown-scan': 'Pre-Sales / Sales Order Scan / Size Per Colour Breakdown',
  '/presales/total-country-breakdown-scan': 'Pre-Sales / Sales Order Scan / Total Country Breakdown',
  '/presales/all-scan': 'Pre-Sales / Sales Order Scan / All',
};

export function MainLayout() {
  const location = useLocation();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  const pagePath =
    pagePathByRoute[location.pathname] ||
    (location.pathname.startsWith('/sales-order-prototype/')
      ? 'Sales / Sales Order Draft'
      : 'Overview');

  return (
    <div className='min-h-screen bg-background font-sans text-gray-900'>
      <Sidebar mobileOpen={mobileNavOpen} onMobileClose={() => setMobileNavOpen(false)} />

      {/* Content shifts right of the sidebar on md+; full width on mobile */}
      <div className='md:pl-64 flex flex-col min-h-screen'>
        <header className='h-14 bg-white border-b border-gray-200 sticky top-0 z-20 px-4 sm:px-6 md:px-8 flex items-center gap-3'>
          <button
            type='button'
            onClick={() => setMobileNavOpen(true)}
            className='p-1.5 -ml-1.5 rounded-md text-gray-500 hover:bg-gray-100 md:hidden'
            aria-label='Open navigation'
          >
            <Menu className='h-5 w-5' />
          </button>
          <h2 className='text-sm sm:text-base font-semibold text-gray-800 tracking-tight truncate'>
            {pagePath}
          </h2>
        </header>

        <main className='flex-1 p-3 sm:p-4 md:p-6 animate-fade-in min-w-0'>
          <Outlet />
        </main>
      </div>
    </div>
  );
}

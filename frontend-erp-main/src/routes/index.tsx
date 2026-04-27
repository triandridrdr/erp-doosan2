/**
 * @file routes/index.tsx
 * @description Application routing. Defines public/private routes and the
 * authenticated layout shell.
 */
import { createBrowserRouter, Navigate, RouterProvider } from 'react-router-dom';

import { MainLayout } from '../components/layout/MainLayout';
import { JournalEntryListPage } from '../features/accounting/JournalEntryListPage';
import { useAuth } from '../features/auth/AuthContext';
import { LoginPage } from '../features/auth/LoginPage';
import { SignupPage } from '../features/auth/SignupPage';
import { DashboardPage } from '../features/dashboard/DashboardPage';
import { StockListPage } from '../features/inventory/StockListPage';
import { OcrPage } from '../features/ocr/OcrPage';
import { OcrNewPage } from '../features/ocrnew/OcrNewPage';
import { SalesOrderListPage } from '../features/sales/SalesOrderListPage';
import { SalesOrderPrototypeEditPage } from '../features/salesOrderPrototype/SalesOrderPrototypeEditPage';
import { SalesOrderPrototypeListPage } from '../features/salesOrderPrototype/SalesOrderPrototypeListPage';

function ProtectedRoute() {
  const { isAuthenticated, isLoading } = useAuth();
  if (isLoading) return <div className='p-8 text-gray-500'>Loading...</div>;
  if (!isAuthenticated) return <Navigate to='/login' replace />;
  return <MainLayout />;
}

const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  {
    path: '/',
    element: <ProtectedRoute />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'sales', element: <SalesOrderListPage /> },
      { path: 'sales-order-prototype', element: <SalesOrderPrototypeListPage /> },
      { path: 'sales-order-prototype/:id', element: <SalesOrderPrototypeEditPage /> },
      { path: 'inventory', element: <StockListPage /> },
      { path: 'accounting', element: <JournalEntryListPage /> },
      { path: 'ocr', element: <OcrPage /> },
      { path: 'ocr-new', element: <OcrNewPage /> },
    ],
  },
  { path: '*', element: <Navigate to='/' replace /> },
]);

export function Routes() {
  return <RouterProvider router={router} />;
}

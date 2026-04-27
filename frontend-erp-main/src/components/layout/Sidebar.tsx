/**
 * @file components/layout/Sidebar.tsx
 * @description DCBJ ERP sidebar navigation. White theme with collapsible sub-menus,
 * matching the PPT design (DCBJ ERP logo, deep-navy active state, accent sub-item).
 */
import {
  BarChart3,
  Briefcase,
  ChevronDown,
  CircleUser,
  ClipboardList,
  Cog,
  Factory,
  Layers,
  LayoutDashboard,
  LineChart,
  LogOut,
  Package,
  ShoppingCart,
  Truck,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { NavLink, useLocation } from 'react-router-dom';

import { useAuth } from '../../features/auth/AuthContext';
import { cn } from '../../lib/utils';

type NavChild = { name: string; href: string; children?: NavChild[] };
type NavItem = {
  name: string;
  icon: typeof LayoutDashboard;
  href?: string;
  children?: NavChild[];
};

// DCBJ ERP main navigation (mirrors the PPT). Sub-items map to existing routes
// where available; placeholder hashes keep the design intact for future pages.
const navigation: NavItem[] = [
  { name: 'Dashboard', icon: LayoutDashboard, href: '/' },
  {
    name: 'Accounting',
    icon: ClipboardList,
    children: [{ name: 'Journal Entry', href: '/accounting' }],
  },
  {
    name: 'Budget',
    icon: BarChart3,
    children: [{ name: 'Overview', href: '#budget' }],
  },
  {
    name: 'Sales',
    icon: LineChart,
    children: [
      { name: 'Basic Settings', href: '#sales-basic' },
      { name: 'Shipment Request', href: '#sales-shipment' },
      { name: 'Export', href: '#sales-export' },
      {
        name: 'Other',
        href: '/sales',
        children: [
          { name: 'Other', href: '/sales' },
          { name: 'Report', href: '#sales-report' },
        ],
      },
      { name: 'Sales/Invoicing', href: '#sales-invoicing' },
      { name: 'Collection', href: '#sales-collection' },
      { name: 'Journal Entry Management', href: '#sales-journal' },
      { name: 'Online Order System', href: '/sales-order-prototype' },
      { name: 'OCR', href: '/ocr' },
      { name: 'OCR New', href: '/ocr-new' },
    ],
  },
  {
    name: 'Procurement',
    icon: ShoppingCart,
    children: [{ name: 'Overview', href: '#procurement' }],
  },
  {
    name: 'Inventory',
    icon: Package,
    children: [{ name: 'Stock', href: '/inventory' }],
  },
  { name: 'Production', icon: Factory, children: [{ name: 'Overview', href: '#production' }] },
  { name: 'Performance', icon: Layers, children: [{ name: 'Overview', href: '#performance' }] },
  { name: 'HR', icon: Briefcase, children: [{ name: 'Overview', href: '#hr' }] },
  { name: 'Customizing', icon: Cog, children: [{ name: 'Settings', href: '#customizing' }] },
];

// All real routes that exist in the router
const REAL_ROUTES = new Set<string>([
  '/',
  '/sales',
  '/sales-order-prototype',
  '/inventory',
  '/accounting',
  '/ocr',
  '/ocr-new',
]);

function isPlaceholder(href: string) {
  return href.startsWith('#');
}

function findActiveTopLevel(pathname: string): string | null {
  for (const item of navigation) {
    if (item.href && item.href === pathname) return item.name;
    if (item.children) {
      const stack: NavChild[] = [...item.children];
      while (stack.length) {
        const c = stack.pop()!;
        if (c.href === pathname) return item.name;
        if (c.children) stack.push(...c.children);
      }
    }
  }
  return null;
}

interface SidebarProps {
  /** Whether the off-canvas drawer is currently open on mobile. */
  mobileOpen?: boolean;
  /** Called when the mobile drawer should close (link click / overlay click / X). */
  onMobileClose?: () => void;
}

export function Sidebar({ mobileOpen = false, onMobileClose }: SidebarProps = {}) {
  const { logout, user } = useAuth();
  const location = useLocation();

  // Keep the section containing the active route open by default
  const initiallyOpen = useMemo(() => {
    const top = findActiveTopLevel(location.pathname);
    return top ? new Set<string>([top]) : new Set<string>(['Sales']);
  }, [location.pathname]);

  const [openSections, setOpenSections] = useState<Set<string>>(initiallyOpen);
  const [openSubSections, setOpenSubSections] = useState<Set<string>>(new Set<string>(['Sales/Other']));

  // Lock body scroll when the mobile drawer is open
  useEffect(() => {
    if (mobileOpen) {
      const prev = document.body.style.overflow;
      document.body.style.overflow = 'hidden';
      return () => {
        document.body.style.overflow = prev;
      };
    }
  }, [mobileOpen]);

  // Auto-close drawer when route changes (mobile UX)
  useEffect(() => {
    if (mobileOpen && onMobileClose) onMobileClose();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  const toggleSection = (name: string) => {
    setOpenSections((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const toggleSubSection = (name: string) => {
    setOpenSubSections((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const renderLeaf = (child: NavChild, depth: number) => {
    const placeholder = isPlaceholder(child.href);
    const padding = depth === 1 ? 'pl-12' : 'pl-16';
    if (placeholder || !REAL_ROUTES.has(child.href)) {
      return (
        <div
          key={child.href + child.name}
          className={cn(
            'flex items-center pr-4 py-2 text-sm rounded-md text-gray-500 cursor-not-allowed select-none',
            padding,
          )}
          title='Coming soon'
        >
          {child.name}
        </div>
      );
    }
    return (
      <NavLink
        key={child.href}
        to={child.href}
        end={child.href === '/'}
        className={({ isActive }) =>
          cn(
            'flex items-center pr-4 py-2 text-sm rounded-md transition-colors',
            padding,
            isActive
              ? 'bg-accent text-white font-semibold shadow-sm'
              : 'text-gray-600 hover:text-primary hover:bg-primary-soft',
          )
        }
      >
        {child.name}
      </NavLink>
    );
  };

  return (
    <>
      {/* Mobile overlay backdrop */}
      <div
        className={cn(
          'fixed inset-0 z-30 bg-black/40 transition-opacity md:hidden',
          mobileOpen ? 'opacity-100 pointer-events-auto' : 'opacity-0 pointer-events-none',
        )}
        onClick={onMobileClose}
        aria-hidden='true'
      />

      <aside
        className={cn(
          'flex w-64 flex-col bg-white border-r border-gray-200 h-screen fixed left-0 top-0 z-40 transition-transform duration-200',
          // Mobile: slide in/out. Desktop: always visible.
          mobileOpen ? 'translate-x-0' : '-translate-x-full',
          'md:translate-x-0',
        )}
        aria-label='Primary navigation'
      >
        {/* Logo + (mobile only) close button */}
        <div className='flex h-16 items-center px-5 border-b border-gray-100'>
          <div className='w-9 h-9 mr-3 flex items-center justify-center'>
            <svg viewBox='0 0 40 40' className='w-9 h-9' aria-hidden='true'>
              <defs>
                <linearGradient id='dcbjGrad' x1='0' y1='0' x2='1' y2='1'>
                  <stop offset='0%' stopColor='#0E4D92' />
                  <stop offset='100%' stopColor='#2D7EAA' />
                </linearGradient>
              </defs>
              <rect x='2' y='2' width='36' height='36' rx='8' fill='url(#dcbjGrad)' />
              <path
                d='M11 12h9c4.5 0 7.5 3 7.5 7.5S24.5 27 20 27h-9V12zm4 4v7h4.5c2 0 3.5-1.5 3.5-3.5S21.5 16 19.5 16H15z'
                fill='white'
              />
            </svg>
          </div>
          <h1 className='text-lg font-bold text-gray-900 tracking-tight'>DCBJ ERP</h1>
          <button
            type='button'
            onClick={onMobileClose}
            className='ml-auto p-1.5 rounded-md hover:bg-gray-100 text-gray-500 md:hidden'
            aria-label='Close navigation'
          >
            <X className='h-5 w-5' />
          </button>
        </div>

      {/* Navigation */}
      <nav className='flex-1 overflow-y-auto py-3'>
        {navigation.map((item) => {
          const Icon = item.icon;

          // Top-level direct link (Dashboard)
          if (item.href && !item.children) {
            return (
              <NavLink
                key={item.name}
                to={item.href}
                end={item.href === '/'}
                className={({ isActive }) =>
                  cn(
                    'flex items-center px-5 py-2.5 mx-2 my-0.5 rounded-md text-sm font-medium transition-colors',
                    isActive
                      ? 'bg-primary text-white shadow-sm'
                      : 'text-gray-700 hover:bg-primary-soft hover:text-primary',
                  )
                }
              >
                {({ isActive }) => (
                  <>
                    <Icon className={cn('mr-3 h-5 w-5 shrink-0', isActive ? 'text-white' : 'text-gray-500')} />
                    <span>{item.name}</span>
                  </>
                )}
              </NavLink>
            );
          }

          const isOpen = openSections.has(item.name);
          const activeTop = findActiveTopLevel(location.pathname) === item.name;

          return (
            <div key={item.name} className='mx-2 my-0.5'>
              <button
                type='button'
                onClick={() => toggleSection(item.name)}
                className={cn(
                  'w-full flex items-center justify-between px-5 py-2.5 rounded-md text-sm font-medium transition-colors',
                  activeTop
                    ? 'bg-primary text-white shadow-sm'
                    : 'text-gray-700 hover:bg-primary-soft hover:text-primary',
                )}
              >
                <span className='flex items-center'>
                  <Icon className={cn('mr-3 h-5 w-5 shrink-0', activeTop ? 'text-white' : 'text-gray-500')} />
                  {item.name}
                </span>
                <ChevronDown
                  className={cn(
                    'h-4 w-4 transition-transform',
                    isOpen ? 'rotate-180' : '',
                    activeTop ? 'text-white' : 'text-gray-400',
                  )}
                />
              </button>

              {isOpen && item.children && (
                <div className='mt-1 mb-1'>
                  {item.children.map((child) => {
                    if (child.children && child.children.length > 0) {
                      const key = `${item.name}/${child.name}`;
                      const subOpen = openSubSections.has(key);
                      const childActive =
                        child.href && location.pathname === child.href
                          ? true
                          : child.children.some((g) => g.href === location.pathname);
                      return (
                        <div key={key}>
                          <button
                            type='button'
                            onClick={() => toggleSubSection(key)}
                            className={cn(
                              'w-full flex items-center justify-between pr-3 pl-12 py-2 text-sm rounded-md transition-colors',
                              childActive
                                ? 'bg-accent text-white font-semibold'
                                : 'text-gray-600 hover:text-primary hover:bg-primary-soft',
                            )}
                          >
                            <span>{child.name}</span>
                            <ChevronDown
                              className={cn('h-3.5 w-3.5 transition-transform', subOpen ? 'rotate-180' : '')}
                            />
                          </button>
                          {subOpen && <div className='mt-0.5'>{child.children.map((g) => renderLeaf(g, 2))}</div>}
                        </div>
                      );
                    }
                    return renderLeaf(child, 1);
                  })}
                </div>
              )}
            </div>
          );
        })}
      </nav>

      {/* User profile + logout */}
      <div className='border-t border-gray-100 p-4 flex items-center justify-between bg-white'>
        <div className='flex items-center min-w-0'>
          <div className='h-9 w-9 rounded-full bg-gray-200 flex items-center justify-center'>
            <CircleUser className='h-5 w-5 text-gray-500' />
          </div>
          <div className='ml-3 min-w-0'>
            <p className='text-sm font-semibold text-gray-800 truncate'>{user?.name || 'user name'}</p>
            <p className='text-xs text-gray-400 truncate'>team name</p>
          </div>
        </div>
        <button
          onClick={logout}
          className='p-2 rounded-md text-gray-400 hover:text-primary hover:bg-primary-soft transition-colors'
          title='Log out'
        >
          <LogOut size={18} />
        </button>
      </div>
        {/* Decorative truck icon hidden so unused import is suppressed */}
        <span className='hidden'>
          <Truck />
        </span>
      </aside>
    </>
  );
}

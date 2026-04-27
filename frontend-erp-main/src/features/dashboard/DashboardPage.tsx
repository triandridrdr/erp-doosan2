/**
 * @file features/dashboard/DashboardPage.tsx
 * @description DCBJ ERP dashboard page (matches the PPT design).
 * Shows Sales Overview, Inventory Summary, Sales & Purchase bar chart,
 * and Income Breakdown donut chart. All numbers are illustrative until
 * a backend dashboard endpoint is wired up; backend logic is unchanged.
 */
import { Banknote, Boxes, Receipt, TrendingUp } from 'lucide-react';
import { useState } from 'react';

import { cn } from '../../lib/utils';

const KRW = (n: number) => `₩ ${n.toLocaleString('en-US')}`;

// Mock monthly Purchase / Sales values mirroring the PPT chart
const monthly = [
  { m: 'Jan', purchase: 52000, sales: 48000 },
  { m: 'Feb', purchase: 56000, sales: 50000 },
  { m: 'Mar', purchase: 50000, sales: 47000 },
  { m: 'Apr', purchase: 41000, sales: 40000 },
  { m: 'May', purchase: 44000, sales: 42000 },
  { m: 'Jun', purchase: 46000, sales: 43000 },
  { m: 'Jul', purchase: 55000, sales: 52000 },
  { m: 'Aug', purchase: 47000, sales: 45000 },
  { m: 'Sep', purchase: 44000, sales: 42000 },
  { m: 'Oct', purchase: 48000, sales: 46000 },
  { m: 'Nov', purchase: 45000, sales: 43000 },
  { m: 'Dec', purchase: 47000, sales: 44000 },
];

// Income breakdown for the donut chart
const breakdown = [
  { label: 'Marketing Channels', value: 22000, color: '#FBBF24' },
  { label: 'Direct Sales', value: 8400, color: '#3B82F6' },
  { label: 'Offline Channels', value: 18600, color: '#34D399' },
  { label: 'Other Channels', value: 15300, color: '#F87171' },
];

function StatCard({
  title,
  amount,
  label,
  icon: Icon,
  iconBg,
}: {
  title: string;
  amount: string;
  label: string;
  icon: typeof Banknote;
  iconBg: string;
}) {
  return (
    <div className='flex items-center gap-3 px-4 py-3'>
      <div className={cn('w-10 h-10 rounded-md flex items-center justify-center shrink-0', iconBg)}>
        <Icon className='w-5 h-5 text-primary' />
      </div>
      <div>
        <p className='text-base font-semibold text-gray-900 leading-tight'>{title}</p>
        <p className='text-xs text-gray-500 mt-0.5'>{label}</p>
      </div>
      <div className='ml-auto text-right'>
        <p className='text-lg font-bold text-gray-900'>{amount}</p>
      </div>
    </div>
  );
}

function SalesOverview() {
  return (
    <div className='bg-white rounded-lg border border-gray-200 p-5'>
      <h3 className='text-sm font-semibold text-gray-800 mb-4'>Sales Overview</h3>
      <div className='grid grid-cols-3 divide-x divide-gray-100'>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <Receipt className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>{KRW(2200)}</p>
          <p className='text-xs text-gray-500 mt-0.5'>Sales</p>
        </div>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <TrendingUp className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>{KRW(18300)}</p>
          <p className='text-xs text-gray-500 mt-0.5'>Revenue</p>
        </div>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <Banknote className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>{KRW(868)}</p>
          <p className='text-xs text-gray-500 mt-0.5'>Profit</p>
        </div>
      </div>
    </div>
  );
}

function InventorySummary() {
  return (
    <div className='bg-white rounded-lg border border-gray-200 p-5'>
      <h3 className='text-sm font-semibold text-gray-800 mb-4'>Inventory Summary</h3>
      <div className='grid grid-cols-3 divide-x divide-gray-100'>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <Boxes className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>868</p>
          <p className='text-xs text-gray-500 mt-0.5'>Quantity in Hand</p>
        </div>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <Boxes className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>200</p>
          <p className='text-xs text-gray-500 mt-0.5'>To be received</p>
        </div>
        <div className='flex flex-col items-center px-2'>
          <div className='w-9 h-9 rounded-md bg-primary-soft flex items-center justify-center mb-2'>
            <Boxes className='w-5 h-5 text-primary' />
          </div>
          <p className='text-base font-semibold text-gray-900'>868</p>
          <p className='text-xs text-gray-500 mt-0.5'>Quantity in Hand</p>
        </div>
      </div>
    </div>
  );
}

function SalesPurchaseChart() {
  const max = Math.max(...monthly.flatMap((d) => [d.purchase, d.sales]));
  const yTicks = [60000, 50000, 40000, 30000, 20000, 10000];

  return (
    <div className='bg-white rounded-lg border border-gray-200 p-3 sm:p-5'>
      <h3 className='text-sm font-semibold text-gray-800 mb-4'>Sales &amp; Purchase</h3>

      {/* Horizontal scroll container on narrow screens */}
      <div className='overflow-x-auto -mx-3 sm:mx-0 px-3 sm:px-0'>
        <div className='relative h-56 min-w-[480px]'>
          {/* Y axis ticks + grid lines */}
          <div className='absolute inset-0 flex flex-col justify-between text-[10px] text-gray-400'>
            {yTicks.map((t) => (
              <div key={t} className='flex items-center gap-2'>
                <span className='w-10 text-right'>{t.toLocaleString()}</span>
                <div className='flex-1 border-t border-dashed border-gray-100' />
              </div>
            ))}
          </div>

          {/* Bars */}
          <div className='absolute inset-0 ml-12 flex items-end justify-between gap-1.5 pb-4'>
            {monthly.map((d) => (
              <div key={d.m} className='flex flex-col items-center flex-1'>
                <div className='flex items-end gap-0.5 h-44'>
                  <div
                    className='w-2.5 rounded-t bg-blue-400'
                    style={{ height: `${(d.purchase / max) * 100}%` }}
                    title={`Purchase: ${d.purchase}`}
                  />
                  <div
                    className='w-2.5 rounded-t bg-emerald-400'
                    style={{ height: `${(d.sales / max) * 100}%` }}
                    title={`Sales: ${d.sales}`}
                  />
                </div>
                <span className='text-[10px] text-gray-500 mt-1'>{d.m}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className='flex items-center gap-5 mt-3 text-xs text-gray-600'>
        <span className='flex items-center gap-1.5'>
          <span className='w-2.5 h-2.5 rounded-full bg-blue-400' /> Purchase
        </span>
        <span className='flex items-center gap-1.5'>
          <span className='w-2.5 h-2.5 rounded-full bg-emerald-400' /> Sales
        </span>
      </div>
    </div>
  );
}

function IncomeBreakdown() {
  const [range, setRange] = useState<'Day' | 'Week' | 'Month'>('Day');
  const total = breakdown.reduce((s, x) => s + x.value, 0);

  // Build SVG donut path segments
  let cumulative = 0;
  const radius = 60;
  const stroke = 18;
  const center = 80;

  const arcs = breakdown.map((seg) => {
    const start = cumulative / total;
    const end = (cumulative + seg.value) / total;
    cumulative += seg.value;
    return { ...seg, start, end };
  });

  const polar = (frac: number) => {
    const angle = frac * Math.PI * 2 - Math.PI / 2;
    return [center + radius * Math.cos(angle), center + radius * Math.sin(angle)] as const;
  };

  return (
    <div className='bg-white rounded-lg border border-gray-200 p-3 sm:p-5'>
      <div className='flex flex-col sm:flex-row sm:items-center sm:justify-between gap-2 mb-4'>
        <h3 className='text-sm font-semibold text-gray-800'>Income Breakdown</h3>
        <div className='flex items-center bg-gray-100 rounded-md p-0.5 text-xs self-start sm:self-auto'>
          {(['Day', 'Week', 'Month'] as const).map((r) => (
            <button
              key={r}
              type='button'
              onClick={() => setRange(r)}
              className={cn(
                'px-3 py-1 rounded-md transition-colors',
                range === r ? 'bg-white shadow text-gray-900 font-semibold' : 'text-gray-500 hover:text-gray-700',
              )}
            >
              {r}
            </button>
          ))}
        </div>
      </div>

      <div className='flex flex-col sm:flex-row items-center sm:items-center gap-4 sm:gap-6'>
        <svg width='160' height='160' viewBox='0 0 160 160' className='shrink-0'>
          {arcs.map((seg) => {
            const [sx, sy] = polar(seg.start);
            const [ex, ey] = polar(seg.end);
            const large = seg.end - seg.start > 0.5 ? 1 : 0;
            return (
              <path
                key={seg.label}
                d={`M ${sx} ${sy} A ${radius} ${radius} 0 ${large} 1 ${ex} ${ey}`}
                stroke={seg.color}
                strokeWidth={stroke}
                fill='none'
              />
            );
          })}
          <text x='80' y='86' textAnchor='middle' className='fill-gray-700 text-sm font-semibold'>
            16%
          </text>
        </svg>

        <div className='grid grid-cols-2 gap-x-5 gap-y-2 text-xs'>
          {breakdown.map((seg) => (
            <div key={seg.label} className='flex items-center justify-between gap-3'>
              <span className='flex items-center gap-1.5 text-gray-600'>
                <span className='w-2 h-2 rounded-full' style={{ backgroundColor: seg.color }} />
                {seg.label}
              </span>
              <span className='font-semibold text-gray-800'>${(seg.value / 1000).toFixed(1)}k</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export function DashboardPage() {
  return (
    <div className='space-y-5'>
      <div className='grid grid-cols-1 lg:grid-cols-2 gap-5'>
        <SalesOverview />
        <InventorySummary />
      </div>
      <div className='grid grid-cols-1 lg:grid-cols-2 gap-5'>
        <SalesPurchaseChart />
        <IncomeBreakdown />
      </div>
    </div>
  );
}

// Keep StatCard exported for potential reuse but mark it with a void usage
// to avoid an unused-warning if the component is not consumed elsewhere.
void StatCard;

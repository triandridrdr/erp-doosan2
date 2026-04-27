/**
 * @file components/ui/Modal.tsx
 * @description Centered overlay dialog rendered via React Portal.
 * Supports custom max-width and an optional footer slot, matching the DCBJ design.
 */
import { X } from 'lucide-react';
import React, { useEffect } from 'react';
import { createPortal } from 'react-dom';

import { cn } from '../../lib/utils';

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
  /** Tailwind max-width class, e.g. 'max-w-2xl' (default), 'max-w-5xl' */
  maxWidth?: string;
  /** Hide the body padding when caller wants full control */
  bodyClassName?: string;
}

export function Modal({
  isOpen,
  onClose,
  title,
  children,
  footer,
  maxWidth = 'max-w-2xl',
  bodyClassName,
}: ModalProps) {
  useEffect(() => {
    if (isOpen) {
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = 'unset';
    }
    return () => {
      document.body.style.overflow = 'unset';
    };
  }, [isOpen]);

  if (!isOpen) return null;

  return createPortal(
    <div className='fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 animate-fade-in'>
      <div
        className={cn(
          'bg-white rounded-lg shadow-xl w-full max-h-[92vh] flex flex-col animate-slide-up',
          maxWidth,
        )}
        role='dialog'
        aria-modal='true'
      >
        <div className='flex items-center justify-between px-5 py-3 border-b border-gray-200'>
          <h2 className='text-base font-semibold text-gray-900'>{title}</h2>
          <button
            onClick={onClose}
            className='p-1 rounded-full hover:bg-gray-100 transition-colors text-gray-500 hover:text-gray-700'
            aria-label='Close modal'
          >
            <X className='w-5 h-5' />
          </button>
        </div>

        <div className={cn('flex-1 overflow-y-auto', bodyClassName ?? 'p-5')}>{children}</div>

        {footer && (
          <div className='px-5 py-3 border-t border-gray-200 bg-gray-50 rounded-b-lg flex items-center justify-end gap-2'>
            {footer}
          </div>
        )}
      </div>
    </div>,
    document.body,
  );
}

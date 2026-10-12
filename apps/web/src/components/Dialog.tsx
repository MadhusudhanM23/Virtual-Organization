import { useEffect, useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';

export interface DialogProps {
  title: string;
  onClose: () => void;
  children: ReactNode;
  /** Close button label (translated by the caller). */
  closeLabel: string;
  testId?: string;
  wide?: boolean;
}

/**
 * Modal dialog on the native `<dialog>` element: `showModal()` gives the focus trap, inert background and Escape
 * handling; focus returns to the opener on close. Mounted = open.
 */
export function Dialog({ title, onClose, children, closeLabel, testId, wide = false }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return undefined;
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (typeof el.showModal === 'function') {
      if (!el.open) el.showModal();
    } else {
      el.setAttribute('open', '');
    }
    const onCancel = (e: Event) => {
      e.preventDefault();
      onCloseRef.current();
    };
    el.addEventListener('cancel', onCancel);
    return () => {
      el.removeEventListener('cancel', onCancel);
      if (typeof el.close === 'function' && el.open) el.close();
      opener?.focus();
    };
  }, []);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      data-testid={testId}
      className={`m-auto ${wide ? 'w-[min(44rem,calc(100vw-2rem))]' : 'w-[min(32rem,calc(100vw-2rem))]'} rounded-card border border-line bg-surface p-0 text-ink-900 shadow-float backdrop:bg-navy-900/40`}
    >
      <div className="flex items-start justify-between gap-3 border-b border-line px-5 py-3">
        <h2 id={titleId} className="text-base font-semibold">
          {title}
        </h2>
        <button
          type="button"
          onClick={onClose}
          aria-label={closeLabel}
          className="grid size-8 shrink-0 place-items-center rounded-control text-ink-600 hover:bg-surface-sunken hover:text-ink-900"
        >
          <X aria-hidden="true" className="size-4" />
        </button>
      </div>
      <div className="max-h-[70vh] overflow-y-auto px-5 py-4">{children}</div>
    </dialog>
  );
}

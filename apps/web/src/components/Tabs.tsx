import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';
import { cn } from '../lib/cn';

export interface TabDefinition {
  id: string;
  label: string;
  icon?: LucideIcon;
}

export interface TabsProps {
  tabs: TabDefinition[];
  activeId: string;
  onChange: (id: string) => void;
  /** Accessible name of the tablist. */
  label: string;
  /** Unique prefix so ids stay valid when several tab sets exist. */
  idPrefix: string;
  /** Content of the active tab. */
  children: ReactNode;
}

/** Accessible tabs: roving tabindex, arrow/Home/End keys, tab<->panel ARIA wiring. */
export function Tabs({ tabs, activeId, onChange, label, idPrefix, children }: TabsProps) {
  const refs = useRef(new Map<string, HTMLButtonElement>());

  const focusTab = (id: string | undefined) => {
    if (id === undefined) return;
    onChange(id);
    refs.current.get(id)?.focus();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = tabs.length - 1;
    let next: number | null = null;
    if (event.key === 'ArrowRight') next = index === last ? 0 : index + 1;
    else if (event.key === 'ArrowLeft') next = index === 0 ? last : index - 1;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = last;
    if (next === null) return;
    event.preventDefault();
    focusTab(tabs[next]?.id);
  };

  return (
    <div className="flex min-h-0 flex-col">
      <div
        role="tablist"
        aria-label={label}
        className="flex gap-1 overflow-x-auto border-b border-line px-2 pt-1"
      >
        {tabs.map((tab, index) => {
          const selected = tab.id === activeId;
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              ref={(node) => {
                if (node) refs.current.set(tab.id, node);
                else refs.current.delete(tab.id);
              }}
              type="button"
              role="tab"
              id={`${idPrefix}-tab-${tab.id}`}
              aria-selected={selected}
              aria-controls={`${idPrefix}-panel-${tab.id}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.id)}
              onKeyDown={(event) => onKeyDown(event, index)}
              className={cn(
                'relative inline-flex shrink-0 items-center gap-1.5 rounded-t-lg px-3 py-2.5 text-sm font-medium transition-colors',
                selected
                  ? 'text-accent-700 after:absolute after:inset-x-2 after:-bottom-px after:h-0.5 after:rounded-full after:bg-accent-600'
                  : 'text-ink-600 hover:bg-surface-sunken hover:text-ink-900',
              )}
            >
              {Icon ? <Icon aria-hidden="true" className="size-4" /> : null}
              {tab.label}
            </button>
          );
        })}
      </div>
      <div
        role="tabpanel"
        id={`${idPrefix}-panel-${activeId}`}
        aria-labelledby={`${idPrefix}-tab-${activeId}`}
        className="min-h-0 overflow-y-auto"
      >
        {children}
      </div>
    </div>
  );
}

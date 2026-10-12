import { Box } from 'lucide-react';
import { EmptyState } from './EmptyState';
import { NAV_ITEMS } from '../app/navigation';
import { t } from '../i18n';
import { useDocumentTitle } from '../lib/useDocumentTitle';

/**
 * Titled empty state for a section whose feature has not been built yet. Looks up its title, description
 * and arrival phase from the navigation config so the sidebar and the page can never disagree.
 */
export function PlaceholderPage({ itemId }: { itemId: string }) {
  const item = NAV_ITEMS.find((candidate) => candidate.id === itemId);
  if (!item) throw new Error(`Unknown navigation item: ${itemId}`);

  const title = t(item.labelId);
  const Icon = item.icon;
  useDocumentTitle(title);

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 p-4 md:p-8">
      <header className="flex items-start gap-4">
        <span
          aria-hidden="true"
          className="grid size-11 shrink-0 place-items-center rounded-xl bg-navy-900 text-accent-400 shadow-card"
        >
          <Icon className="size-5" strokeWidth={1.75} />
        </span>
        <div className="min-w-0">
          <h1 className="text-2xl font-semibold tracking-tight text-ink-900">{title}</h1>
          <p className="mt-1 max-w-2xl text-sm text-ink-600">{t(item.descriptionId)}</p>
        </div>
      </header>

      {item.stage ? (
        <section
          aria-label={t('page.stage.title')}
          className="stage-grid grid min-h-[22rem] place-items-center rounded-card border border-line shadow-card md:min-h-[26rem]"
        >
          <EmptyState
            icon={Box}
            title={t('page.stage.title')}
            description={t('page.stage.description')}
            phase={item.arrivesInPhase}
          />
        </section>
      ) : (
        <section className="rounded-card border border-line bg-surface shadow-card">
          <EmptyState icon={Icon} phase={item.arrivesInPhase} />
        </section>
      )}
    </div>
  );
}

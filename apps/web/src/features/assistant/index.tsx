import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';
import { AssistantPanel } from './AssistantPanel';

/** Route `/assistant` (Plan 9.3): the AI operations assistant, full width. The same panel is in the right column. */
export default function AssistantPage() {
  useDocumentTitle(t('nav.assistant'));
  return (
    <div className="mx-auto flex w-full max-w-4xl flex-col gap-4 p-4 md:p-6">
      <header>
        <h1 className="text-2xl font-semibold tracking-tight text-ink-900">{t('nav.assistant')}</h1>
        <p className="mt-1 max-w-2xl text-sm text-ink-600">{t('page.assistant.description')}</p>
      </header>
      <section className="rounded-card border border-line bg-surface p-4 shadow-card">
        <AssistantPanel variant="page" />
      </section>
    </div>
  );
}

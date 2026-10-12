import { Link } from 'react-router';
import { FileQuestion } from 'lucide-react';
import { DEFAULT_PATH } from '../../app/navigation';
import { EmptyState } from '../../components/EmptyState';
import { t } from '../../i18n';
import { useDocumentTitle } from '../../lib/useDocumentTitle';

export default function NotFoundPage() {
  useDocumentTitle(t('page.notFound.title'));
  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-6 p-4 md:p-8">
      <h1 className="text-2xl font-semibold tracking-tight text-ink-900">
        {t('page.notFound.title')}
      </h1>
      <section className="rounded-card border border-line bg-surface shadow-card">
        <EmptyState
          icon={FileQuestion}
          title={t('page.notFound.title')}
          description={t('page.notFound.description')}
        />
        <p className="pb-8 text-center text-sm">
          <Link
            className="font-semibold text-accent-700 underline underline-offset-2"
            to={DEFAULT_PATH}
          >
            {t('page.notFound.back')}
          </Link>
        </p>
      </section>
    </div>
  );
}

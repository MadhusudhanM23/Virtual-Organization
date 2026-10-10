import { useEffect } from 'react';
import { t } from '../i18n';

/** Sets `document.title` to "<page> · Virtual Organization" so route changes are announced. */
export function useDocumentTitle(page: string): void {
  useEffect(() => {
    document.title = `${page} · ${t('app.name')}`;
  }, [page]);
}

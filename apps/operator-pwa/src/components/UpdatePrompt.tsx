import { useRegisterSW } from 'virtual:pwa-register/react';
import { t } from '../i18n';

/**
 * Service-worker lifecycle prompts (vite-plugin-pwa, registerType "prompt"): "ready to work offline" once the app
 * shell is precached, and "new version" with an explicit Update action (never a surprise reload mid-task).
 */
export function UpdatePrompt() {
  const {
    offlineReady: [offlineReady, setOfflineReady],
    needRefresh: [needRefresh, setNeedRefresh],
    updateServiceWorker,
  } = useRegisterSW();

  if (!offlineReady && !needRefresh) return null;
  return (
    <div
      className="banner fixed inset-x-4 bottom-20 z-50 shadow-lg"
      role="status"
      data-testid="pwa-prompt"
    >
      <p className="m-0">{needRefresh ? t('pwa.updateReady') : t('pwa.offlineReady')}</p>
      <div className="mt-2 flex gap-2">
        {needRefresh ? (
          <button
            type="button"
            className="btn btn-primary"
            onClick={() => void updateServiceWorker(true)}
          >
            {t('pwa.update')}
          </button>
        ) : null}
        <button
          type="button"
          className="btn"
          onClick={() => {
            setOfflineReady(false);
            setNeedRefresh(false);
          }}
        >
          {t('pwa.dismiss')}
        </button>
      </div>
    </div>
  );
}

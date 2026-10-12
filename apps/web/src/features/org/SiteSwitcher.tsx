import { useEffect, useState } from 'react';
import { Building2 } from 'lucide-react';
import { t } from '../../i18n';
import { fetchOrganizations } from '../../lib/api/client';
import { flattenSites } from './flattenSites';
import { useSiteFormat } from '../../lib/useSiteFormat';
import { useSessionStore } from '../../stores/sessionStore';
import { useUiStore } from '../../stores/uiStore';

/** Current time at the selected site, ticking each minute, formatted in the site's zone and locale. */
function SiteClock() {
  const format = useSiteFormat();
  const [now, setNow] = useState(() => new Date().toISOString());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date().toISOString()), 60_000);
    return () => window.clearInterval(id);
  }, []);
  return (
    <time
      dateTime={now}
      title={t('site.switcher.localTime')}
      className="hidden truncate text-xs text-ink-500 lg:block"
    >
      {format.dateTime(now)}
    </time>
  );
}

/** Lists the sites in the caller's scope (GET /api/organizations) and stores the selection in uiStore. */
export function SiteSwitcher() {
  const sessionKey = useSessionStore((s) => s.me?.actor_ref ?? null);
  const sites = useUiStore((s) => s.sites);
  const selectedSiteId = useUiStore((s) => s.selectedSiteId);
  const setSites = useUiStore((s) => s.setSites);
  const setSelectedSiteId = useUiStore((s) => s.setSelectedSiteId);

  useEffect(() => {
    if (!sessionKey) return undefined;
    let cancelled = false;
    fetchOrganizations()
      .then((response) => {
        if (!cancelled) setSites(flattenSites(response));
      })
      .catch(() => {
        if (!cancelled) setSites([]);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionKey, setSites]);

  return (
    <div
      className="topbar-context flex min-w-0 items-center gap-2.5"
      role="group"
      aria-label={t('topbar.context.label')}
    >
      <span
        aria-hidden="true"
        className="hidden size-9 shrink-0 place-items-center rounded-control bg-surface-sunken text-ink-600 sm:grid"
      >
        <Building2 className="size-[1.125rem]" strokeWidth={1.75} />
      </span>
      {sites.length === 0 ? (
        <span className="truncate text-sm font-medium text-ink-600">{t('site.switcher.none')}</span>
      ) : (
        <>
          <label htmlFor="site-switcher" className="sr-only">
            {t('site.switcher.label')}
          </label>
          <select
            id="site-switcher"
            value={selectedSiteId ?? ''}
            onChange={(e) => setSelectedSiteId(e.target.value)}
            className="h-10 min-w-0 max-w-full rounded-control border border-line bg-surface px-2 text-sm font-medium text-ink-900"
          >
            {sites.map((site) => (
              <option key={site.id} value={site.id}>
                {site.name}
              </option>
            ))}
          </select>
          <SiteClock />
        </>
      )}
    </div>
  );
}
